# ADR 004: Booking owns ports; integration implements them

Status: accepted.

## Context

Booking needs atomic follow-up registration and integration progress, while external delivery belongs to a separate feature. Direct dependencies on integration persistence would blur ownership and make changes harder to isolate.

## Decision

Booking owns appointments, patient resolution, qualification and allocation. Integration owns durable jobs, progress derivation, workers and HTTP/SMTP adapters.

Booking declares `PostBookingActions` and `AppointmentProgressQuery`; integration implements them. Registration joins the booking transaction, with `Propagation.MANDATORY` rejecting calls outside it. Progress lookup is batched for the appointments in a page. Booking persistence does not access the job table. Integration reads booking tables and writes its own tables.

Application services depend on ports rather than adapters. `BookingService` combines `AppointmentDetails` and progress into `AppointmentView`. The controller maps public POST responses, including replay, to a separate `BookingReceipt` containing only ID, times, status and progress. Full patient and resource details are exposed only by ADMIN-protected GET endpoints. DTOs remain feature-local. Booking/domain exceptions stay with their feature; shared HTTP error handling, configuration and observability live in support packages.

Use `BookingErrorCode` and `IntegrationOperation` across their respective flows, converting names at SQL, tracing and response boundaries. Exhaustive switches force dispatch/mapping decisions when enum values are added. Integration error codes also represent dynamic provider responses such as `HTTP_429`.

## Consequences

Two ports provide the needed separation without a module framework or event bus. ArchUnit enforces feature/layer dependency rules. SQL ownership still requires repository review and transaction tests because class-dependency checks cannot inspect table access.

[Architecture](001-architecture.md) · [Database model](../data-model.md) · [README](../../README.md)
