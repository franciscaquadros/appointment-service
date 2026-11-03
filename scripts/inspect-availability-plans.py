"""Compare actual repository queries on an isolated PostgreSQL 17.6 fixture.

Run from any directory with Python 3 and Docker. Writes the complete plans to
docs/performance/availability-plans.json; never connects to the application DB.
"""

import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "postgres:17.6-alpine"


def docker(*args, **kwargs):
    return subprocess.run(
        ["docker", *args], check=True, text=True, capture_output=True, **kwargs
    ).stdout.strip()


def fixture_id(value):
    return str(uuid.UUID(hashlib.md5(value.encode()).hexdigest()))


container = docker("run", "--rm", "-d", "-e", "POSTGRES_PASSWORD=fixture-only", IMAGE)
try:
    for _ in range(60):
        try:
            docker("exec", container, "pg_isready", "-U", "postgres")
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise RuntimeError("Fixture database did not become ready")

    def sql(statement):
        return docker(
            "exec", "-i", container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
            "-U", "postgres", input=statement,
        )

    # Apply the real migrations, without demo data.
    migrations = ROOT / "src/main/resources/db/migration"
    for path in sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__")[0][1:])):
        sql(path.read_text())
    sql("""
        INSERT INTO specialty VALUES (md5('specialty')::uuid,'TEST','Test');
        INSERT INTO doctor SELECT md5('doctor'||i)::uuid,'Doctor '||i,true,'doctor-'||i
          FROM generate_series(1,20) i;
        INSERT INTO room SELECT md5('room'||i)::uuid,'R'||i,true,'room-'||i
          FROM generate_series(1,20) i;
        INSERT INTO patient SELECT md5('patient'||i)::uuid,'Patient '||i,
          i||'@example.invalid',i||'@example.invalid' FROM generate_series(1,20) i;
        INSERT INTO doctor_specialty SELECT id,md5('specialty')::uuid FROM doctor;
        INSERT INTO appointment(id,patient_id,doctor_id,room_id,specialty_id,starts_at,ends_at)
          SELECT md5(i||':'||n)::uuid,md5('patient'||i)::uuid,md5('doctor'||i)::uuid,
            md5('room'||i)::uuid,md5('specialty')::uuid,
            '2020-01-01 10:00Z'::timestamptz+n*interval '1 day',
            '2020-01-01 10:30Z'::timestamptz+n*interval '1 day'
          FROM generate_series(1,20) i CROSS JOIN generate_series(0,1999) n;
        ANALYZE;
    """)
    source = (ROOT / "src/main/java/pt/healthcare/appointments/booking/persistence/JdbcAppointmentRepository.java").read_text()
    methods = ["patientHasOverlap", "findAvailableDoctor", "findAvailableRoom"]
    cases = {
        "future-free": ("2030-01-01 10:00Z", "2030-01-01 10:30Z", False),
        "overlap": ("2022-01-01 10:15Z", "2022-01-01 10:45Z", True),
        "adjacent-after": ("2022-01-01 10:30Z", "2022-01-01 11:00Z", False),
        "adjacent-before": ("2022-01-01 09:30Z", "2022-01-01 10:00Z", False),
    }
    results = []
    for method in methods:
        body = source.split("public ", 1)[-1].split(method + "(", 1)[1]
        match = re.search(r'jdbc\.sql\(\s*("""[\s\S]*?"""|"[^"\n]*")', body)
        query = match.group(1)
        query = query[3:-3] if query.startswith('"""') else query[1:-1]
        for case, (start, end, occupied) in cases.items():
            current = query
            for name, value in {"patient": fixture_id("patient1"), "specialty": fixture_id("specialty"), "start": start, "end": end}.items():
                current = re.sub(r":" + name + r"\b", "'" + value + "'", current)
            baseline = re.sub(
                r"tstzrange\((a\.)?starts_at, (?:a\.)?ends_at, '\[\)'\) && tstzrange\(([^,]+), ([^,]+), '\[\)'\)",
                lambda m: f"{m[1] or ''}starts_at < {m[3]} AND {m[1] or ''}ends_at > {m[2]}",
                current,
            )
            assert baseline != current, "Range predicate was not found"
            expected = ("t" if occupied else "f") if method == "patientHasOverlap" else (None if occupied else "available")
            for version, statement in [("before", baseline), ("after", current)]:
                actual = sql(statement)
                if method == "patientHasOverlap":
                    assert actual == expected, (method, case, version, actual)
                else:
                    assert bool(actual) == (expected == "available"), (method, case, version, actual)
                # Warm the query, then record three runs; do not force planner settings.
                sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement)
                plans = [json.loads(sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement))[0] for _ in range(3)]
                results.append({"method": method, "case": case, "version": version, "sql": statement.strip(), "execution_ms": [p["Execution Time"] for p in plans], "plan": sorted(plans, key=lambda p: p["Execution Time"])[1]})
    report = {
        "postgres": sql("SELECT version()"),
        "fixture": "40,000 appointments; 20 doctors, rooms and patients; 2,000 daily non-overlapping appointments per resource starting 2020-01-01 at 10:00 UTC; ANALYZE; default planner settings; one warmup and three measured runs per query; no concurrent traffic",
        "semantic_checks": "All 24 before/after checks passed: overlap, both adjacent boundaries, future free slot, across all three queries.",
        "results": results,
    }
    output = ROOT / "docs/performance/availability-plans.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Saved {len(results)} query comparisons to {output}")
finally:
    docker("rm", "-f", container)
