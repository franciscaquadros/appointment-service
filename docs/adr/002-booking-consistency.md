# ADR 002: PostgreSQL enforces booking consistency

Status: accepted.

## Context

Concurrent requests can select the same doctor or room. Clients can also retry after a timeout without knowing whether a booking committed. Application checks alone cannot guarantee consistency across instances.

## Decision

Use one transaction for request-key claiming, patient resolution, allocation, appointment creation and durable job registration. PostgreSQL exclusion constraints reject overlapping doctor, room and patient intervals. Half-open intervals `[start, end)` allow adjacent appointments.

Select active resources deterministically, locking doctor before room. Wait within bounded deadlines rather than skipping locked resources and treating contention as no capacity. Recognized conflicts/timeouts receive at most five fresh-transaction attempts with short jitter. Exhaustion returns `503`; actual unavailability returns `409`.

Identify patients by unique normalized email and reuse their stored details. Hash the canonical request payload and require it to match on replay. Resolve an existing request before applying new-booking time validation. Persist both interval boundaries so duration changes affect only new bookings.

## Consequences

Database constraints provide the final overlap guarantee across instances. Allocation locks briefly serialize requests for different slots using the same resource, a simplicity trade-off. Failed transactions roll back key claims and all booking/job writes together.

A response read can fail after the write commits. That failure never restarts the write transaction; replaying the original request recovers the persisted appointment with current progress.

[Database model](../data-model.md) · [Architecture](001-architecture.md) · [README](../../README.md)
