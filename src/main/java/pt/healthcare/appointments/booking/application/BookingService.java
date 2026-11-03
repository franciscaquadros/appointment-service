package pt.healthcare.appointments.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.booking.domain.BookingPolicy;
import pt.healthcare.appointments.booking.dto.AppointmentDetails;
import pt.healthcare.appointments.booking.dto.AppointmentPage;
import pt.healthcare.appointments.booking.dto.AppointmentView;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;

@Service
public class BookingService {
  private static final Logger LOG = LoggerFactory.getLogger(BookingService.class);
  private static final int MAX_ALLOCATION_ATTEMPTS = 5;
  private static final SecureRandom RETRY_JITTER = new SecureRandom();
  private final TransactionTemplate transaction;
  private final BookingPolicy policy;
  private final AppointmentRepository appointmentRepository;
  private final PostBookingActions postBookingActions;
  private final AppointmentProgressQuery progressQuery;

  public BookingService(
      AppointmentRepository appointmentRepository,
      PostBookingActions postBookingActions,
      AppointmentProgressQuery progressQuery,
      TransactionTemplate transaction,
      BookingPolicy policy) {
    this.postBookingActions = postBookingActions;
    this.progressQuery = progressQuery;
    this.transaction = transaction;
    this.policy = policy;
    this.appointmentRepository = appointmentRepository;
  }

