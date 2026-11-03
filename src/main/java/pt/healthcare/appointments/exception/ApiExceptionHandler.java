package pt.healthcare.appointments.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pt.healthcare.appointments.booking.domain.BookingException;

@RestControllerAdvice
@NullMarked
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(BookingException.class)
  ResponseEntity<ProblemDetail> bookingFailure(
      BookingException failure, HttpServletRequest request) {
    int status =
        switch (failure.code()) {
          case START_IN_PAST, OUTSIDE_WORKING_HOURS, UNKNOWN_SPECIALTY, INVALID_PAGINATION -> 400;
          case APPOINTMENT_NOT_FOUND -> 404;
          case PATIENT_ALREADY_BOOKED,
              NO_DOCTOR_AVAILABLE,
              NO_ROOM_AVAILABLE,
              IDEMPOTENCY_MISMATCH ->
              409;
          case ALLOCATION_BUSY, BOOKING_INTERRUPTED -> 503;
        };
    LOG.atLevel(status == 503 ? Level.WARN : Level.INFO)
        .log(
            "Booking request rejected code={} status={} method={} route={}",
            failure.code(),
            status,
            request.getMethod(),
            route(request));
    return problem(status, failure.code().name(), failure.getMessage(), request);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> databaseFailure(
      DataAccessException failure, HttpServletRequest request) {
    if (!(failure instanceof TransientDataAccessException)
        && !(failure instanceof DataAccessResourceFailureException)) {
      LOG.error(
          "API storage failure code=STORAGE_ERROR status=500 method={} route={} exceptionType={} applicationFrames={}",
          request.getMethod(),
          route(request),
          failure.getClass().getName(),
          applicationFrames(failure));
      return problem(500, "STORAGE_ERROR", "An unexpected storage error occurred", request);
    }
    return storageUnavailable(failure, request);
  }

  @ExceptionHandler(CannotCreateTransactionException.class)
  ResponseEntity<ProblemDetail> transactionStartFailure(
      CannotCreateTransactionException failure, HttpServletRequest request) {
    return storageUnavailable(failure, request);
  }

  private ResponseEntity<ProblemDetail> storageUnavailable(
      Exception failure, HttpServletRequest request) {
    LOG.warn(
        "API storage unavailable code=DATABASE_UNAVAILABLE status=503 method={} route={} exceptionType={}",
        request.getMethod(),
        route(request),
        failure.getClass().getName());
    return problem(
        503,
        "DATABASE_UNAVAILABLE",
        "Booking storage is temporarily unavailable; retry with the same idempotency key",
        request);
  }

  private ResponseEntity<ProblemDetail> problem(
      int status, String code, String message, HttpServletRequest request) {
    ProblemDetail detail =
        ApiProblems.create(HttpStatusCode.valueOf(status), code, message, request.getRequestURI());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      response.header(HttpHeaders.RETRY_AFTER, "2");
    }
    return response.body(detail);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      Exception failure,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    RequestError error = classifyRequestError(failure, status);
    HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
    if (status.is5xxServerError()) {
      LOG.error(
          "MVC request handling failed code={} status={} method={} route={} exceptionType={} applicationFrames={}",
          error.name(),
          status.value(),
          servletRequest.getMethod(),
          route(servletRequest),
          failure.getClass().getName(),
          applicationFrames(failure));
    } else {
      LOG.debug(
          "Request rejected code={} status={} method={} route={}",
          error.name(),
          status.value(),
          servletRequest.getMethod(),
          route(servletRequest));
    }
    String requestPath = servletRequest.getRequestURI();
    ProblemDetail problem = ApiProblems.create(status, error.name(), error.message, requestPath);

    if (failure instanceof MethodArgumentNotValidException validationFailure) {
      problem.setProperty("fieldErrors", validationErrors(validationFailure));
    }

    // Keep Spring's response headers and handling for an already committed response.
    return super.handleExceptionInternal(failure, problem, headers, status, request);
  }

  private RequestError classifyRequestError(Exception failure, HttpStatusCode status) {
    return switch (failure) {
      case MissingRequestHeaderException _ -> RequestError.MISSING_REQUEST_HEADER;
      case ServletRequestBindingException _ -> RequestError.INVALID_REQUEST;
      case TypeMismatchException _ -> RequestError.INVALID_PARAMETER;
      case HttpMessageNotReadableException _ -> RequestError.MALFORMED_REQUEST;
      case MethodArgumentNotValidException _, HandlerMethodValidationException _ ->
          RequestError.VALIDATION_FAILED;
      case HttpRequestMethodNotSupportedException _ -> RequestError.METHOD_NOT_ALLOWED;
      case HttpMediaTypeNotSupportedException _ -> RequestError.UNSUPPORTED_MEDIA_TYPE;
      case HttpMediaTypeNotAcceptableException _ -> RequestError.NOT_ACCEPTABLE;
      default -> defaultRequestError(status);
    };
  }

  private RequestError defaultRequestError(HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return RequestError.INTERNAL_ERROR;
    }
    if (status.value() == HttpStatus.NOT_FOUND.value()) {
      return RequestError.RESOURCE_NOT_FOUND;
    }
    return RequestError.INVALID_REQUEST;
  }

  private List<ValidationError> validationErrors(MethodArgumentNotValidException failure) {
    return failure.getBindingResult().getFieldErrors().stream().map(this::validationError).toList();
  }

  private ValidationError validationError(FieldError fieldError) {
    String message = fieldError.getDefaultMessage();
    if (message == null) {
      message = "Invalid value";
    }
    return new ValidationError(fieldError.getField(), message);
  }

  private record ValidationError(String field, String message) {}

  /** Each request error keeps its stable code and safe explanation together. */
  private enum RequestError {
    MISSING_REQUEST_HEADER("A required request header is missing"),
    INVALID_REQUEST("Request is invalid"),
    INVALID_PARAMETER("A request parameter or header has an invalid value"),
    MALFORMED_REQUEST("Request body is missing or malformed"),
    VALIDATION_FAILED("One or more request fields are invalid"),
    METHOD_NOT_ALLOWED("HTTP method is not supported for this resource"),
    UNSUPPORTED_MEDIA_TYPE("Request content type is not supported"),
    NOT_ACCEPTABLE("Requested response format is not supported"),
    RESOURCE_NOT_FOUND("Resource not found"),
    INTERNAL_ERROR("An unexpected error occurred");

    private final String message;

    RequestError(String message) {
      this.message = message;
    }
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpectedFailure(Exception failure, HttpServletRequest request) {
    LOG.error(
        "Unexpected API failure code=INTERNAL_ERROR status=500 method={} route={} exceptionType={} applicationFrames={}",
        request.getMethod(),
        route(request),
        failure.getClass().getName(),
        applicationFrames(failure));
    return problem(500, "INTERNAL_ERROR", "An unexpected error occurred", request);
  }

  private String route(HttpServletRequest request) {
    // The matched template has no patient IDs, query parameters or other request values.
    return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof String path
        ? path
        : "unmapped";
  }

  private List<StackTraceElement> applicationFrames(Exception failure) {
    // Raw exception messages/causes can contain SQL, patient data and provider payloads.
    return Arrays.stream(failure.getStackTrace())
        .filter(frame -> frame.getClassName().startsWith("pt.healthcare.appointments."))
        .limit(8)
        .toList();
  }
}
