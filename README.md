# Medical appointment service

Java 25 · Spring Boot 4.0.9 · PostgreSQL 17.6

Books medical appointments in Portugal by assigning a qualified doctor and an available room, preventing overlaps, updating external reservation systems, and emailing a confirmation. Administrators can list and inspect appointments.

## Contents

- [Overall solution and assumptions](#overall-solution-and-assumptions)
- [Database model and booking consistency](#database-model-and-booking-consistency)
- [Idempotency](#idempotency)
- [Post-booking processing](#post-booking-processing)
- [Operations](#operations)
- [Observability](#observability)
- [API documentation and Swagger](#api-documentation-and-swagger)
- [Dependency check](#dependency-check)
- [Testing](#testing)
- [Manual walkthrough](#manual-walkthrough)
- [Further documentation](#further-documentation)

## Overall solution and assumptions

One Spring Boot application contains two features: **booking** owns appointment rules, availability checks and resource allocation; **integration** owns durable jobs, background processing and external adapters. A booking transaction commits the appointment and its follow-up jobs together. External calls run asynchronously, so provider availability does not determine whether the local booking can be accepted. PostgreSQL is the source of availability.

[ADR 001: Application architecture](docs/adr/001-architecture.md) explains the structure and its trade-offs.

The exercise uses these assumptions:

- Appointment duration defaults to 30 minutes. The entire appointment must fit between 08:00 and 20:00 on one `Europe/Lisbon` calendar day, every day. Clients supply an explicit UTC offset. Holidays, leave and external busy intervals are outside scope.
- A patient is identified by trimmed, lowercased email. Identity is not verified. Existing patient details are reused without being overwritten; a name remains required on every booking request.
- Doctors can have multiple specialties. Only active doctors and rooms are allocated, in deterministic order. Patients cannot hold overlapping appointments either.
- Calendar and room providers mirror local bookings and must support durable idempotency. The demo supplies HTTP contract stubs and Mailpit instead of real providers.

Cancellation, rescheduling, patient edits, resource administration, waiting lists and alternative-slot suggestions are outside scope.

### Run

Requires Docker Compose. Java 25 is needed for local Maven builds; Python 3 is needed for the supporting smoke/load runners. Run commands from the project root, the directory containing `pom.xml` and `compose.yaml`.

```sh
docker compose up -d --build
curl --fail http://localhost:8080/actuator/health/readiness
```

Wait for readiness to report `UP` before trying the API. Compose starts the application, database, provider stubs, Mailpit, Prometheus, Alertmanager and Jaeger. Published ports bind to localhost.

Demo administrator credentials: **`demo-admin` / `demo-admin-only`**. The catalog contains two doctors, two rooms, Cardiology and Dermatology. Use synthetic patient details; demo credentials are for local use.

### Try the API

Choose a future timestamp that fits Lisbon working hours; replace the example value as needed.

```sh
REQUEST_KEY=$(uuidgen)
curl -i http://localhost:8080/api/v1/appointments \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $REQUEST_KEY" \
  -d '{"patientName":"Ana Silva","patientEmail":"ana@example.com","specialtyCode":"CARDIOLOGY","startsAt":"2030-01-02T10:00:00Z"}'

curl -u demo-admin:demo-admin-only \
  'http://localhost:8080/api/v1/appointments?limit=20'
```

The response is `201 Created` with a minimal booking receipt and a `Location` header pointing to the ADMIN-protected detail endpoint. The receipt contains only `id`, `startsAt`, `endsAt`, `status` and `integrationStatus`; it excludes patient identity, specialty, doctor and room details for every caller. Keep the request key and payload for retries. Administrators can retrieve full appointment details using the protected GET endpoints.

## Database model and booking consistency

Appointments reference a patient, doctor, specialty and room. A doctor/specialty association guarantees qualification. PostgreSQL GiST exclusion constraints prevent doctor, room and patient overlaps using half-open intervals `[start, end)`, allowing adjacent appointments. Timestamps use `timestamptz` to represent instants; business hours and email display use Lisbon time.

One transaction claims the request key, resolves the patient, allocates resources, saves the appointment and registers three jobs. Allocation locks doctor before room and retries recognized contention in fresh transactions, up to five attempts. Actual unavailability returns `409`; exhausted contention returns retryable `503`.

See the [database model and diagrams](docs/data-model.md), [booking consistency decision](docs/adr/002-booking-consistency.md), and [availability query-plan evidence](docs/performance/availability-plans.md).

## Idempotency

Every booking POST requires a client-generated UUID in `Idempotency-Key`.

- Repeating the key with the same canonical payload returns the original minimal receipt with current integration progress and HTTP `201`, including after its start time. Replays also exclude patient and resource details.
- Reusing the key with a different payload returns `409 IDEMPOTENCY_MISMATCH`.
- Concurrent requests with the same key create one appointment and one set of jobs. A failed transaction rolls back its key claim as well as its booking data.

Canonicalization trims the patient name, trims/lowercases email, and compares the requested start as an instant. The specialty code is case-sensitive. The request hash is independent of configured duration, so retries recover the persisted interval. Treat the key as a secret recovery token. After a timeout, `429` or `503`, retry with the same key and payload.

[ADR 002](docs/adr/002-booking-consistency.md) explains the transaction and replay guarantees. Providers use separate operation keys, described in the [external contract](docs/operations.md#external-provider-contract).

## Post-booking processing

Each booking creates `CALENDAR`, `ROOM` and `EMAIL` jobs. Calendar and room jobs can run independently; email waits until both succeed. Each instance has three worker lanes, with one in-flight job per operation. Full batches drain immediately; partial or empty batches are followed by the configured poll delay.

| Public integration progress | Meaning |
|---|---|
| `PENDING` | Follow-up work is incomplete |
| `COMPLETED` | All three jobs succeeded |
| `ATTENTION_REQUIRED` | At least one job failed permanently or exhausted retries |

Transient failures receive bounded retries. Terminal failures remain available for operator recovery and do not cancel the appointment or release capacity. Confirmation content includes the doctor, room, booking reference and Lisbon start/end times. Display names and patient details are read when the job runs. SMTP submission can be duplicated after an uncertain failure and does not prove mailbox receipt.

See [ADR 003: Durable post-booking jobs](docs/adr/003-post-booking-processing.md) for ordering and delivery trade-offs, and the [recovery runbook](docs/operations.md#diagnosis-and-replay) for failed jobs.

## Operations

The [operations runbook](docs/operations.md) covers local development, configuration, provider failure simulation, migrations, diagnosis and replay.

- [Liveness](http://localhost:8080/actuator/health/liveness) reports application liveness. [Readiness](http://localhost:8080/actuator/health/readiness) includes database health.
- On SIGTERM, each worker lane finishes its current job and stops taking new work. Scheduler termination wait is 30 seconds; Compose gives the application 45 seconds to stop.
- Booking POSTs share a fixed-window limit of **10 requests per second per instance**, configurable with `BOOKING_REQUESTS_PER_SECOND`. Excess requests return `429 RATE_LIMITED` and `Retry-After: 1`. Reads and health checks are exempt.
- Non-demo operation requires database/admin credentials and appropriate provider, SMTP and tracing settings. Terminate HTTPS and provision secrets and operator access in the deployment environment.

## Observability

| Local tool | Purpose |
|---|---|
| [Prometheus](http://localhost:9090) | Application/JVM/HTTP metrics, worker gauges and alert rules |
| [Alertmanager](http://localhost:9093) | Active alerts and notification grouping |
| [Jaeger](http://localhost:16686) | Booking, persisted-job and outgoing HTTP traces |
| [Mailpit](http://localhost:8025) | Captured confirmations and operator alert emails |
| [Actuator metrics](http://localhost:8080/actuator/metrics) / [Prometheus export](http://localhost:8080/actuator/prometheus) | ADMIN-protected application metrics endpoints |

Prometheus scrapes every five seconds. Alerts cover failed jobs, old pending work and an unreachable application/metrics endpoint. Alertmanager sends operator notifications to Mailpit in the demo.

Structured logs include trace/span identifiers when available. API booking rejections use INFO, temporary operational failures WARN, unexpected failures ERROR with bounded application stack frames, and ordinary request validation DEBUG. Logs omit patient values, credentials, request keys and raw exception/provider messages. Persisted `traceparent` connects the request to worker traces.

See [observability and alerts](docs/operations.md#observability-and-alerts) for metric names, thresholds, sampling and notification behavior.

## API documentation and Swagger

Open [Swagger UI](http://localhost:8080/swagger-ui.html) with the demo administrator credentials. Use **Authorize** to supply Basic authentication for protected calls. The generated [OpenAPI JSON](http://localhost:8080/v3/api-docs) and [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml) describe validation, request headers, responses and error codes.

| Endpoint | Access |
|---|---|
| `POST /api/v1/appointments` | Public; JSON and UUID `Idempotency-Key` required |
| `GET /api/v1/appointments` | ADMIN |
| `GET /api/v1/appointments/{id}` | ADMIN |
| Swagger/OpenAPI and `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` | ADMIN |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Public |

Listing returns `{ "items": [...], "nextCursor": "..." }`, ordered by start and ID. Use `limit=1..100` and optional offset-bearing `from`/`to` timestamps to select appointments starting in `[from, to)`. Pass the URL-safe `nextCursor` back as `cursor`; it preserves the filters. `nextCursor: null` ends pagination. Conflicting filters, invalid ranges and malformed cursors return `400`. Pagination does not provide snapshot isolation across requests.

```sh
curl -G -u demo-admin:demo-admin-only http://localhost:8080/api/v1/appointments \
  --data-urlencode 'limit=20' \
  --data-urlencode 'from=2030-01-01T00:00:00Z' \
  --data-urlencode 'to=2030-02-01T00:00:00Z'

curl -G -u demo-admin:demo-admin-only http://localhost:8080/api/v1/appointments \
  --data-urlencode 'cursor=RETURNED_NEXT_CURSOR'
```

For a Lisbon calendar day, use local midnight boundaries with their respective offsets, including DST transitions. Errors use Problem Details with a stable `code`: `400` invalid input, `401/403` access failure, `404` missing resource, `409` booking conflict, `429` rate limit, `503` temporary unavailability, and `500` unexpected failure. Booking `503` responses include `Retry-After: 2`.

## Dependency check

```sh
sh scripts/check-dependencies.sh
```

Requires Java 25, Docker and internet access, without an account or API key. Maven generates a resolved dependency inventory, including transitive and test dependencies; OSV-Scanner checks it for known vulnerabilities. Findings and scanner failures fail the check. Reports are written to `target/dependency-scan/` and retained as GitHub Actions artifacts.

See [dependency-check scope and reports](docs/dependency-check.md). This is separate from `./mvnw verify` so normal tests can run independently of the vulnerability service.

## Testing

```sh
./mvnw verify
```

Requires Java 25 and Docker. The verified suite contains **129 tests**, with zero failures, errors or skips. It covers policy boundaries, allocation, inactive resources, concurrency, rollback, idempotency, pagination, HTTP/security contracts, public booking/replay privacy, logging privacy, migrations, provider behavior, worker recovery and shutdown. PostgreSQL tests use isolated containers; ArchUnit checks feature/layer boundaries; Spotless checks formatting.

The supporting runners require Python 3 and Docker Compose and use disposable deployments. They build the current checkout, publish no host ports and clean up their own resources. Use separate output directories to preserve recorded evidence.

| Check | Command | Supporting report |
|---|---|---|
| End-to-end smoke | `python3 scripts/load/run.py --smoke --output /tmp/healthcare-smoke` | [Smoke checks](docs/performance/smoke.md) |
| k6 load baseline | `python3 scripts/load/run.py --output /tmp/healthcare-load` | [Workload and measurements](docs/performance/load-baseline.md) |
| Provider outages and process/database recovery | `python3 scripts/load/run.py --reliability --output /tmp/healthcare-reliability` | [Reliability evidence](docs/performance/reliability.md) |
| Graceful shutdown and restart | `python3 scripts/load/run.py --shutdown-check --output /tmp/healthcare-shutdown` | [Shutdown evidence](docs/performance/shutdown.md) |
| Availability query plans | `python3 scripts/inspect-availability-plans.py` | [Plans and comparison](docs/performance/availability-plans.md) |

The retained load run completed 300 mixed requests in 180 seconds, equivalent to **6,000 requests/hour**, with zero unexpected errors or dropped iterations. Half the requests were new bookings. The recovery run also completed all jobs after injected failures. Reports describe workload, measurements and limitations; each dataset belongs to the source/image identifiers in its raw artifacts. Re-run the checks to measure the current checkout. A short local run is not a maximum-capacity or production-uptime guarantee.

[GitHub Actions](.github/workflows/verify.yml) runs Maven verification, the Compose smoke test and the dependency scan. These checks also run locally without GitHub. Source analysis consists of compilation, formatting and architecture checks.

### Manual walkthrough

Start the demo using [Run](#run), wait for readiness, and open [Swagger](http://localhost:8080/swagger-ui.html). Sign in and use **Authorize** for protected requests with `demo-admin` / `demo-admin-only`.

Use synthetic patient details and an unused future date within Lisbon working hours. The examples use `2030-01-15`; adjust all related timestamps if choosing another date or repeating the walkthrough. Generate each new `Idempotency-Key` with `uuidgen` in a terminal, or another UUID generator.

**Create an appointment**

Call `POST /api/v1/appointments` with a new key:

```json
{
  "patientName": "Manual Test",
  "patientEmail": "manual-test@example.invalid",
  "specialtyCode": "CARDIOLOGY",
  "startsAt": "2030-01-15T10:00:00Z"
}
```

Expect **201 Created**, a minimal receipt with `status: CONFIRMED`, a `Location` header and an end time of `10:30Z`. Patient and resource details are excluded from the POST response; retrieve the assigned doctor and room through the ADMIN-protected detail endpoint. Keep the appointment ID, key and original payload. Initial `integrationStatus` may be `PENDING` or already `COMPLETED`.

**Check the main flows**

Restore the original body before each POST below. Generate a new key except where the original key is explicitly required.

| Check | Action | Expected result |
|---|---|---|
| Background processing | Call `GET /api/v1/appointments/{id}` with the returned ID; poll after a few seconds if needed | **200**, eventually `integrationStatus: COMPLETED` |
| Confirmation | Open [Mailpit](http://localhost:8025) and find the message for `manual-test@example.invalid` | One confirmation with the correct patient, doctor, room, booking reference and Lisbon start/end times |
| Idempotent replay | Repeat the original POST with the original key and unchanged payload | **201**, same appointment ID, no additional confirmation |
| Key misuse | Change the email while retaining the original key | **409** `IDEMPOTENCY_MISMATCH` |
| Patient overlap | Use a new key, the original patient and `startsAt: 2030-01-15T10:15:00Z` | **409** `PATIENT_ALREADY_BOOKED` |
| Adjacent appointment | Use a new key, the original patient and `startsAt: 2030-01-15T10:30:00Z` | **201**; adjacent appointments do not overlap |

**Follow a pagination cursor**

Call `GET /api/v1/appointments` with `limit=1`, `from=2030-01-15T00:00:00Z`, `to=2030-01-16T00:00:00Z`, and no cursor. Expect **200**, one appointment in `items` and a non-empty `nextCursor`.

Copy `nextCursor` into the next request's `cursor` parameter, keeping the same limit and filters. Expect a different appointment ID. Continue until `nextCursor` is `null`; if those are the only two appointments that day, the second page ends pagination.

**Check rejected requests**

Starting from the original body, change one field per request and use a new key:

| Change | Expected result |
|---|---|
| `specialtyCode: UNKNOWN` | **400** `UNKNOWN_SPECIALTY` |
| `patientEmail: invalid-email` | **400** validation error |
| `startsAt: 2020-01-15T10:00:00Z` | **400** `START_IN_PAST` |
| `startsAt: 2030-01-15T22:00:00Z` | **400** `OUTSIDE_WORKING_HOURS` |

These rejected requests should create neither appointments nor confirmation emails. To check access independently of Swagger's saved credentials, run:

```sh
curl -i http://localhost:8080/api/v1/appointments
curl -i -u demo-admin:wrong-password http://localhost:8080/api/v1/appointments
curl -i http://localhost:8080/actuator/health/readiness
```

Expect **401** for both listing requests and **200** with `status: UP` for readiness.

Continue with the [manual recovery walkthrough](docs/operations.md#manual-recovery-walkthrough) to exercise provider outages, alerts, diagnosis and targeted replay.

## Further documentation

| Document | Read it for |
|---|---|
| [ADR 001: Application architecture](docs/adr/001-architecture.md) | System structure and overall trade-offs |
| [ADR 002: Booking consistency](docs/adr/002-booking-consistency.md) | Transactions, allocation, constraints and idempotency |
| [ADR 003: Post-booking processing](docs/adr/003-post-booking-processing.md) | Durable jobs, ordering, retries and delivery guarantees |
| [ADR 004: Feature boundaries](docs/adr/004-feature-boundaries.md) | Ports, ownership and dependency rules |
| [Database model](docs/data-model.md) | Relationships, constraints and indexes |
| [Operations](docs/operations.md) | Configuration, observability, provider contracts and recovery |
| [Dependency check](docs/dependency-check.md) | Scanner policy, outputs and scope |
| [Smoke](docs/performance/smoke.md), [load](docs/performance/load-baseline.md), [reliability](docs/performance/reliability.md), [shutdown](docs/performance/shutdown.md), [query plans](docs/performance/availability-plans.md) | Reproduction commands, measured evidence and its limits |