  public AppointmentView book(UUID requestKey, BookAppointmentCommand command) {
    String requestHash = hash(command);
    for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
      UUID appointmentId;
      try {
        appointmentId = transaction.execute(_ -> createOrReplay(requestKey, requestHash, command));
      } catch (AllocationConflictException
          | ConcurrencyFailureException
          | QueryTimeoutException _) {
        // Constraint checks can time out while waiting for competing inserts.
        // TransactionTemplate has rolled back before the next bounded attempt starts.
        if (attempt + 1 < MAX_ALLOCATION_ATTEMPTS) {
          pauseBeforeRetry();
        }
        continue;
      }
      // Includes idempotent replay; log only after the transaction commits, even if the read fails.
      UUID committedAppointmentId = Objects.requireNonNull(appointmentId);
      LOG.info(
          "Booking transaction committed appointmentId={} attempts={}",
          committedAppointmentId,
          attempt + 1);
      // A read failure after commit must not restart the booking transaction.
      return findAppointment(committedAppointmentId);
    }
    throw new BookingException(
        BookingErrorCode.ALLOCATION_BUSY,
        "Booking is temporarily busy; retry with the same idempotency key");
  }

  public AppointmentView findAppointment(UUID appointmentId) {
    AppointmentDetails appointment =
        appointmentRepository
            .find(appointmentId)
            .orElseThrow(
                () ->
                    new BookingException(
                        BookingErrorCode.APPOINTMENT_NOT_FOUND, "Appointment not found"));
    return addIntegrationProgress(List.of(appointment)).getFirst();
  }

  public AppointmentPage listAppointments(
      int limit, String cursor, OffsetDateTime from, OffsetDateTime to) {
    if (limit < 1 || limit > 100)
      throw new BookingException(
          BookingErrorCode.INVALID_PAGINATION, "Limit must be between 1 and 100");
    AppointmentCursor position = cursor == null ? null : AppointmentCursor.decode(cursor);
    if (position != null) {
      if ((from != null && (position.from() == null || !from.isEqual(position.from())))
          || (to != null && (position.to() == null || !to.isEqual(position.to()))))
        throw new BookingException(
            BookingErrorCode.INVALID_PAGINATION, "Date filters must match the cursor");
      from = position.from();
      to = position.to();
    }
    if (from != null && to != null && !from.isBefore(to))
      throw new BookingException(
          BookingErrorCode.INVALID_PAGINATION, "From must be earlier than to");
    var rows =
        appointmentRepository.list(
            limit + 1,
            position == null ? null : position.start(),
            position == null ? null : position.id(),
            from,
            to);
    boolean hasNext = rows.size() > limit;
    var page = rows.subList(0, Math.min(rows.size(), limit));
    String nextCursor = null;
    if (hasNext) {
      var last = page.getLast();
      nextCursor = new AppointmentCursor(last.startsAt(), last.id(), from, to).encode();
    }
    return new AppointmentPage(addIntegrationProgress(page), nextCursor);
  }

  private List<AppointmentView> addIntegrationProgress(List<AppointmentDetails> appointments) {
    if (appointments.isEmpty()) return List.of();
    Set<UUID> appointmentIds =
        appointments.stream().map(AppointmentDetails::id).collect(Collectors.toSet());
    Map<UUID, String> progress = progressQuery.findProgress(appointmentIds);
    return appointments.stream()
        .map(
            appointment ->
                AppointmentView.from(
                    appointment, progress.getOrDefault(appointment.id(), "PENDING")))
        .toList();
  }

  private UUID createOrReplay(UUID requestKey, String requestHash, BookAppointmentCommand command) {
    Optional<AppointmentRepository.PreviousRequest> previousRequest =
        appointmentRepository.claimRequest(requestKey, requestHash);
    if (previousRequest.isPresent()) {
      if (!requestHash.equals(previousRequest.get().requestHash()))
        throw new BookingException(
            BookingErrorCode.IDEMPOTENCY_MISMATCH,
            "Idempotency key has already been used for another request");
      return previousRequest.get().appointmentId();
    }
    Instant startInstant = command.startsAt().toInstant();
    OffsetDateTime start = startInstant.atOffset(ZoneOffset.UTC);
    OffsetDateTime end = policy.validateAndCalculateEnd(startInstant).atOffset(ZoneOffset.UTC);
    UUID specialtyId =
        appointmentRepository
            .findSpecialty(command.specialtyCode())
            .orElseThrow(
                () ->
                    new BookingException(BookingErrorCode.UNKNOWN_SPECIALTY, "Unknown specialty"));
    UUID patientId = appointmentRepository.findOrCreatePatient(command);
    if (appointmentRepository.patientHasOverlap(patientId, start, end))
      throw new BookingException(
          BookingErrorCode.PATIENT_ALREADY_BOOKED,
          "Patient already has an overlapping appointment");
    UUID doctorId =
        appointmentRepository
            .findAvailableDoctor(specialtyId, start, end)
            .orElseThrow(
                () ->
                    new BookingException(
                        BookingErrorCode.NO_DOCTOR_AVAILABLE,
                        "No doctor available for the requested interval"));
    UUID roomId =
        appointmentRepository
            .findAvailableRoom(start, end)
            .orElseThrow(
                () ->
                    new BookingException(
                        BookingErrorCode.NO_ROOM_AVAILABLE,
                        "No room available for the requested interval"));
    UUID appointmentId = UUID.randomUUID();
    appointmentRepository.saveAppointment(
        appointmentId, patientId, specialtyId, doctorId, roomId, start, end);
    postBookingActions.register(appointmentId);
    appointmentRepository.linkRequestToAppointment(requestKey, appointmentId);
    return appointmentId;
  }

  private static String hash(BookAppointmentCommand request) {
    // Length-prefix each field to avoid ambiguous concatenation; hash the client payload, not
    // configured duration.
    List<String> fields =
        List.of(
            request.patientName().strip(),
            request.normalizedEmail(),
            request.specialtyCode(),
            request.startsAt().toInstant().toString());
    String canonical =
        fields.stream().map(value -> value.length() + ":" + value).collect(Collectors.joining());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void pauseBeforeRetry() {
    try {
      // Spread out allocation retries with a bounded 5–25 ms delay.
      long retryDelayMillis = RETRY_JITTER.nextInt(5, 26);
      Thread.sleep(retryDelayMillis);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new BookingException(
          BookingErrorCode.BOOKING_INTERRUPTED,
          "Booking interrupted; retry with the same idempotency key");
    }
  }
}
