package pt.healthcare.appointments.booking.domain;

import java.time.*;

public class BookingPolicy {
  public static final ZoneId APPOINTMENT_ZONE = ZoneId.of("Europe/Lisbon");
  private final Clock clock;
  private final Duration duration;
  private final LocalTime openingTime;
  private final LocalTime closingTime;

  public BookingPolicy(Clock clock, Duration duration, String openingTime, String closingTime) {
    this.clock = clock;
    this.duration = duration;
    this.openingTime = LocalTime.parse(openingTime);
    this.closingTime = LocalTime.parse(closingTime);
    if (duration.isNegative()
        || duration.isZero()
        || !this.openingTime.isBefore(this.closingTime)
        || duration.compareTo(Duration.between(this.openingTime, this.closingTime)) > 0) {
      throw new IllegalArgumentException("Appointment duration and working hours are incompatible");
    }
  }

  public Instant validateAndCalculateEnd(Instant startsAt) {
    if (!startsAt.isAfter(clock.instant())) {
      throw new BookingException(
          BookingErrorCode.START_IN_PAST, "Appointment start must be in the future");
    }
    Instant endsAt = startsAt.plus(duration);
    ZonedDateTime localStart = startsAt.atZone(APPOINTMENT_ZONE);
    ZonedDateTime localEnd = endsAt.atZone(APPOINTMENT_ZONE);
    if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
        || localStart.toLocalTime().isBefore(openingTime)
        || localEnd.toLocalTime().isAfter(closingTime)) {
      throw new BookingException(
          BookingErrorCode.OUTSIDE_WORKING_HOURS,
          "Appointment must fit within Lisbon working hours");
    }
    return endsAt;
  }
}
