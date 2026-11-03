# Operations

[README](../README.md) · [Processing decision](adr/003-post-booking-processing.md)

## Local development and configuration

Run commands from the project root, the directory containing `pom.xml` and `compose.yaml`.

Start the complete demo with `docker compose up -d --build`. The Compose file sets the application's `demo` Spring profile; no Compose profile flag is needed. Published ports bind to localhost. `docker compose down` stops the stack and retains the database/provider named volumes. `docker compose down -v` also deletes those volumes and their demo data.

For IDE/Maven development, start dependencies and run the application on the host:

```sh
docker compose up -d database calendar rooms mail jaeger
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Use Java 25 and the Maven wrapper for the IDE SDK/importer/runner. Avoid running the containerized app and host app on port 8080 together. Prometheus targets `app:8080`; host development needs a corresponding scrape target change.

Settings live in [application.properties](../src/main/resources/application.properties) and the [demo overrides](../src/main/resources/application-demo.properties).

| Setting | Default / purpose |
|---|---|
| `appointments.duration` | 30 minutes |
| `appointments.opening-time` / `closing-time` | 08:00 / 20:00 in Lisbon |
| `BOOKING_REQUESTS_PER_SECOND` | 10 shared booking POSTs per one-second window per instance |
| `integrations.poll-delay` / `jobs-per-poll` | 1 second after a partial/empty batch; 10 jobs per batch per lane; full batches drain immediately |
| `integrations.maximum-attempts` | 5 recorded attempts |
| `integrations.initial-retry-delay` / `maximum-retry-delay` | Exponential delay from 5 seconds, capped at 15 minutes; starts when failure is recorded |
| `integrations.request-timeout` | 5-second HTTP connect/read timeouts |
| `spring.mail.properties.mail.smtp.*timeout` | 5-second SMTP connect/read/write timeouts |
| Hikari pool | 12 connections; connection acquisition timeout 5 seconds |
| Booking/worker SQL deadlines | 3-second lock timeout; 5-second statement timeout |

Worker lanes hold database transactions during provider calls. Keep database idle-transaction limits above those call deadlines; size the connection pool for both workers and HTTP requests.

`DATABASE_URL` defaults to `jdbc:postgresql://localhost:5432/appointments` and `DATABASE_USER` to `appointments`. Non-demo operation requires `DATABASE_PASSWORD`, `ADMIN_USERNAME`, and a Spring Security encoded `ADMIN_PASSWORD_HASH`. Supply provider URLs/tokens, SMTP and tracing settings appropriate to the deployment: `CALENDAR_URL`, `ROOM_URL`, `SMTP_HOST`, `EMAIL_FROM`, `OTLP_ENDPOINT` and the corresponding credentials/TLS options. Configure HTTPS termination, secret delivery, operator notification recipients and authorized database access. Provision the production specialty/doctor/room catalog separately; no resource-management API is included. Retention, backups and real identity provisioning belong to that environment.

Public JSON booking uses no authenticated authority and is exempt from CSRF. Other unsafe requests retain CSRF protection. Cross-origin access is not enabled. Revisit the security model when adding privileged writes or browser authentication.

## Health and graceful shutdown

