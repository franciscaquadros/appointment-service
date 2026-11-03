"""Run isolated smoke, load or reliability checks; requires Python 3 and Docker Compose.

No host ports or persistent volumes. Every invocation builds the current source,
creates its own Compose project, and removes only that project on exit.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import time
import uuid

from reliability import ProviderRecovery, recovery_probes, request, operation_counts, graceful_shutdown_probe
from smoke import smoke_probe

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--rate-per-minute', type=int, default=100)
parser.add_argument('--duration-seconds', type=int, default=180)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--smoke', action='store_true', help='Only check booking, pagination, real adapters and captured email')
mode.add_argument('--reliability', action='store_true', help='Inject provider faults, then probe app and DB recovery')
mode.add_argument('--shutdown-check', action='store_true', help='Only run the focused graceful-shutdown probe')
parser.add_argument('--output', type=Path, default=ROOT / 'docs/performance/load-baseline')
args = parser.parse_args()
if args.rate_per_minute < 1 or args.duration_seconds < 60:
    parser.error('Rate must be positive and measured duration must be at least 60 seconds')
if args.smoke and args.output == ROOT / 'docs/performance/load-baseline':
    args.output = ROOT / 'docs/performance/smoke-run'
if args.shutdown_check and args.output == ROOT / 'docs/performance/load-baseline':
    args.output = ROOT / 'docs/performance/shutdown-run'
if args.reliability:
    if args.duration_seconds < 180:
        parser.error('Reliability mode needs at least 180 measured seconds')
    if args.output == ROOT / 'docs/performance/load-baseline':
        args.output = ROOT / 'docs/performance/reliability-run'
args.output.mkdir(parents=True, exist_ok=True)
project = 'healthcare-load-' + uuid.uuid4().hex[:10]
prefix = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'scripts/load/compose.yaml')]


def command(cmd, **kwargs):
    result = subprocess.run(cmd, text=True, capture_output=True, **kwargs)
    if result.returncode:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        result.check_returncode()
    return result.stdout.strip()


def sql(statement):
    return command(prefix + ['exec', '-T', 'database', 'psql', '-X', '-qAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'appointments'], input=statement)


def snapshot():
    return json.loads(sql("""
        SELECT json_build_object(
          'pending', count(*) FILTER (WHERE status IN ('READY','BLOCKED')),
          'ready', count(*) FILTER (WHERE status='READY'),
          'blocked', count(*) FILTER (WHERE status='BLOCKED'),
          'failed', count(*) FILTER (WHERE status='FAILED'),
          'succeeded', count(*) FILTER (WHERE status='SUCCEEDED'),
          'oldest_pending_seconds', coalesce(extract(epoch FROM now()-min(created_at)
            FILTER (WHERE status IN ('READY','BLOCKED'))),0)) FROM integration_job;
    """))


samples = []
metadata = {}
faults = None
passed = False
process = None
try:
    print(f'Building and starting disposable project {project}', flush=True)
    command(prefix + ['up', '--build', '-d', '--wait', '--wait-timeout', '240'])
    if not (args.shutdown_check or args.smoke):
        sql((ROOT / 'scripts/load/seed.sql').read_text())
    metadata = {
        'started_utc': datetime.now(timezone.utc).isoformat(),
        'host': platform.platform(),
        'docker_resources': json.loads(command(['docker', 'info', '--format', '{{json .}}'])),
        'rate_per_minute': args.rate_per_minute,
        'measured_seconds': args.duration_seconds,
        'planned_measured_requests': args.rate_per_minute * args.duration_seconds / 60,
        'k6_image': 'grafana/k6:1.6.0',
        'integration_delay_method': 'Final successful attempt transaction-start updated_at minus appointment transaction-start created_at; approximate, excludes final provider call and commit.',
        'warmup_seconds': 30,
        'measured_start_seconds': 40,
        'history_appointments': 40000,
        'resource_limits': {'app': '2 CPUs / 1 GiB', 'database': '2 CPUs / 1 GiB',
                            'k6': '1 CPU / 512 MiB', 'each_stub': '0.5 CPU / 256 MiB'},
        'external_services': 'Local calendar/room contract stubs, zero configured delay; Mailpit SMTP. Worker enabled. Tracing sampling disabled; no Prometheus scraping.',
    }
    # Retain useful environment fields without Docker host paths or proxy settings.
    metadata['docker_resources'] = {key: metadata['docker_resources'][key]
                                   for key in ['NCPU', 'MemTotal', 'ServerVersion', 'Architecture', 'OperatingSystem']}
    source = hashlib.sha256()
    for path in sorted([ROOT / 'pom.xml', ROOT / 'Dockerfile', * (ROOT / 'src/main').rglob('*')]):
        if path.is_file():
            source.update(str(path.relative_to(ROOT)).encode())
            source.update(path.read_bytes())
    metadata['application_source_sha256'] = source.hexdigest()
    image_ids = command(prefix + ['images', '-q']).splitlines()
    metadata['images'] = json.loads(command(['docker', 'image', 'inspect', *sorted(set(image_ids))]))
    metadata['images'] = [{'id': item['Id'], 'tags': item.get('RepoTags'),
                           'digests': item.get('RepoDigests')} for item in metadata['images']]
    if args.shutdown_check or args.smoke:
        for key in ['rate_per_minute', 'measured_seconds', 'planned_measured_requests', 'k6_image',
                    'warmup_seconds', 'measured_start_seconds', 'integration_delay_method']:
            metadata.pop(key, None)
        metadata['history_appointments'] = 0
        if args.smoke:
            metadata['mode'] = 'smoke-only'
            metadata['smoke_probe'] = smoke_probe(command, prefix, sql)
            result = metadata['smoke_probe']
        else:
            metadata['mode'] = 'graceful-shutdown-only'
            metadata['external_services'] = 'Calendar response delayed four seconds; healthy room stub and Mailpit. Real adapters and worker enabled.'
            metadata['shutdown_probe'] = graceful_shutdown_probe(command, prefix, sql)
            result = metadata['shutdown_probe']
        metadata['ended_utc'] = datetime.now(timezone.utc).isoformat()
        passed = True
        print(json.dumps(result, indent=2), flush=True)
        raise SystemExit(0)
    faults = ProviderRecovery(command, prefix, sql) if args.reliability else None
    if faults:
        metadata['external_services'] += ' Provider failures are injected; see fault-events.json.'
    print('Starting 30s warmup, 10s gap, then measured traffic', flush=True)
    started = time.monotonic()
    with (args.output / 'k6-stdout.log').open('w') as out, (args.output / 'k6-stderr.log').open('w') as err:
        process = subprocess.Popen(prefix + ['run', '--rm', '-T', '--no-deps',
            '-e', f'RATE_PER_MINUTE={args.rate_per_minute}',
            '-e', f'DURATION={args.duration_seconds}s', 'k6', 'run', '--quiet',
            '/scripts/baseline.js'], stdout=out, stderr=err)
        while process.poll() is None:
            samples.append({'elapsed_seconds': round(time.monotonic() - started, 3), **snapshot()})
            if faults:
                faults.tick(round(time.monotonic() - started, 3))
            time.sleep(2)
    k6_image = json.loads(command(['docker', 'image', 'inspect', 'grafana/k6:1.6.0']))[0]
    metadata['images'].append({'id': k6_image['Id'], 'tags': k6_image.get('RepoTags'),
                               'digests': k6_image.get('RepoDigests')})
    metadata['k6_exit_code'] = process.returncode
    metadata['load_wall_seconds'] = round(time.monotonic() - started, 3)
    raw = (args.output / 'k6-stdout.log').read_text()
    summary = json.loads(raw[raw.index('{'):])
    (args.output / 'k6-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    # Give the real worker at most 30 seconds to finish remaining jobs.
    drain_start = time.monotonic()
    final = snapshot()
    while final['pending'] and time.monotonic() - drain_start < 30:
        time.sleep(1)
        final = snapshot()
    metadata['drain_wait_seconds'] = round(time.monotonic() - drain_start, 3)
    metadata['final_jobs'] = final
    metadata['measured_integrations'] = json.loads(sql("""
        WITH per_appointment AS (
          SELECT a.id, count(*)=3 AND bool_and(j.status='SUCCEEDED') AS completed,
            extract(epoch FROM max(j.updated_at)-a.created_at) AS completion_seconds
          FROM appointment a JOIN patient p ON p.id=a.patient_id
          JOIN integration_job j ON j.appointment_id=a.id
          WHERE p.normalized_email LIKE 'load-main-%@example.invalid'
          GROUP BY a.id,a.created_at
        ) SELECT json_build_object('appointments',count(*),
          'completed',count(*) FILTER (WHERE completed),
          'completion_p95_seconds',percentile_cont(0.95) WITHIN GROUP (ORDER BY completion_seconds)
            FILTER (WHERE completed),
          'completion_p99_seconds',percentile_cont(0.99) WITHIN GROUP (ORDER BY completion_seconds)
            FILTER (WHERE completed),
          'completion_max_seconds',max(completion_seconds) FILTER (WHERE completed))
        FROM per_appointment;
    """))
    if faults:
        (args.output / 'fault-events.json').write_text(json.dumps(faults.events, indent=2) + '\n')
        faults.assert_observed()
        metadata['operations_after_load'] = operation_counts(sql)
        metadata['recovery_probes'] = recovery_probes(command, prefix, sql)
        metadata['final_jobs_after_probes'] = snapshot()
        metadata['invariants'] = json.loads(sql("""
            SELECT json_build_object(
              'appointments', (SELECT count(*) FROM appointment a JOIN patient p ON p.id=a.patient_id
                WHERE p.normalized_email LIKE 'load-%' OR p.normalized_email LIKE 'probe-%'),
              'jobs', (SELECT count(*) FROM integration_job),
              'maximum_recorded_attempts', (SELECT max(attempt_count) FROM integration_job),
              'orphan_jobs', (SELECT count(*) FROM integration_job j LEFT JOIN appointment a
                ON a.id=j.appointment_id WHERE a.id IS NULL),
              'missing_prerequisites', (SELECT count(*) FROM integration_job email
                WHERE email.operation='EMAIL' AND email.status='SUCCEEDED' AND EXISTS (
                  SELECT 1 FROM integration_job dependency
                  WHERE dependency.appointment_id=email.appointment_id
                    AND dependency.operation IN ('CALENDAR','ROOM') AND dependency.status<>'SUCCEEDED')),
              'overlap_pairs', (SELECT count(*) FROM appointment a JOIN patient p ON p.id=a.patient_id
                JOIN appointment b ON a.id<>b.id
                  AND (a.doctor_id=b.doctor_id OR a.room_id=b.room_id OR a.patient_id=b.patient_id)
                  AND tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(b.starts_at,b.ends_at,'[)')
                WHERE p.normalized_email LIKE 'load-%' OR p.normalized_email LIKE 'probe-%'));
        """))
        expected = metadata['invariants']['appointments']
        metadata['remote_reservations'] = {
            name: request(command, prefix, f'http://{name}:8080/__admin/state')['body']['reservations']
            for name in ['calendar', 'rooms']}
        metadata['smtp_messages'] = request(command, prefix, 'http://mail:8025/api/v1/messages')['body']['total']
        checks = metadata['invariants']
        assert checks['jobs'] == expected * 3 and not checks['overlap_pairs']
        assert not checks['orphan_jobs'] and not checks['missing_prerequisites']
        assert checks['maximum_recorded_attempts'] <= 5
        assert all(count == expected for count in metadata['remote_reservations'].values())
        assert metadata['smtp_messages'] >= expected
        assert not metadata['final_jobs_after_probes']['pending']
        assert not metadata['final_jobs_after_probes']['failed']
    metadata['ended_utc'] = datetime.now(timezone.utc).isoformat()
    (args.output / 'environment-and-jobs.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (args.output / 'backlog-samples.json').write_text(json.dumps(samples, indent=2) + '\n')
    integrations = metadata['measured_integrations']
    created = summary['metrics']['baseline_created']['values']['count']
    requests = summary['metrics']['baseline_requests']['values']['count']
    passed = (process.returncode == 0 and not final['failed'] and not final['pending']
              and requests >= int(args.rate_per_minute * args.duration_seconds / 60)
              and created > 0 and integrations['appointments'] == created
              and integrations['completed'] == created)
    print(json.dumps({'passed': passed, 'jobs': final,
                      'measured_integrations': integrations, 'output': str(args.output)}, indent=2), flush=True)
    if passed:
        (args.output / 'app-failure.log').unlink(missing_ok=True)
    if not passed:
        raise SystemExit('Baseline failed; inspect the saved measurements')
finally:
    # Keep partial evidence on failure, including failed probes and interrupted runs.
    if metadata:
        metadata['passed'] = passed
        if not passed and sys.exc_info()[1] is not None:
            metadata['failure'] = str(sys.exc_info()[1])
        (args.output / 'environment-and-jobs.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (args.output / 'backlog-samples.json').write_text(json.dumps(samples, indent=2) + '\n')
    if faults:
        (args.output / 'fault-events.json').write_text(json.dumps(faults.events, indent=2) + '\n')
    if process is not None and process.poll() is None:
        process.terminate()
        process.wait(timeout=15)
    # Logs are useful even when build/startup or k6 fails.
    try:
        logs = command(prefix + ['logs', '--no-color', '--tail', '50', 'app'])
        if not passed:
            (args.output / 'app-failure.log').write_text(logs + '\n')
    finally:
        # A terminated Compose client can leave its one-off k6 container running.
        owned = command(['docker', 'ps', '-aq', '--filter',
                         'label=com.docker.compose.project=' + project]).splitlines()
        if owned:
            command(['docker', 'rm', '-f', '-v', *owned])
        command(prefix + ['down', '--volumes', '--remove-orphans'])
