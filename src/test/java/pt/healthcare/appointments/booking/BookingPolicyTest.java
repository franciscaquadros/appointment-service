package pt.healthcare.appointments.booking;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.booking.domain.BookingPolicy;

class BookingPolicyTest {
  private final BookingPolicy policy =
      new BookingPolicy(
          Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
          Duration.ofMinutes(30),
          "08:00",
          "20:00");

  @Test
  void allowsOpeningAndClosingBoundariesInWinterAndSummer() {
    for (String date : new String[] {"2030-01-02", "2030-07-02"}) {
      for (LocalTime time : new LocalTime[] {LocalTime.of(8, 0), LocalTime.of(19, 30)}) {
        Instant start =
            LocalDate.parse(date).atTime(time).atZone(BookingPolicy.APPOINTMENT_ZONE).toInstant();
        assertThat(policy.validateAndCalculateEnd(start)).isEqualTo(start.plusSeconds(1800));
      }
    }
  }

  @Test
  void rejectsIntervalEndingAfterClosing() {
    assertThatThrownBy(() -> policy.validateAndCalculateEnd(Instant.parse("2030-01-02T19:45:00Z")))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(BookingErrorCode.OUTSIDE_WORKING_HOURS));
  }

  @Test
  void validatesLisbonTimeRatherThanClientOffset() {
    Instant start = OffsetDateTime.parse("2030-07-02T08:00:00+03:00").toInstant();
    assertThatThrownBy(() -> policy.validateAndCalculateEnd(start))
        .isInstanceOf(BookingException.class);
  }

  @Test
  void rejectsPastStartAndInvalidConfiguration() {
    assertThatThrownBy(() -> policy.validateAndCalculateEnd(Instant.parse("2029-01-01T10:00:00Z")))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> new BookingPolicy(Clock.systemUTC(), Duration.ZERO, "08:00", "20:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
