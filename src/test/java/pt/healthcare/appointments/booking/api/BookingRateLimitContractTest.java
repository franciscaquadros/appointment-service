package pt.healthcare.appointments.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.configuration.SecurityConfiguration;
import pt.healthcare.appointments.exception.SecurityProblemHandler;

@WebMvcTest(
    controllers = AppointmentController.class,
    properties = "appointments.booking-requests-per-second=1")
@Import({SecurityConfiguration.class, SecurityProblemHandler.class})
@ActiveProfiles("demo")
class BookingRateLimitContractTest {
  @Autowired MockMvc mvc;
  @MockitoBean BookingService bookings;

  @Test
  void securityChainRejectsExcessPostsBeforeCallingBookingServiceAndStillAllowsReads()
      throws Exception {
    when(bookings.book(any(), any()))
        .thenThrow(
            new BookingException(BookingErrorCode.NO_DOCTOR_AVAILABLE, "No doctor available"));
    int admitted = 0;
    int rejected = 0;
    for (int i = 0; i < 12; i++) {
      var response =
          mvc.perform(
                  post("/api/v1/appointments")
                      .header("Idempotency-Key", UUID.randomUUID())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {"patientName":"Ana Silva","patientEmail":"ana@example.com",
                           "specialtyCode":"CARDIOLOGY","startsAt":"2030-01-02T10:00:00Z"}
                          """))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isIn(409, 429);
      if (response.getStatus() == 429) {
        rejected++;
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
      } else admitted++;
    }
    assertThat(rejected).isPositive();
    assertThat(admitted).isPositive();
    verify(bookings, times(admitted)).book(any(), any());
    mvc.perform(get("/api/v1/appointments").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
    verify(bookings).listAppointments(20, null, null, null);
    verifyNoMoreInteractions(bookings);
  }
}
