package pt.healthcare.appointments.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public record BookingRequest(
    @Schema(
            description =
                "Required for every booking; saved only for a new patient. Existing patient details are not overwritten.",
            example = "Ana Silva")
        @NotBlank
        @Size(max = 160)
        String patientName,
    @Schema(
            description =
                "Patient identity for this exercise: trimmed and lowercased; not verified.",
            example = "ana@example.com",
            format = "email")
        @NotBlank
        @Email
        @Size(max = 254)
        String patientEmail,
    @Schema(
            description = "Seeded specialty code; demo supports CARDIOLOGY and DERMATOLOGY.",
            example = "CARDIOLOGY")
        @NotBlank
        @Size(max = 80)
        String specialtyCode,
    @Schema(
            description =
                "Future timestamp with explicit UTC offset. Entire interval must fit 08:00–20:00 on one Europe/Lisbon date, every day. End is calculated from configured duration.",
            example = "2030-01-02T10:00:00Z",
            type = "string",
            format = "date-time")
        @NotNull
        OffsetDateTime startsAt) {
  public BookAppointmentCommand toCommand() {
    return new BookAppointmentCommand(patientName, patientEmail, specialtyCode, startsAt);
  }
}
