package pt.healthcare.appointments.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public confirmation of a booking; persisted patient and resource details require ADMIN access.
 */
@Schema(requiredProperties = {"id", "startsAt", "endsAt", "status", "integrationStatus"})
public record BookingReceipt(
    UUID id,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    @Schema(allowableValues = {"CONFIRMED"}) String status,
    @Schema(allowableValues = {"PENDING", "COMPLETED", "ATTENTION_REQUIRED"})
        String integrationStatus) {

  public static BookingReceipt from(AppointmentView appointment) {
    return new BookingReceipt(
        appointment.id(),
        appointment.startsAt(),
        appointment.endsAt(),
        appointment.status(),
        appointment.integrationStatus());
  }
}
