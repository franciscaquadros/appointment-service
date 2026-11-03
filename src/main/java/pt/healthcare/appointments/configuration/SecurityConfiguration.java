package pt.healthcare.appointments.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import pt.healthcare.appointments.booking.api.BookingRateLimitFilter;
import pt.healthcare.appointments.exception.SecurityProblemHandler;

@Configuration
public class SecurityConfiguration {
  @Bean
  UserDetailsService administrators(
      @Value("${admin.username}") String username,
      @Value("${admin.password-hash}") String passwordHash) {
    if (username.isBlank() || passwordHash.isBlank())
      throw new IllegalArgumentException("Admin configuration is required");
    return new InMemoryUserDetailsManager(
        User.withUsername(username).password(passwordHash).roles("ADMIN").build());
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      SecurityProblemHandler problemHandler,
      @Value("${appointments.booking-requests-per-second}") int bookingRequestsPerSecond) {
    // Public booking does not act with an authenticated user's authority.
    // Keep CSRF protection for all other unsafe requests, including future admin operations.
    var publicBooking =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/appointments");
    return http.csrf(csrf -> csrf.ignoringRequestMatchers(publicBooking))
        .addFilterBefore(
            new BookingRateLimitFilter(bookingRequestsPerSecond, publicBooking, problemHandler),
            BasicAuthenticationFilter.class)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            access ->
                access
                    .requestMatchers(HttpMethod.POST, "/api/v1/appointments")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
        .httpBasic(basic -> basic.authenticationEntryPoint(problemHandler))
        .build();
  }
}
