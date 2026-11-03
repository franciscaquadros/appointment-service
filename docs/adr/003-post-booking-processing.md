# ADR 003: Durable jobs with transaction-scoped locking

Status: accepted.

## Context

External providers can fail or accept a request whose response is lost. Post-booking work must survive restarts, remain recoverable and avoid blocking the booking HTTP request.

## Decision

Register `CALENDAR`, `ROOM` and `EMAIL` jobs inside the booking transaction. Confirm the local booking after commit. Email starts blocked until both resource jobs succeed.

Each attempt locks one ready job with `FOR UPDATE SKIP LOCKED`, calls the provider, records the result and commits. Rollback or connection loss releases the lock. Three independent sequential lanes per instance isolate the operation types; different instances can claim different jobs. Full batches drain immediately. Email prerequisites are swept once per email batch.

Retry eligible failures with bounded exponential delay and a default limit of five recorded attempts. Respect `Retry-After` within the configured cap and calculate the delay from failure recording. Retain terminal failures for targeted SQL recovery. On context close, stop taking jobs and allow active attempts to finish within the scheduler/container grace periods.

| Concept | Values/rule |
|---|---|
| Appointment | `CONFIRMED`; integration failure does not release capacity |
| Job | `BLOCKED`, `READY`, `SUCCEEDED`, `FAILED` |
| Public progress | Any failed job: `ATTENTION_REQUIRED`; all three succeeded: `COMPLETED`; otherwise: `PENDING` |

`ConfirmationEmailComposer` produces Portuguese subject/body content with Lisbon start/end times. The SMTP adapter owns MIME construction, recipients, the final deadline check, transport and error classification. Doctor/room names and patient details are read for each attempt.

## Consequences

Each lane holds one database connection during its external call, up to three per instance. Transaction-scoped ownership avoids a lease/recovery protocol but ties capacity to provider latency and the connection pool. A crashed transaction does not durably increment its attempt counter; the retry limit bounds recorded attempts, not calls across repeated crashes.

Remote success followed by local rollback can repeat a call. Calendar/room providers must durably deduplicate stable operation keys.

SMTP does not provide an idempotency guarantee. If a provider accepts an email but the application cannot record success, retrying may submit it again. We accept this risk to support recovery from temporary failures, using a stable Message-ID for identification without assuming recipient deduplication.

Confirmation is suppressed once the appointment starts; submission success does not prove receipt. Failed integrations preserve the booking for operator recovery. A full attempt/replay audit history and booking-time display-name snapshots are outside scope.

[Provider contract and recovery](../operations.md) · [Architecture](001-architecture.md) · [README](../../README.md)
