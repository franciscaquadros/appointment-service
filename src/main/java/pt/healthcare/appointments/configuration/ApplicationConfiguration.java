package pt.healthcare.appointments.configuration;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.healthcare.appointments.booking.domain.BookingPolicy;

@Configuration
public class ApplicationConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  BookingPolicy bookingPolicy(
      Clock clock,
      @Value("${appointments.duration}") Duration duration,
      @Value("${appointments.opening-time}") String openingTime,
      @Value("${appointments.closing-time}") String closingTime) {
    return new BookingPolicy(clock, duration, openingTime, closingTime);
  }
}
