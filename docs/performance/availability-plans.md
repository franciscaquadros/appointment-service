# Availability query-plan inspection

[README testing](../../README.md#testing) · [Database model](../data-model.md)

Patient, doctor and room overlap predicates use the range expression supported by the GiST exclusion indexes. This report compares scalar timestamp predicates with range-overlap predicates on the same fixture and schema.

## Reproduction

Run from the project root (the directory containing `pom.xml` and `compose.yaml`). If your terminal is in `docs/performance`, run `cd ../..` first.

In IntelliJ, run the command in the Terminal tool window from the project root, or set the run configuration's working directory to the project root. The Markdown code block's Run action may use `docs/performance` as its working directory; the command below assumes the project root.

Run `python3 scripts/inspect-availability-plans.py` with Python 3 and Docker available. The script creates and removes its own PostgreSQL 17.6 container, applies the schema migrations, reads the three queries from the repository source, and constructs equivalent scalar predicates for comparison. It does not access the application database. It writes `docs/performance/availability-plans.json`; preserve that file separately if retaining an existing measurement.

40,000 appointments; 20 doctors, rooms and patients; 2,000 daily non-overlapping appointments per resource starting 2020-01-01 at 10:00 UTC; ANALYZE; default planner settings; one warmup and three measured runs per query; no concurrent traffic.

The fixture is synthetic and uniformly distributed: every doctor, room and patient has the same historical schedule. It represents accumulated appointment history, not measured production traffic. Queries use SQL literals rather than JDBC prepared statements; application tests separately exercise JDBC binding. Timings exclude network round trips. These are warm-cache plan checks, not a throughput or concurrency benchmark.

## Results

Each time is the median of three measured runs. Buffers are shared hits plus reads at the root of that median run; parent counters already include child counters. [Raw plans](availability-plans.json) retain exact SQL, all three execution times and the PostgreSQL version. These measurements describe that fixture/run; rerun the script for the current source/environment. Raw `before`/`after` labels denote scalar/range predicates respectively.

| Query | Slot | Scalar ms | Range ms | Scalar buffers | Range buffers |
|---|---|---:|---:|---:|---:|
| patientHasOverlap | future-free | 0.543 | 0.251 | 73 | 25 |
| patientHasOverlap | overlap | 0.085 | 0.271 | 15 | 29 |
| patientHasOverlap | adjacent-after | 2.696 | 0.258 | 728 | 28 |
| patientHasOverlap | adjacent-before | 3.064 | 0.301 | 728 | 28 |
| findAvailableDoctor | future-free | 3.427 | 0.306 | 732 | 29 |
| findAvailableDoctor | overlap | 3.233 | 0.592 | 1120 | 76 |
| findAvailableDoctor | adjacent-after | 6.506 | 0.726 | 2112 | 58 |
| findAvailableDoctor | adjacent-before | 6.683 | 0.631 | 2112 | 58 |
| findAvailableRoom | future-free | 3.702 | 0.335 | 731 | 28 |
| findAvailableRoom | overlap | 3.319 | 0.731 | 1072 | 76 |
| findAvailableRoom | adjacent-after | 6.811 | 0.630 | 2096 | 57 |
| findAvailableRoom | adjacent-before | 6.290 | 0.563 | 2096 | 57 |

All range-predicate plans contain a range-overlap index condition. Scalar predicates either scanned appointment rows or used only the ID part of an exclusion index, filtering by timestamps afterward. PostgreSQL sometimes chose the `patient_overlap` GiST index for doctor and room queries because its range expression also supports a range-only scan; the chosen index name is not a correctness requirement.

Range predicates reduced buffer use and execution time in 11 of 12 cases. The patient positive-overlap case increased from 0.085 ms to 0.271 ms and from 15 to 29 buffers because the scalar plan found a match early in a sequential scan. Indexed range lookup does not mean every small-data or early-match query is faster.

## Correctness

All 24 scalar/range checks passed: overlap, both adjacent boundaries and a future free slot, across all three queries.

The Spring integration suite also checks both adjacent boundaries, identical and partial overlaps through the repository methods. Existing booking-concurrency tests continue to cover allocation and exclusion-constraint behavior.
