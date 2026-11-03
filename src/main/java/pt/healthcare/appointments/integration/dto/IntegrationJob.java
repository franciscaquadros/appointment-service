package pt.healthcare.appointments.integration.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;

/** A locked job and the booking data needed for the next external attempt. */
public record IntegrationJob(
    UUID id,
    UUID appointmentId,
    IntegrationOperation operation,
    int attemptCount,
    String traceparent,
    String doctorExternalId,
    String roomExternalId,
    String doctorName,
    String roomCode,
    String patientEmail,
    String patientName,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt) {
  public String operationKey() {
    return appointmentId + ":" + operation.name();
  }
}
