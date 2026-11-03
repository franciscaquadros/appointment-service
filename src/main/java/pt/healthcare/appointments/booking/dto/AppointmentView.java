package pt.healthcare.appointments.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(
    requiredProperties = {
      "id",
      "patientName",
      "patientEmail",
      "specialty",
      "doctor",
      "room",
      "startsAt",
      "endsAt",
      "status",
      "integrationStatus"
    })
public record AppointmentView(
    UUID id,
    String patientName,
    String patientEmail,
    String specialty,
    String doctor,
    String room,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    @Schema(
            allowableValues = {"CONFIRMED"},
            description = "Remains confirmed even if integration fails.")
        String status,
    @Schema(
            allowableValues = {"PENDING", "COMPLETED", "ATTENTION_REQUIRED"},
            description = "Derived from the three post-booking jobs.")
        String integrationStatus) {
  public static AppointmentView from(AppointmentDetails appointment, String integrationStatus) {
    return new AppointmentView(
        appointment.id(),
        appointment.patientName(),
        appointment.patientEmail(),
        appointment.specialty(),
        appointment.doctor(),
        appointment.room(),
        appointment.startsAt(),
        appointment.endsAt(),
        appointment.status(),
        integrationStatus);
  }
}