[Readiness](http://localhost:8080/actuator/health/readiness) includes database health; [liveness](http://localhost:8080/actuator/health/liveness) remains independent of a database outage. Booking storage unavailability returns `503 DATABASE_UNAVAILABLE` with `Retry-After: 2`. A retry must retain the original idempotency key and payload.

On Spring context close, including SIGTERM, each worker lane stops taking jobs and allows its current attempt to record its outcome and commit. Unstarted work remains durable. The scheduler waits up to 30 seconds; Compose grants the application 45 seconds to stop. Other deployment manifests should provide equivalent grace. Forced termination relies on transaction rollback and provider idempotency for recovery.

Run `python3 scripts/load/run.py --shutdown-check --output /tmp/healthcare-shutdown` for the [shutdown probe](performance/shutdown.md).

## External provider contract

Calendar and room providers accept `PUT /reservations/{appointmentId}` with a stable operation key: `{appointmentId}:CALENDAR` or `{appointmentId}:ROOM` in `Idempotency-Key`.

```json
{"appointmentId":"UUID","resourceId":"provider-id","startsAt":"2030-01-02T10:00:00Z","endsAt":"2030-01-02T10:30:00Z"}
```

Only HTTP 200, 201 and 204 mean completion. Providers must durably deduplicate identical keys/payloads and reject changed content with 409. No patient details are sent to these providers.

HTTP network errors, 408, 429 and 5xx are retryable; other unsuccessful statuses are terminal. Retry delay is the greater of exponential backoff and `Retry-After`, capped by configuration. SMTP authentication/message errors are terminal; other mail errors receive bounded retries. Uncertain SMTP submission can duplicate a message despite its stable Message-ID. Confirmation is checked against appointment start immediately before submission.

### Failure simulation

The local stubs persist reservations in named volumes. Calendar is on port 8091 and rooms on 8092. To simulate a calendar outage and recovery:

```sh
curl http://localhost:8091/__admin/config \
  -H 'Content-Type: application/json' -d '{"status":503,"retryAfter":10}'
# Create a booking and observe its integration progress.
curl http://localhost:8091/__admin/config \
  -H 'Content-Type: application/json' -d '{"status":0}'
```

Use status 401 for permanent failure. `{"status":0,"delaySeconds":8}` commits the remote reservation before delaying the response beyond the adapter timeout; reset with `{"status":0}`. `GET /__admin/state` shows reservation counts. These unauthenticated controls are demo-only.

## Observability and alerts

| Tool / endpoint | Use |
|---|---|
| [Prometheus](http://localhost:9090) | Query metrics and inspect scrape/rule health |
| [Alertmanager](http://localhost:9093) | Inspect active alerts and notification groups |
| [Jaeger](http://localhost:16686) | Select service `appointment-service` and inspect request/job/outgoing HTTP spans |
| [Mailpit](http://localhost:8025) | Inspect patient confirmations and operator notifications |
| [Actuator metrics](http://localhost:8080/actuator/metrics) / [Prometheus export](http://localhost:8080/actuator/prometheus) | ADMIN-protected application metrics |

Prometheus authenticates with demo ADMIN credentials and scrapes every five seconds. The [scrape configuration](../dev/observability/prometheus.yml), [alert rules](../dev/observability/alerts.yml) and [notification routing](../dev/observability/alertmanager.yml) define:

| Alert | Condition |
|---|---|
| `IntegrationFailed` | `integration_jobs_failed > 0` |
| `IntegrationBacklog` | `integration_oldest_pending_seconds > 300` sustained for one minute |
| `AppointmentServiceUnavailable` | Application metrics scrape is down for 30 seconds |

Alertmanager groups by alert name, waits five seconds for initial notification and repeats unresolved alerts every four hours. It sends notifications to `operator@example.invalid` through Mailpit in the demo. Alerts are aggregated; obtain individual job IDs through logs or the database. Notifications depend on the monitoring and SMTP services being available.

W3C `traceparent` is stored with jobs, without request baggage, to continue request traces during worker execution. Jobs without a parent start new traces. Demo sampling is 100%; the non-demo default is 10%, controlled by `TRACING_SAMPLE_RATE`. Local trace storage is ephemeral.

API logs record stable error code, status, method and matched route template. Expected booking rejections use INFO, temporary operational failures WARN, and unexpected failures ERROR with up to eight application stack frames. Ordinary malformed/validation requests use DEBUG. Worker logs include job ID, operation, attempt and safe outcome codes. Trace/span IDs are included when available. Application logs exclude patient values, credentials, request keys and raw exception/provider messages.

```sh
docker compose logs --tail=100 app
```

## Diagnosis and replay

Inspect the failed job and fix the underlying provider problem before replaying:

```sh
docker compose exec -T database psql -U appointments -d appointments -c \
  "SELECT id,appointment_id,operation,attempt_count,last_error_code,next_attempt_at FROM integration_job WHERE status='FAILED' ORDER BY updated_at;"

docker compose exec -T database psql -U appointments -d appointments \
  -v job_id=REPLACE_WITH_JOB_UUID < scripts/replay-integration-job.sql
```

The [replay script](../scripts/replay-integration-job.sql) resets one failed job's attempt count and schedules it again. Successful jobs remain untouched. Email returns to `BLOCKED` until both resource jobs succeed; past-start email cannot be replayed. Zero returned rows means an ineligible or missing job. A booking remains confirmed while integrations require attention.

The demo assumes a trusted Docker operator. HTTP ADMIN access or receiving an alert does not grant database access; provision that separately for operators. A full replay audit history is outside scope.

### Manual recovery walkthrough

Start with the [README manual walkthrough](../README.md#manual-walkthrough). Use the demo's default settings and run terminal commands from the project root. In IntelliJ, use the Terminal tool window in that directory; a Markdown code block's Run action may use the documentation directory instead. Use new request keys and unused future slots for these bookings, adjusting the example date throughout if needed.

**Temporary failure and automatic recovery**

1. Make the calendar stub return a retryable error:

   ```sh
   curl http://localhost:8091/__admin/config \
     -H 'Content-Type: application/json' \
     -d '{"status":503,"retryAfter":30}'
   ```

2. In Swagger, POST a booking with a new `Idempotency-Key`:

   ```json
   {
     "patientName": "Recovery Test",
     "patientEmail": "manual-recovery@example.invalid",
     "specialtyCode": "CARDIOLOGY",
     "startsAt": "2030-01-15T11:00:00Z"
   }
   ```

   Expect **201** and `status: CONFIRMED`. Keep the appointment ID. Retrieving it while the provider is unavailable should show `integrationStatus: PENDING`, with no patient confirmation yet.

3. Immediately after observing `PENDING`, restore the provider, before the bounded retry budget is exhausted:

   ```sh
   curl http://localhost:8091/__admin/config \
     -H 'Content-Type: application/json' -d '{"status":0}'
   ```

4. Poll the same appointment after the scheduled retry, normally around 30 seconds for a short outage. Expect `COMPLETED` and one patient confirmation in Mailpit. Recovery should require no replay. If the outage was left active until retries were exhausted, use the diagnosis/replay procedure below.

**Permanent failure and operator replay**

1. Simulate a terminal provider error:

   ```sh
   curl http://localhost:8091/__admin/config \
     -H 'Content-Type: application/json' -d '{"status":401}'
   ```

2. Repeat the booking POST with a new key, changing the patient to `Operator Test`, the email to `manual-operator@example.invalid`, and the start to `2030-01-15T12:00:00Z`. Keep this appointment ID. Expect **201**, followed by `status: CONFIRMED` and `integrationStatus: ATTENTION_REQUIRED` when retrieving it after processing.

3. Restore the calendar provider with the `{"status":0}` command above. The terminal job remains failed until replayed. Allow monitoring to catch up, then inspect `IntegrationFailed` in [Prometheus](http://localhost:9090/alerts) and [Alertmanager](http://localhost:9093). Check Mailpit for the aggregated notification to `operator@example.invalid`; the patient confirmation should still be absent. Notification timing follows the grouping policy described above.

4. Diagnose the booking's jobs. Replace `REPLACE_WITH_APPOINTMENT_UUID` with the complete appointment UUID, keeping it on one line inside the SQL quotes:

   ```sh
   docker compose exec -T database psql -U appointments -d appointments -c \
     "SELECT id,operation,status,attempt_count,last_error_code FROM integration_job WHERE appointment_id='REPLACE_WITH_APPOINTMENT_UUID';"
   ```

   Expect `CALENDAR: FAILED` with `HTTP_401` and one recorded attempt, `ROOM: SUCCEEDED`, and `EMAIL: BLOCKED`. This also checks that room processing continues independently of the failed calendar job.

5. Copy the failed **calendar job ID**, not the appointment ID, into the replay command:

   ```sh
   docker compose exec -T database psql -U appointments -d appointments \
     -v job_id=REPLACE_WITH_FAILED_CALENDAR_JOB_UUID < scripts/replay-integration-job.sql
   ```

   Expect one returned row marked `READY`. Only that failed job is reset; the successful room job remains unchanged.

6. Retrieve the same appointment and rerun the diagnostic query after a few seconds. Expect `integrationStatus: COMPLETED`, all three jobs `SUCCEEDED`, and one patient confirmation for `manual-operator@example.invalid`. `IntegrationFailed` should resolve once monitoring catches up, provided no other failed jobs remain.

Restore the stub with `{"status":0}` after each scenario, including if a check fails. These controls and sample credentials are for the local demo.

## Migrations and demo catalog

Flyway applies [versioned migrations](../src/main/resources/db/migration) at startup. Preserve applied files and checksums; add schema migrations above V1005. V1004 changes worker ownership fields, so stop incompatible application instances before applying that upgrade.

The `demo` profile adds `classpath:db/demo` to Flyway's locations. Keep `V1000__demo_catalog.sql` unchanged for migration compatibility; add catalog fixtures in `R__demo_catalog.sql` with stable IDs and idempotent inserts. Flyway applies the repeatable after pending versioned migrations and reruns it when its contents change. Existing rows and local edits are preserved. Non-demo locations exclude both demo seed files.

## Reliability checks

The [reliability runner](performance/reliability.md) exercises provider stalls/outages, application termination after a remote commit, and database readiness/recovery in a disposable deployment. The [smoke check](performance/smoke.md) verifies the normal end-to-end flow. Commands and evidence for all supporting checks are indexed in [README testing](../README.md#testing).
