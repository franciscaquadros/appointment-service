"""Fault controls and recovery probes, used only in the disposable load project."""

from datetime import datetime, timedelta, timezone
import json
import time
import uuid


def request(command, prefix, url, method='GET', body=None, headers=None):
    # The existing stub image has Python/urllib. No extra image or host port needed.
    script = """
import json, sys, urllib.request, urllib.error
url, method, body, headers = sys.argv[1:]
request = urllib.request.Request(url, data=None if body == 'null' else body.encode(),
    headers=json.loads(headers), method=method)
try:
    response = urllib.request.urlopen(request, timeout=8)
except urllib.error.HTTPError as error:
    response = error
raw = response.read().decode()
try: body = json.loads(raw)
except ValueError: body = raw
print(json.dumps({'status': response.status, 'body': body}))
"""
    return json.loads(command(prefix + ['exec', '-T', 'calendar', 'python', '-c', script,
        url, method, json.dumps(body), json.dumps(headers or {})]))


def configure_calendar(command, prefix, **config):
    result = request(command, prefix, 'http://calendar:8080/__admin/config', 'POST',
                     config, {'Content-Type': 'application/json'})
    assert result['status'] == 200, result


def operation_counts(sql):
    return json.loads(sql("""
        SELECT coalesce(json_object_agg(operation,counts),'{}'::json) FROM (
          SELECT operation,json_build_object(
            'succeeded',count(*) FILTER (WHERE status='SUCCEEDED'),
            'pending',count(*) FILTER (WHERE status IN ('READY','BLOCKED')),
            'failed',count(*) FILTER (WHERE status='FAILED'),
            'retried',count(*) FILTER (WHERE attempt_count>1),
            'attempts',coalesce(sum(attempt_count),0),
            'last_errors',count(*) FILTER (WHERE last_error_code IS NOT NULL),
            'error_codes',json_agg(DISTINCT last_error_code) FILTER (WHERE last_error_code IS NOT NULL)) AS counts
          FROM integration_job GROUP BY operation
        ) operations;
    """))


class ProviderRecovery:
    """Drive short faults while the existing k6 mixed workload keeps running."""

    def __init__(self, command, prefix, sql):
        self.command, self.prefix, self.sql = command, prefix, sql
        self.events = []
        self.stage = 0
        self.changed = 0

    def tick(self, elapsed):
        if self.stage == 0:
            created = int(self.sql("""
                SELECT count(*) FROM patient WHERE normalized_email LIKE 'load-main-%@example.invalid';
            """))
            if created < 10:
                return
            configure_calendar(self.command, self.prefix, status=503, retryAfter=5)
            label = 'calendar_503_started'
        elif self.stage == 1 and time.monotonic() - self.changed >= 20:
            # The stub commits before sleeping: the caller times out after 5s.
            configure_calendar(self.command, self.prefix, status=0, delaySeconds=8)
            label = 'calendar_timeout_started'
        elif self.stage == 2 and time.monotonic() - self.changed >= 15:
            configure_calendar(self.command, self.prefix, status=0, delaySeconds=0)
            label = 'calendar_recovered'
        elif self.stage == 3:
            self.command(self.prefix + ['pause', 'mail'])
            label = 'smtp_paused'
        elif self.stage == 4 and time.monotonic() - self.changed >= 20:
            self.command(self.prefix + ['unpause', 'mail'])
            label = 'smtp_resumed'
        else:
            return
        self.events.append({'event': label, 'elapsed_seconds': elapsed,
                            'operations': operation_counts(self.sql)})
        self.stage += 1
        self.changed = time.monotonic()
        print(label, flush=True)

    def assert_observed(self):
        assert self.stage == 5, 'Not all provider faults ran'
        first, timeout, recovered, smtp_down, smtp_up = [x['operations'] for x in self.events]
        assert 'HTTP_503' in (timeout['CALENDAR']['error_codes'] or []), 'Calendar outage was not observed'
        assert 'HTTP_UNAVAILABLE' in (recovered['CALENDAR']['error_codes'] or []), 'Calendar timeout was not observed'
        assert timeout['ROOM']['succeeded'] > first['ROOM']['succeeded'], 'Room lane stalled during calendar outage'
        assert smtp_up['ROOM']['succeeded'] > smtp_down['ROOM']['succeeded'], 'Room lane stalled during SMTP outage'
        assert smtp_up['CALENDAR']['succeeded'] > smtp_down['CALENDAR']['succeeded'], 'Calendar lane stalled during SMTP outage'
        assert 'SMTP_DELIVERY_FAILED' in (smtp_up['EMAIL']['error_codes'] or []), 'SMTP outage was not observed'


