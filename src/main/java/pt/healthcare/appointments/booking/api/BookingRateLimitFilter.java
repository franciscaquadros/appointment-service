package pt.healthcare.appointments.booking.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.LongSupplier;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import pt.healthcare.appointments.exception.SecurityProblemHandler;

/** One shared, fixed one-second booking window per application instance. */
public class BookingRateLimitFilter extends OncePerRequestFilter {
  private static final long WINDOW_NANOS = 1_000_000_000L;
  private final int requestsPerSecond;
  private final RequestMatcher bookingRequests;
  private final SecurityProblemHandler problems;
  private final LongSupplier nanoTime;
  private long windowStart;
  private int requests;

  public BookingRateLimitFilter(
      int requestsPerSecond, RequestMatcher bookingRequests, SecurityProblemHandler problems) {
    this(requestsPerSecond, bookingRequests, problems, System::nanoTime);
  }

  BookingRateLimitFilter(
      int requestsPerSecond,
      RequestMatcher bookingRequests,
      SecurityProblemHandler problems,
      LongSupplier nanoTime) {
    if (requestsPerSecond < 1)
      throw new IllegalArgumentException("Booking requests per second must be positive");
    this.requestsPerSecond = requestsPerSecond;
    this.bookingRequests = bookingRequests;
    this.problems = problems;
    this.nanoTime = nanoTime;
    this.windowStart = nanoTime.getAsLong();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !bookingRequests.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (tryAcquire()) chain.doFilter(request, response);
    else problems.rateLimited(request, response);
  }

  private synchronized boolean tryAcquire() {
    long now = nanoTime.getAsLong();
    if (now - windowStart >= WINDOW_NANOS) {
      windowStart = now;
      requests = 0;
    }
    if (requests >= requestsPerSecond) return false;
    requests++;
    return true;
  }
}
