package pt.healthcare.appointments.booking.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import pt.healthcare.appointments.booking.dto.AppointmentDetails;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;

/** Appointment persistence; writes join the service transaction and never commit independently. */
public interface AppointmentRepository {
  record PreviousRequest(String requestHash, UUID appointmentId) {}

  Optional<PreviousRequest> claimRequest(UUID requestKey, String requestHash);

  Optional<UUID> findSpecialty(String specialtyCode);

  UUID findOrCreatePatient(BookAppointmentCommand command);

  boolean patientHasOverlap(UUID patientId, OffsetDateTime start, OffsetDateTime end);

  Optional<UUID> findAvailableDoctor(UUID specialtyId, OffsetDateTime start, OffsetDateTime end);

  Optional<UUID> findAvailableRoom(OffsetDateTime start, OffsetDateTime end);

  void saveAppointment(
      UUID appointmentId,
      UUID patientId,
      UUID specialtyId,
      UUID doctorId,
      UUID roomId,
      OffsetDateTime start,
      OffsetDateTime end);

  void linkRequestToAppointment(UUID requestKey, UUID appointmentId);

  Optional<AppointmentDetails> find(UUID appointmentId);

  List<AppointmentDetails> list(
      int limit, OffsetDateTime afterStart, UUID afterId, OffsetDateTime from, OffsetDateTime to);
}
