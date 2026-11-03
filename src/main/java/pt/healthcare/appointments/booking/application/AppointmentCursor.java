package pt.healthcare.appointments.booking.application;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import java.util.UUID;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;

/** Versioned transport token, not encrypted or trusted authorization data. */
record AppointmentCursor(OffsetDateTime start, UUID id, OffsetDateTime from, OffsetDateTime to) {
  String encode() {
    String value =
        String.join("|", "1", timestamp(start), id.toString(), timestamp(from), timestamp(to));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static AppointmentCursor decode(String token) {
    try {
      if (token.length() > 1024 || !token.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      String[] fields =
          new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
      if (fields.length != 5 || !fields[0].equals("1") || fields[1].isEmpty())
        throw new IllegalArgumentException();
      return new AppointmentCursor(
          parse(fields[1]), UUID.fromString(fields[2]), parse(fields[3]), parse(fields[4]));
    } catch (IllegalArgumentException | DateTimeException _) {
      throw new BookingException(BookingErrorCode.INVALID_PAGINATION, "Invalid listing cursor");
    }
  }

  private static String timestamp(OffsetDateTime value) {
    return value == null ? "" : value.toInstant().toString();
  }

  private static OffsetDateTime parse(String value) {
    return value.isEmpty() ? null : OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
  }
}
