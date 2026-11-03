package pt.healthcare.appointments.integration.domain;

/** Names are persisted and form part of provider idempotency keys. */
public enum IntegrationOperation {
  CALENDAR,
  ROOM,
  EMAIL
}
