package pt.healthcare.appointments.integration.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.healthcare.appointments.integration.application.IntegrationJobRepository;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

@Repository
public class JdbcIntegrationJobRepository implements IntegrationJobRepository {
  private final JdbcClient jdbc;

  public JdbcIntegrationJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IntegrationJob> lockNextReadyJob(IntegrationOperation operation) {
    setTransactionDeadlines();
    return jdbc.sql(
            """
        SELECT j.id,j.appointment_id,j.operation,j.attempt_count,j.traceparent,
          d.external_id AS doctor_external_id,r.external_id AS room_external_id,
          d.name AS doctor_name,r.code AS room_code,p.email,p.name AS patient_name,a.starts_at,a.ends_at
        FROM integration_job j JOIN appointment a ON a.id=j.appointment_id
        JOIN doctor d ON d.id=a.doctor_id JOIN room r ON r.id=a.room_id
        JOIN patient p ON p.id=a.patient_id
        WHERE j.status='READY' AND j.next_attempt_at<=now()
          AND (:operation='' OR j.operation=:operation)
        ORDER BY j.next_attempt_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
        """)
        .param("operation", operation == null ? "" : operation.name())
        .query(
            (row, _) ->
                new IntegrationJob(
                    row.getObject("id", UUID.class),
                    row.getObject("appointment_id", UUID.class),
                    IntegrationOperation.valueOf(row.getString("operation")),
                    row.getInt("attempt_count") + 1,
                    row.getString("traceparent"),
                    row.getString("doctor_external_id"),
                    row.getString("room_external_id"),
                    row.getString("doctor_name"),
                    row.getString("room_code"),
                    row.getString("email"),
                    row.getString("patient_name"),
                    row.getObject("starts_at", OffsetDateTime.class),
                    row.getObject("ends_at", OffsetDateTime.class)))
        .optional();
  }

  @Override
  public void markSucceeded(UUID jobId) {
    jdbc.sql(
            """
        UPDATE integration_job SET status='SUCCEEDED',attempt_count=attempt_count+1,
          last_error_code=NULL,updated_at=now() WHERE id=:id
        """)
        .param("id", jobId)
        .update();
  }

  @Override
  public void recordFailedAttempt(
      UUID jobId, String errorCode, boolean terminal, Duration retryDelay) {
    // Start the delay after the provider call, rather than at transaction start.
    jdbc.sql(
            """
        UPDATE integration_job SET status=:status,attempt_count=attempt_count+1,
          last_error_code=:error,next_attempt_at=clock_timestamp()+:delay * interval '1 millisecond',updated_at=now()
        WHERE id=:id
        """)
        .param("id", jobId)
        .param("status", terminal ? "FAILED" : "READY")
        .param("error", errorCode)
        .param("delay", retryDelay.toMillis())
        .update();
  }

  @Override
  public void releaseEligibleEmails() {
    setTransactionDeadlines();
    jdbc.sql(
            """
        UPDATE integration_job email SET status='READY',next_attempt_at=now(),updated_at=now()
        WHERE email.operation='EMAIL' AND email.status='BLOCKED'
        AND (SELECT count(*) FROM integration_job prerequisite
          WHERE prerequisite.appointment_id=email.appointment_id
          AND prerequisite.operation IN ('CALENDAR','ROOM') AND prerequisite.status='SUCCEEDED')=2
        """)
        .update();
  }

  @Override
  public long countFailedJobs() {
    return Objects.requireNonNull(
        jdbc.sql("SELECT count(*) FROM integration_job WHERE status='FAILED'")
            .query(Long.class)
            .single());
  }

  @Override
  public double oldestPendingSeconds() {
    return Objects.requireNonNull(
        jdbc.sql(
                """
        SELECT coalesce(extract(epoch FROM now()-min(created_at)),0)::float8
        FROM integration_job WHERE status IN ('BLOCKED','READY')
        """)
            .query(Double.class)
            .single());
  }

  private void setTransactionDeadlines() {
    jdbc.sql("SET LOCAL lock_timeout='3s'").update();
    jdbc.sql("SET LOCAL statement_timeout='5s'").update();
  }
}
