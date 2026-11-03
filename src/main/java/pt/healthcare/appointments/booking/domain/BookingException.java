package pt.healthcare.appointments.booking.domain;

import java.util.Objects;

/** Application failure; HTTP mapping belongs to the API adapter. */
public class BookingException extends RuntimeException {
  private final BookingErrorCode code;

  public BookingException(BookingErrorCode code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public BookingErrorCode code() {
    return code;
  }
}
