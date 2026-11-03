package pt.healthcare.appointments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;

class ApiExceptionHandlerLoggingTest {
  private static final String PRIVATE_DATA = "private-patient@example.invalid";
  private static final String ROUTE = "/api/v1/appointments/{id}";
  private final ApiExceptionHandler handler = new ApiExceptionHandler();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void captureLogs() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void restoreLogger() {
    logger.detachAppender(logs);
    logs.stop();
    logger.setLevel(previousLevel);
  }

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/api/v1/appointments/" + PRIVATE_DATA);
    request.setQueryString("email=" + PRIVATE_DATA);
    request.addHeader("Authorization", "Basic private-credentials");
    request.addHeader("Idempotency-Key", "private-request-key");
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE);
    return request;
  }

  private ILoggingEvent onlyEvent(Level level) {
    assertThat(logs.list).hasSize(1);
    var event = logs.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(level);
    assertThat(event.getThrowableProxy()).isNull();
    assertThat(event.getFormattedMessage())
        .contains("method=GET")
        .doesNotContain(PRIVATE_DATA, "private-credentials", "private-request-key");
    return event;
  }

  @ParameterizedTest
  @CsvSource({
    "UNKNOWN_SPECIALTY,400,INFO", "NO_DOCTOR_AVAILABLE,409,INFO",
    "ALLOCATION_BUSY,503,WARN", "BOOKING_INTERRUPTED,503,WARN"
  })
  void bookingRejectionIsLoggedOnceWithSafeContext(
      BookingErrorCode code, int status, String level) {
    var response = handler.bookingFailure(new BookingException(code, PRIVATE_DATA), request());
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(onlyEvent(Level.toLevel(level)).getFormattedMessage())
        .contains("code=" + code, "status=" + status, "route=" + ROUTE);
  }

  @ParameterizedTest
  @CsvSource({"true,WARN", "false,ERROR"})
  void storageFailuresLogSafeClassification(boolean temporary, String level) {
    var failure =
        temporary
            ? new DataAccessResourceFailureException("SQL containing " + PRIVATE_DATA)
            : new InvalidDataAccessResourceUsageException("SQL containing " + PRIVATE_DATA);
    handler.databaseFailure(failure, request());
    assertThat(onlyEvent(Level.toLevel(level)).getFormattedMessage())
        .contains(
            "code=" + (temporary ? "DATABASE_UNAVAILABLE" : "STORAGE_ERROR"), "route=" + ROUTE)
        .doesNotContain("SQL containing");
  }

  @Test
  void unexpectedFailureLogsApplicationFramesWithoutExceptionMessagesOrCauses() {
    var failure = new IllegalStateException(PRIVATE_DATA, new RuntimeException("private-cause"));
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "pt.healthcare.appointments.booking.application.BookingService",
              "book",
              "BookingService.java",
              50),
          new StackTraceElement("org.example.Framework", "invoke", "Framework.java", 10)
        });
    handler.unexpectedFailure(failure, request());
    assertThat(onlyEvent(Level.ERROR).getFormattedMessage())
        .contains(
            "code=INTERNAL_ERROR",
            "status=500",
            "route=" + ROUTE,
            "exceptionType=java.lang.IllegalStateException",
            "BookingService.book(BookingService.java:50)")
        .doesNotContain("private-cause", "org.example.Framework");
  }

  @Test
  void malformedInputUsesDebugAndDoesNotFallBackToRawRequestUri() {
    var request = request();
    request.removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    handler.handleExceptionInternal(
        new HttpMessageNotReadableException(PRIVATE_DATA, new MockHttpInputMessage(new byte[0])),
        null,
        new HttpHeaders(),
        HttpStatus.BAD_REQUEST,
        new ServletWebRequest(request));
    assertThat(onlyEvent(Level.DEBUG).getFormattedMessage())
        .contains("code=MALFORMED_REQUEST", "status=400", "route=unmapped");
  }
}
