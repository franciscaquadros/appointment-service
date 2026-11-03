package pt.healthcare.appointments.booking.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

public record BookAppointmentCommand(
    String patientName, String patientEmail, String specialtyCode, OffsetDateTime startsAt) {
  public String normalizedEmail() {
    return patientEmail.strip().toLowerCase(Locale.ROOT);
  }
}
