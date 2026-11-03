package pt.healthcare.appointments.booking.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.healthcare.appointments.booking.application.AllocationConflictException;
import pt.healthcare.appointments.booking.application.AppointmentRepository;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.booking.dto.AppointmentDetails;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;

@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {
  private static final String SPECIALTY = "specialty";
  private static final String ID = "id";
  private static final String REQUEST_KEY = "key";
  private static final String START_PARAMETER = "start";
  private static final String END_PARAMETER = "end";
  private final JdbcClient jdbc;
  private static final String SELECT_QUERY =
      """
        SELECT a.id, p.name AS patient_name, p.email AS patient_email, s.code AS specialty,
               d.name AS doctor, r.code AS room, a.starts_at, a.ends_at, a.status
        FROM appointment a JOIN patient p ON p.id=a.patient_id
        JOIN specialty s ON s.id=a.specialty_id JOIN doctor d ON d.id=a.doctor_id
        JOIN room r ON r.id=a.room_id
        """;

  public JdbcAppointmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AppointmentDetails> find(UUID appointmentId) {
    return jdbc.sql(SELECT_QUERY + " WHERE a.id=:id")
        .param(ID, appointmentId)
        .query(JdbcAppointmentRepository::map)
        .optional();
  }

  public List<AppointmentDetails> list(
      int limit, OffsetDateTime afterStart, UUID afterId, OffsetDateTime from, OffsetDateTime to) {
    StringBuilder query = new StringBuilder(SELECT_QUERY).append(" WHERE 1=1");
    if (afterStart != null) query.append(" AND (a.starts_at,a.id) > (:afterStart,:afterId)");
    if (from != null) query.append(" AND a.starts_at >= :from");
    if (to != null) query.append(" AND a.starts_at < :to");
    query.append(" ORDER BY a.starts_at,a.id LIMIT :limit");
    var statement = jdbc.sql(query.toString()).param("limit", limit);
    if (afterStart != null)
      statement = statement.param("afterStart", afterStart).param("afterId", afterId);
    if (from != null) statement = statement.param("from", from);
    if (to != null) statement = statement.param("to", to);
    return statement.query(JdbcAppointmentRepository::map).list();
  }

  private static AppointmentDetails map(ResultSet row, int rowNumber) throws SQLException {
    return new AppointmentDetails(
        row.getObject(ID, UUID.class),
        row.getString("patient_name"),
        row.getString("patient_email"),
        row.getString(SPECIALTY),
        row.getString("doctor"),
        row.getString("room"),
        row.getObject("starts_at", OffsetDateTime.class),
        row.getObject("ends_at", OffsetDateTime.class),
        row.getString("status"));
  }

  @Override
  public Optional<PreviousRequest> claimRequest(UUID requestKey, String requestHash) {
    jdbc.sql("SET LOCAL lock_timeout = '3s'").update();
    jdbc.sql("SET LOCAL statement_timeout = '5s'").update();
    int inserted =
        jdbc.sql(
                "INSERT INTO idempotency_request(request_key,request_hash) VALUES (:key,:hash) ON CONFLICT DO NOTHING")
            .param(REQUEST_KEY, requestKey)
            .param("hash", requestHash)
            .update();
    if (inserted == 1) return Optional.empty();
    return jdbc.sql(
            "SELECT request_hash,appointment_id FROM idempotency_request WHERE request_key=:key")
        .param(REQUEST_KEY, requestKey)
        .query(
            (row, _) ->
                new PreviousRequest(
                    row.getString("request_hash"), row.getObject("appointment_id", UUID.class)))
        .optional();
  }

  @Override
  public Optional<UUID> findSpecialty(String specialtyCode) {
    return jdbc.sql("SELECT id FROM specialty WHERE code=:code")
        .param("code", specialtyCode)
        .query(UUID.class)
        .optional();
  }

  @Override
  public UUID findOrCreatePatient(BookAppointmentCommand command) {
    jdbc.sql(
            "INSERT INTO patient(id,name,email,normalized_email) VALUES (:id,:name,:email,:normalized) ON CONFLICT (normalized_email) DO NOTHING")
        .param(ID, UUID.randomUUID())
        .param("name", command.patientName().strip())
        .param("email", command.patientEmail().strip())
        .param("normalized", command.normalizedEmail())
        .update();
    return jdbc.sql("SELECT id FROM patient WHERE normalized_email=:email")
        .param("email", command.normalizedEmail())
        .query(UUID.class)
        .single();
  }

  @Override
  public boolean patientHasOverlap(UUID patientId, OffsetDateTime start, OffsetDateTime end) {
    Boolean hasOverlap =
        jdbc.sql(
                """
        SELECT EXISTS(SELECT 1 FROM appointment WHERE patient_id=:patient
          AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:start, :end, '[)'))
        """)
            .param("patient", patientId)
            .param(START_PARAMETER, start)
            .param(END_PARAMETER, end)
            .query(Boolean.class)
            .single();
    return Objects.requireNonNull(hasOverlap, "Patient overlap query must return a boolean");
  }

  // Hold the chosen resource row until commit to avoid exclusion-check retry storms.
  // All allocations acquire doctor before room. Do not skip locked rows: busy is not unavailable.
  @Override
  public Optional<UUID> findAvailableDoctor(
      UUID specialtyId, OffsetDateTime start, OffsetDateTime end) {
    return jdbc.sql(
            """
        SELECT d.id FROM doctor d JOIN doctor_specialty ds ON ds.doctor_id=d.id
        WHERE d.active AND ds.specialty_id=:specialty AND NOT EXISTS
        (SELECT 1 FROM appointment a WHERE a.doctor_id=d.id AND
          tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(:start, :end, '[)'))
        ORDER BY d.id LIMIT 1 FOR NO KEY UPDATE OF d
        """)
        .param(SPECIALTY, specialtyId)
        .param(START_PARAMETER, start)
        .param(END_PARAMETER, end)
        .query(UUID.class)
        .optional();
  }

  @Override
  public Optional<UUID> findAvailableRoom(OffsetDateTime start, OffsetDateTime end) {
    return jdbc.sql(
            """
        SELECT r.id FROM room r WHERE r.active AND NOT EXISTS
        (SELECT 1 FROM appointment a WHERE a.room_id=r.id AND
          tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(:start, :end, '[)'))
        ORDER BY r.id LIMIT 1 FOR NO KEY UPDATE OF r
        """)
        .param(START_PARAMETER, start)
        .param(END_PARAMETER, end)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void saveAppointment(
      UUID appointmentId,
      UUID patientId,
      UUID specialtyId,
      UUID doctorId,
      UUID roomId,
      OffsetDateTime start,
      OffsetDateTime end) {
    try {
      jdbc.sql(
              "INSERT INTO appointment(id,patient_id,doctor_id,room_id,specialty_id,starts_at,ends_at) VALUES (:id,:patient,:doctor,:room,:specialty,:start,:end)")
          .param(ID, appointmentId)
          .param("patient", patientId)
          .param("doctor", doctorId)
          .param("room", roomId)
          .param(SPECIALTY, specialtyId)
          .param(START_PARAMETER, start)
          .param(END_PARAMETER, end)
          .update();
    } catch (DataAccessException failure) {
      throw translateAppointmentFailure(failure);
    }
  }

  private RuntimeException translateAppointmentFailure(DataAccessException failure) {
    String constraint = findExclusionConstraint(failure).orElse("");
    return switch (constraint) {
      case "patient_overlap" ->
          new BookingException(
              BookingErrorCode.PATIENT_ALREADY_BOOKED,
              "Patient already has an overlapping appointment");
      case "doctor_overlap", "room_overlap" -> new AllocationConflictException(failure);
      default -> failure;
    };
  }

  private Optional<String> findExclusionConstraint(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException postgresFailure
          && "23P01".equals(postgresFailure.getSQLState())) {
        var serverError = postgresFailure.getServerErrorMessage();
        if (serverError != null) {
          return Optional.ofNullable(serverError.getConstraint());
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void linkRequestToAppointment(UUID requestKey, UUID appointmentId) {
    jdbc.sql("UPDATE idempotency_request SET appointment_id=:appointment WHERE request_key=:key")
        .param("appointment", appointmentId)
        .param(REQUEST_KEY, requestKey)
        .update();
  }
}
