package pt.healthcare.appointments.booking.api;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import pt.healthcare.appointments.exception.SecurityProblemHandler;
import tools.jackson.databind.ObjectMapper;

class BookingRateLimitFilterTest {
  private static final String ENDPOINT = "/api/v1/appointments";
  private final AtomicLong nanoTime = new AtomicLong();
  private final AtomicInteger processed = new AtomicInteger();

  private BookingRateLimitFilter filter(int limit) {
    return new BookingRateLimitFilter(
        limit,
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, ENDPOINT),
        new SecurityProblemHandler(new ObjectMapper()),
        nanoTime::get);
  }

  private MockHttpServletResponse send(BookingRateLimitFilter filter, String method, String path)
      throws Exception {
    var request = new MockHttpServletRequest(method, path);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> processed.incrementAndGet());
    return response;
  }

  @Test
  void rejectsExcessRequestsBeforeBookingProcessingUsingTheProblemContract() throws Exception {
    var filter = filter(2);
    send(filter, "POST", ENDPOINT);
    send(filter, "POST", ENDPOINT);
    var rejected = send(filter, "POST", ENDPOINT);

    assertThat(processed).hasValue(2);
    assertThat(rejected.getStatus()).isEqualTo(429);
    assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
    assertThat(rejected.getContentType()).isEqualTo("application/problem+json");
    var body = new ObjectMapper().readTree(rejected.getContentAsString());
    assertThat(body.get("type").asString()).isEqualTo("about:blank");
    assertThat(body.get("title").asString()).isEqualTo("Too Many Requests");
    assertThat(body.get("status").asInt()).isEqualTo(429);
    assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("instance").asString()).isEqualTo(ENDPOINT);
  }

  @Test
  void startsANewWindowAfterOneSecondWithoutExtendingItForRejectedRequests() throws Exception {
    var filter = filter(1);
    send(filter, "POST", ENDPOINT);
    nanoTime.set(999_999_999L);
    assertThat(send(filter, "POST", ENDPOINT).getStatus()).isEqualTo(429);
    nanoTime.set(1_000_000_000L);
    assertThat(send(filter, "POST", ENDPOINT).getStatus()).isEqualTo(200);
    assertThat(send(filter, "POST", ENDPOINT).getStatus()).isEqualTo(429);
    assertThat(processed).hasValue(2);
  }

  @Test
  void readsHealthChecksAndOtherRoutesDoNotConsumeBookingAllowance() throws Exception {
    var filter = filter(1);
    for (int i = 0; i < 10; i++) {
      assertThat(send(filter, "GET", ENDPOINT).getStatus()).isEqualTo(200);
      assertThat(send(filter, "GET", "/actuator/health").getStatus()).isEqualTo(200);
      assertThat(send(filter, "POST", "/other").getStatus()).isEqualTo(200);
    }
    assertThat(send(filter, "POST", ENDPOINT).getStatus()).isEqualTo(200);
    assertThat(send(filter, "POST", ENDPOINT).getStatus()).isEqualTo(429);
    assertThat(send(filter, "GET", ENDPOINT).getStatus()).isEqualTo(200);
  }

  @Test
  void sharesTheAllowanceAcrossConcurrentClients() throws Exception {
    var filter = filter(10);
    var requests =
        IntStream.range(0, 100)
            .mapToObj(
                client ->
                    (Callable<Integer>)
                        () -> {
                          var request = new MockHttpServletRequest("POST", ENDPOINT);
                          request.setRemoteAddr("192.0.2." + client);
                          request.addHeader("Idempotency-Key", "key-" + client);
                          var response = new MockHttpServletResponse();
                          filter.doFilter(
                              request, response, (req, res) -> processed.incrementAndGet());
                          return response.getStatus();
                        })
            .toList();
    try (var executor = Executors.newFixedThreadPool(16)) {
      var responses = executor.invokeAll(requests);
      int rejected = 0;
      for (var response : responses) {
        int status = response.get();
        assertThat(status).isIn(200, 429);
        if (status == 429) rejected++;
      }
      assertThat(rejected).isEqualTo(90);
      assertThat(processed).hasValue(10);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsNonpositiveLimits(int limit) {
    assertThatIllegalArgumentException().isThrownBy(() -> filter(limit));
  }
}
