package pt.healthcare.appointments.booking.api;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.configuration.SecurityConfiguration;
import pt.healthcare.appointments.exception.SecurityProblemHandler;

@WebMvcTest(
    controllers = AppointmentController.class,
    properties = "appointments.booking-requests-per-second=1000")
@Import({SecurityConfiguration.class, SecurityProblemHandler.class})
@ActiveProfiles("demo")
class ApiErrorContractTest {
  private static final String ENDPOINT = "/api/v1/appointments";
  private static final String VALID_BODY =
      """
      {"patientName":"Ana Silva","patientEmail":"ana@example.com",
       "specialtyCode":"CARDIOLOGY","startsAt":"2030-01-02T10:00:00Z"}
      """;
  @Autowired MockMvc mvc;
  @MockitoBean BookingService bookings;

  static Stream<RuntimeException> unavailableStorage() {
    return Stream.of(
        new CannotCreateTransactionException("private-database-details"),
        new DataAccessResourceFailureException("private-database-details"),
        new TransientDataAccessResourceException("private-database-details"));
  }

  @ParameterizedTest
  @MethodSource("unavailableStorage")
  void unavailableStorageReturnsRetryableProblemWithoutLeakingDetails(RuntimeException failure)
      throws Exception {
    when(bookings.book(any(), any())).thenThrow(failure);
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)),
            503,
            "DATABASE_UNAVAILABLE")
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(content().string(not(containsString("private-database-details"))));
  }

  @Test
  void storageProgrammingFailureIsNotReportedAsTemporary() throws Exception {
    when(bookings.book(any(), any()))
        .thenThrow(new InvalidDataAccessResourceUsageException("private-database-details"));
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)),
            500,
            "STORAGE_ERROR")
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(content().string(not(containsString("private-database-details"))));
  }

  @Test
  void missingHeaderHasStableCode() throws Exception {
    assertProblem(
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
        400,
        "MISSING_REQUEST_HEADER");
  }

  @Test
  void invalidHeaderHasStableCodeAndDoesNotEchoItsValue() throws Exception {
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", "private-invalid-value")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)),
            400,
            "INVALID_PARAMETER")
        .andExpect(content().string(not(containsString("private-invalid-value"))));
  }

  @Test
  void malformedJsonDoesNotExposeInput() throws Exception {
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"patientEmail\":\"private@example.com\",")),
            400,
            "MALFORMED_REQUEST")
        .andExpect(content().string(not(containsString("private@example.com"))));
  }

  @Test
  void validationIncludesFieldDetailsWithoutRejectedValues() throws Exception {
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY.replace("ana@example.com", "private-invalid-email"))),
            400,
            "VALIDATION_FAILED")
        .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("patientEmail")))
        .andExpect(jsonPath("$.fieldErrors[0].message").isString())
        .andExpect(content().string(not(containsString("private-invalid-email"))));
  }

  @Test
  void anonymousAccessPreservesBasicChallenge() throws Exception {
    assertProblem(mvc.perform(get(ENDPOINT)), 401, "AUTHENTICATION_REQUIRED")
        .andExpect(header().string("WWW-Authenticate", startsWith("Basic ")));
  }

  @Test
  void incorrectCredentialsUseSameContract() throws Exception {
    assertProblem(
            mvc.perform(get(ENDPOINT).with(httpBasic("demo-admin", "wrong-password"))),
            401,
            "AUTHENTICATION_REQUIRED")
        .andExpect(header().exists("WWW-Authenticate"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void insufficientRoleReturnsForbiddenProblem() throws Exception {
    assertProblem(mvc.perform(get(ENDPOINT)), 403, "ACCESS_DENIED");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void invalidPaginationParameterUsesMvcContract() throws Exception {
    assertProblem(
        mvc.perform(get(ENDPOINT).param("limit", "not-a-number")), 400, "INVALID_PARAMETER");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void methodNotAllowedPreservesAllowHeader() throws Exception {
    assertProblem(mvc.perform(delete(ENDPOINT).with(csrf())), 405, "METHOD_NOT_ALLOWED")
        .andExpect(header().string("Allow", containsString("GET")));
  }

  @ParameterizedTest
  @CsvSource({
    "START_IN_PAST,400", "OUTSIDE_WORKING_HOURS,400", "UNKNOWN_SPECIALTY,400",
    "INVALID_PAGINATION,400", "APPOINTMENT_NOT_FOUND,404", "PATIENT_ALREADY_BOOKED,409",
    "NO_DOCTOR_AVAILABLE,409", "NO_ROOM_AVAILABLE,409", "IDEMPOTENCY_MISMATCH,409",
    "ALLOCATION_BUSY,503", "BOOKING_INTERRUPTED,503"
  })
  void applicationErrorsRetainCodesAndRetryHeader(BookingErrorCode code, int status)
      throws Exception {
    when(bookings.book(any(), any())).thenThrow(new BookingException(code, "Safe booking failure"));
    var result =
        assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)),
            status,
            code.name());
    if (status == 503) result.andExpect(header().string("Retry-After", "2"));
    else result.andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  void unexpectedErrorsDoNotExposeExceptionMessage() throws Exception {
    when(bookings.book(any(), any()))
        .thenThrow(new IllegalStateException("private implementation details"));
    assertProblem(
            mvc.perform(
                post(ENDPOINT)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)),
            500,
            "INTERNAL_ERROR")
        .andExpect(content().string(not(containsString("private implementation details"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void nonExemptWritesRequireCsrfToken() throws Exception {
    assertProblem(mvc.perform(delete(ENDPOINT)), 403, "ACCESS_DENIED");
  }

  @Test
  void publicBookingRejectsBrowserSimpleContentTypes() throws Exception {
    for (MediaType contentType :
        new MediaType[] {
          MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA
        }) {
      assertProblem(
          mvc.perform(
              post(ENDPOINT)
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType(contentType)
                  .content(VALID_BODY)),
          415,
          "UNSUPPORTED_MEDIA_TYPE");
    }
  }

  private ResultActions assertProblem(ResultActions response, int status, String code)
      throws Exception {
    return response
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").isString())
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.detail").isString())
        .andExpect(jsonPath("$.instance").value(ENDPOINT))
        .andExpect(jsonPath("$.code").value(code));
  }
}
