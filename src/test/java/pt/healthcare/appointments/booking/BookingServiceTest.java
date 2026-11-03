package pt.healthcare.appointments.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionTemplate;
import pt.healthcare.appointments.booking.application.AppointmentProgressQuery;
import pt.healthcare.appointments.booking.application.AppointmentRepository;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.application.PostBookingActions;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.booking.domain.BookingPolicy;
import pt.healthcare.appointments.booking.dto.AppointmentDetails;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;

class BookingServiceTest {
  private final AppointmentRepository repository = mock(AppointmentRepository.class);
  private final PostBookingActions postBookingActions = mock(PostBookingActions.class);
  private final AppointmentProgressQuery progressQuery = mock(AppointmentProgressQuery.class);
  private final BookingService service =
      new BookingService(
          repository,
          postBookingActions,
          progressQuery,
          mock(TransactionTemplate.class),
          mock(BookingPolicy.class));

  @Test
  void rejectsInvalidPaginationBeforeQueryingStorage() {
    assertThatThrownBy(() -> service.listAppointments(101, null, null, null))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.code()).isEqualTo(BookingErrorCode.INVALID_PAGINATION));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMalformedCursorsAndInvalidDateRangesBeforeStorage() {
    var from = OffsetDateTime.parse("2030-01-01T10:00:00Z");
    for (String token : List.of("", "@@", "eA", "x".repeat(1025))) {
      assertThatThrownBy(() -> service.listAppointments(20, token, null, null))
          .isInstanceOfSatisfying(
              BookingException.class,
              failure -> assertThat(failure.code()).isEqualTo(BookingErrorCode.INVALID_PAGINATION));
    }
    assertThatThrownBy(() -> service.listAppointments(20, null, from, from))
        .isInstanceOf(BookingException.class);
    assertThatThrownBy(() -> service.listAppointments(20, null, from, from.minusMinutes(1)))
        .isInstanceOf(BookingException.class);
    verifyNoInteractions(repository, progressQuery);
  }

  @Test
  void missingAppointmentIsAnApplicationFailure() {
    UUID appointmentId = UUID.randomUUID();
    when(repository.find(appointmentId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.findAppointment(appointmentId))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(BookingErrorCode.APPOINTMENT_NOT_FOUND));
  }

  @Test
  void repeatedTimeoutsStopAtTheRetryLimit() {
    var transaction = mock(TransactionTemplate.class);
    when(transaction.execute(any())).thenThrow(new QueryTimeoutException("Timeout"));
    var serviceWithTimeouts =
        new BookingService(
            repository, postBookingActions, progressQuery, transaction, mock(BookingPolicy.class));
    var command =
        new BookAppointmentCommand(
            "Ana Silva", "ana@example.com", "CARDIOLOGY", OffsetDateTime.now().plusDays(1));
    assertThatThrownBy(() -> serviceWithTimeouts.book(UUID.randomUUID(), command))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.code()).isEqualTo(BookingErrorCode.ALLOCATION_BUSY));
    verify(transaction, times(5)).execute(any());
    verifyNoInteractions(repository);
  }

  @Test
  void readTimeoutAfterCommitDoesNotRetryBooking() {
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    UUID appointmentId = UUID.randomUUID();
    when(transaction.execute(any())).thenReturn(appointmentId);
    when(repository.find(appointmentId)).thenThrow(new QueryTimeoutException("Read timed out"));
    BookingService bookingService =
        new BookingService(
            repository, postBookingActions, progressQuery, transaction, mock(BookingPolicy.class));
    BookAppointmentCommand command =
        new BookAppointmentCommand(
            "Ana Silva", "ana@example.com", "CARDIOLOGY", OffsetDateTime.now().plusDays(1));
    assertThatThrownBy(() -> bookingService.book(UUID.randomUUID(), command))
        .isInstanceOf(QueryTimeoutException.class);
    verify(transaction).execute(any());
    verify(repository).find(appointmentId);
  }

  @Test
  void listingCombinesAppointmentDetailsWithOneBatchProgressLookup() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.now().plusDays(1);
    var first =
        new AppointmentDetails(
            firstId,
            "Ana",
            "ana@example.com",
            "CARDIOLOGY",
            "Doctor",
            "Room",
            start,
            start.plusMinutes(30),
            "CONFIRMED");
    var second =
        new AppointmentDetails(
            secondId,
            "Bruno",
            "bruno@example.com",
            "CARDIOLOGY",
            "Doctor",
            "Room",
            start.plusHours(1),
            start.plusHours(1).plusMinutes(30),
            "CONFIRMED");
    when(repository.list(21, null, null, null, null)).thenReturn(List.of(first, second));
    when(progressQuery.findProgress(Set.of(firstId, secondId)))
        .thenReturn(Map.of(firstId, "COMPLETED", secondId, "ATTENTION_REQUIRED"));
    var appointments = service.listAppointments(20, null, null, null).items();
    assertThat(appointments)
        .extracting(view -> view.integrationStatus())
        .containsExactly("COMPLETED", "ATTENTION_REQUIRED");
    assertThat(appointments).extracting(view -> view.patientName()).containsExactly("Ana", "Bruno");
    verify(progressQuery).findProgress(Set.of(firstId, secondId));
    verifyNoMoreInteractions(progressQuery);
  }

  @Test
  void emptyPageDoesNotQueryProgress() {
    when(repository.list(21, null, null, null, null)).thenReturn(List.of());
    assertThat(service.listAppointments(20, null, null, null).items()).isEmpty();
    verifyNoInteractions(progressQuery);
  }

  @Test
  void missingFollowUpWorkIsPendingRatherThanCompleted() {
    UUID id = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.now().plusDays(1);
    var appointment =
        new AppointmentDetails(
            id,
            "Ana",
            "ana@example.com",
            "CARDIOLOGY",
            "Doctor",
            "Room",
            start,
            start.plusMinutes(30),
            "CONFIRMED");
    when(repository.find(id)).thenReturn(Optional.of(appointment));
    when(progressQuery.findProgress(Set.of(id))).thenReturn(Map.of());
    assertThat(service.findAppointment(id).integrationStatus()).isEqualTo("PENDING");
  }
}