def recovery_probes(command, prefix, sql):
    results = {}

    def wait_until(probe, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = probe()
            if result:
                return result
            time.sleep(0.5)
        raise AssertionError('Recovery probe timed out')

    def payload(label, day):
        start = datetime(datetime.now(timezone.utc).year + 1, 1, 1, 10, tzinfo=timezone.utc) + timedelta(days=day)
        return {'patientName': 'Recovery Probe', 'patientEmail': f'probe-{label}@example.invalid',
                'specialtyCode': 'CARDIOLOGY', 'startsAt': start.isoformat()}

    def book(key, body):
        return request(command, prefix, 'http://app:8080/api/v1/appointments', 'POST', body,
                       {'Content-Type': 'application/json', 'Idempotency-Key': key})

    def complete(appointment):
        return int(sql(f"SELECT count(*) FROM integration_job WHERE appointment_id='{appointment}' AND status='SUCCEEDED'")) == 3

    configure_calendar(command, prefix, status=0, delaySeconds=8)
    key = str(uuid.uuid4())
    body = payload('crash', 2500)
    booked = book(key, body)
    assert booked['status'] == 201, booked
    appointment = booked['body']['id']
    script = """
import sqlite3,sys
with sqlite3.connect('/data/reservations.sqlite') as db:
 print(db.execute('SELECT count(*) FROM reservation WHERE key=?',(sys.argv[1],)).fetchone()[0])
"""
    # Prove remote commit, then kill while the delayed HTTP response is in flight.
    wait_until(lambda: command(prefix + ['exec', '-T', 'calendar', 'python', '-c', script,
                                       appointment + ':CALENDAR']) == '1', 15)
    print('Killing app after confirmed remote calendar commit', flush=True)
    command(prefix + ['kill', '-s', 'SIGKILL', 'app'])
    pending = json.loads(sql(f"""
        SELECT json_agg(json_build_object('operation',operation,'status',status,'attempts',attempt_count))
        FROM integration_job WHERE appointment_id='{appointment}';
    """))
    calendar = next(job for job in pending if job['operation'] == 'CALENDAR')
    assert calendar['status'] == 'READY' and calendar['attempts'] == 0, pending
    configure_calendar(command, prefix, status=0, delaySeconds=0)
    command(prefix + ['up', '-d', '--wait', '--wait-timeout', '120', 'app'])
    replay = book(key, body)
    assert replay['status'] == 201 and replay['body']['id'] == appointment, replay
    wait_until(lambda: complete(appointment))
    remote_count = int(command(prefix + ['exec', '-T', 'calendar', 'python', '-c', script,
                                       appointment + ':CALENDAR']))
    assert remote_count == 1
    results['app_crash_after_remote_commit'] = {'passed': True, 'appointment_id': appointment,
        'jobs_after_kill': pending, 'same_id_on_replay': True, 'remote_calendar_reservations': remote_count}

    print('App crash and idempotent recovery passed', flush=True)

    # Stop/start the existing database container so the same data directory survives.
    print('Stopping database to check readiness and booking behavior', flush=True)
    command(prefix + ['stop', '-t', '5', 'database'])
    readiness = request(command, prefix, 'http://app:8080/actuator/health/readiness')
    liveness = request(command, prefix, 'http://app:8080/actuator/health/liveness')
    unavailable_key, unavailable_body = str(uuid.uuid4()), payload('database', 2501)
    unavailable = book(unavailable_key, unavailable_body)
    assert readiness['status'] == 503 and liveness['status'] == 200, (readiness, liveness)
    assert unavailable['status'] == 503, unavailable
    command(prefix + ['start', 'database'])
    wait_until(lambda: request(command, prefix, 'http://app:8080/actuator/health/readiness')['status'] == 200)
    restored = book(unavailable_key, unavailable_body)
    again = book(unavailable_key, unavailable_body)
    assert restored['status'] == again['status'] == 201, (restored, again)
    assert restored['body']['id'] == again['body']['id']
    wait_until(lambda: complete(restored['body']['id']))
    assert complete(appointment), 'Earlier booking was lost after database restart'
    results['database_outage'] = {'passed': True, 'readiness_during_outage': readiness['status'],
        'liveness_during_outage': liveness['status'], 'booking_during_outage': unavailable['status'],
        'readiness_after_recovery': 200, 'replayed_booking_status': restored['status'],
        'same_id_on_replay': True, 'earlier_booking_preserved': True}
    print('Database readiness and idempotent recovery passed', flush=True)
    return results


def graceful_shutdown_probe(command, prefix, sql):
    """SIGTERM must finish one in-flight call, leaving the next job for restart."""
    configure_calendar(command, prefix, status=0, delaySeconds=4)
    start = datetime(datetime.now(timezone.utc).year + 1, 1, 1, 10, tzinfo=timezone.utc)

    def book(label, day):
        result = request(command, prefix, 'http://app:8080/api/v1/appointments', 'POST',
            {'patientName': 'Shutdown Probe', 'patientEmail': f'shutdown-{label}@example.invalid',
             'specialtyCode': 'CARDIOLOGY', 'startsAt': (start + timedelta(days=day)).isoformat()},
            {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
        assert result['status'] == 201, result
        return result['body']['id']

    def remote_count(appointment):
        script = """
import sqlite3,sys
with sqlite3.connect('/data/reservations.sqlite') as db:
 print(db.execute('SELECT count(*) FROM reservation WHERE key=?',(sys.argv[1],)).fetchone()[0])
"""
        return int(command(prefix + ['exec', '-T', 'calendar', 'python', '-c', script,
                                     appointment + ':CALENDAR']))

    def jobs():
        return json.loads(sql("""
            SELECT json_agg(json_build_object('appointment',appointment_id,'operation',operation,
                'status',status,'attempts',attempt_count) ORDER BY appointment_id,operation)
            FROM integration_job;
        """))

    first = book('first', 0)
    deadline = time.monotonic() + 15
    while not remote_count(first):
        assert time.monotonic() < deadline, 'Calendar call did not start'
        time.sleep(0.1)
    second = book('second', 1)
    before = jobs()
    active = next(j for j in before if j['appointment'] == first and j['operation'] == 'CALENDAR')
    assert active['status'] == 'READY' and active['attempts'] == 0, 'First call finished before the signal'
    assert remote_count(second) == 0
    print('Sending SIGTERM while the first calendar response is delayed', flush=True)
    stopped_at = time.monotonic()
    command(prefix + ['stop', 'app'])
    elapsed = round(time.monotonic() - stopped_at, 3)
    container = command(prefix + ['ps', '-aq', 'app'])
    state = json.loads(command(['docker', 'inspect', container, '--format', '{{json .State}}']))
    after = jobs()
    first_job = next(j for j in after if j['appointment'] == first and j['operation'] == 'CALENDAR')
    second_job = next(j for j in after if j['appointment'] == second and j['operation'] == 'CALENDAR')
    assert first_job['status'] == 'SUCCEEDED' and first_job['attempts'] == 1, after
    assert second_job['status'] == 'READY' and second_job['attempts'] == 0, after
    assert remote_count(first) == 1 and remote_count(second) == 0
    assert elapsed < 30 and state['ExitCode'] in (0, 143) and not state['OOMKilled'], state
    configure_calendar(command, prefix, status=0, delaySeconds=0)
    command(prefix + ['up', '-d', '--wait', '--wait-timeout', '120', 'app'])
    deadline = time.monotonic() + 30
    while int(sql("SELECT count(*) FROM integration_job WHERE status='SUCCEEDED'")) != 6:
        assert time.monotonic() < deadline, 'Pending work did not complete after restart'
        time.sleep(0.5)
    assert remote_count(first) == remote_count(second) == 1
    return {'passed': True, 'signal': 'SIGTERM via docker compose stop',
        'provider_delay_seconds': 4, 'scheduler_wait_seconds': 30, 'container_grace_seconds': 45,
        'stop_elapsed_seconds': elapsed, 'exit_code': state['ExitCode'], 'oom_killed': state['OOMKilled'],
        'first_appointment': first, 'second_appointment': second,
        'jobs_before_signal': before, 'jobs_after_stop': after,
        'jobs_after_restart': jobs(), 'remote_reservations_after_restart': 2}
