"""Small happy-path check using the existing disposable Compose services."""

import base64
from datetime import datetime, timedelta, timezone
import json
import time
import uuid
from zoneinfo import ZoneInfo

from reliability import request, operation_counts


def smoke_probe(command, prefix, sql):
    started = time.monotonic()
    endpoint = 'http://app:8080/api/v1/appointments'
    auth = {'Authorization': 'Basic ' + base64.b64encode(b'demo-admin:demo-admin-only').decode()}
    start = (datetime.now(timezone.utc) + timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
    bookings = []
    receipt_fields = {'id', 'startsAt', 'endsAt', 'status', 'integrationStatus'}
    for label in ['first', 'second']:
        body = {'patientName': f'Smoke {label}', 'patientEmail': f'smoke-{label}@example.invalid',
                'specialtyCode': 'CARDIOLOGY', 'startsAt': start.isoformat()}
        headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
        created = request(command, prefix, endpoint, 'POST', body, headers)
        assert created['status'] == 201, created
        assert created['body']['status'] == 'CONFIRMED', created
        assert set(created['body']) == receipt_fields, created
        # Patient/resource details are available only to an administrator.
        details = request(command, prefix, endpoint + '/' + created['body']['id'], headers=auth)
        assert details['status'] == 200, details
        bookings.append((details['body'], body, headers))

    # The same request key must resolve to the same booking without extra jobs.
    first, body, headers = bookings[0]
    replay = request(command, prefix, endpoint, 'POST', body, headers)
    assert replay['status'] == 201 and replay['body']['id'] == first['id'], replay
    assert set(replay['body']) == receipt_fields, replay

    page = request(command, prefix, endpoint + '?limit=1', headers=auth)
    assert page['status'] == 200 and len(page['body']['items']) == 1, page
    cursor = page['body']['nextCursor']
    assert isinstance(cursor, str) and cursor, page
    next_page = request(command, prefix, endpoint + '?limit=1&cursor=' + cursor, headers=auth)
    assert next_page['status'] == 200 and len(next_page['body']['items']) == 1, next_page
    assert next_page['body']['nextCursor'] is None, next_page
    assert {page['body']['items'][0]['id'], next_page['body']['items'][0]['id']} == {
        booking['id'] for booking, _, _ in bookings}

    deadline = time.monotonic() + 30
    while True:
        operations = operation_counts(sql)
        if set(operations) == {'CALENDAR', 'ROOM', 'EMAIL'} and all(
                counts['succeeded'] == 2 and counts['pending'] == counts['failed'] == 0
                for counts in operations.values()):
            break
        assert time.monotonic() < deadline, f'Jobs did not complete within 30 seconds: {operations}'
        time.sleep(0.5)
    assert int(sql('SELECT count(*) FROM appointment')) == 2
    assert int(sql('SELECT count(*) FROM integration_job')) == 6

    # Inspect the same durable stub records used by the recovery probes; compare
    # payloads as well as counts so the right booking/resources/times are checked.
    resources = json.loads(sql("""
        SELECT json_object_agg(a.id,json_build_object('calendar',d.external_id,'rooms',r.external_id))
        FROM appointment a JOIN doctor d ON d.id=a.doctor_id JOIN room r ON r.id=a.room_id;
    """))
    remote_script = """
import json, sqlite3
with sqlite3.connect('/data/reservations.sqlite') as db:
    print(json.dumps({key:json.loads(payload) for key,payload in db.execute('SELECT key,payload FROM reservation')}))
"""
    for service, operation in [('calendar', 'CALENDAR'), ('rooms', 'ROOM')]:
        reservations = json.loads(command(prefix + ['exec', '-T', service, 'python', '-c', remote_script]))
        assert set(reservations) == {booking['id'] + ':' + operation for booking, _, _ in bookings}
        for booking, _, _ in bookings:
            payload = reservations[booking['id'] + ':' + operation]
            assert set(payload) == {'appointmentId', 'resourceId', 'startsAt', 'endsAt'}, payload
            assert payload['appointmentId'] == booking['id'], payload
            assert payload['resourceId'] == resources[booking['id']][service], payload
            for field in ['startsAt', 'endsAt']:
                assert datetime.fromisoformat(payload[field]) == datetime.fromisoformat(booking[field]), payload

    messages = request(command, prefix, 'http://mail:8025/api/v1/messages')
    assert messages['status'] == 200 and messages['body']['total'] == 2, messages
    captured = []
    for message in messages['body']['messages']:
        detail = request(command, prefix, 'http://mail:8025/api/v1/message/' + message['ID'])
        assert detail['status'] == 200, detail
        captured.append(detail['body'])
    lisbon = ZoneInfo('Europe/Lisbon')
    for booking, _, _ in bookings:
        matching = [message for message in captured
                    if [recipient['Address'] for recipient in message['To']] == [booking['patientEmail']]]
        assert len(matching) == 1, matching
        message = matching[0]
        assert message['Subject'] == 'Confirmação de consulta', message
        for expected in [booking['patientName'], booking['doctor'], booking['room'], booking['id'],
                         datetime.fromisoformat(booking['startsAt']).astimezone(lisbon).strftime('%H:%M'),
                         datetime.fromisoformat(booking['endsAt']).astimezone(lisbon).strftime('%H:%M')]:
            assert expected in message['Text'], f'Missing confirmation content: {expected}'
        completed = request(command, prefix, endpoint + '/' + booking['id'], headers=auth)
        assert completed['status'] == 200 and completed['body']['integrationStatus'] == 'COMPLETED', completed

    return {'passed': True, 'elapsed_seconds': round(time.monotonic() - started, 3),
            'appointments': 2, 'jobs': 6, 'operations': operations,
            'calendar_reservations': 2, 'room_reservations': 2, 'confirmation_emails': 2,
            'checks': ['minimal public booking/replay receipts and protected administrator details',
                       'booking and idempotent replay', 'HTTP cursor continuation',
                       'provider payloads and stable operation keys', 'all jobs succeeded',
                       'email recipients, subject, names, reference and Lisbon start/end times',
                       'HTTP completed integration status']}
