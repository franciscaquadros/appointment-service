package pt.healthcare.appointments.integration.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.healthcare.appointments.booking.application.PostBookingActions;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.observability.JobTracing;

@Repository
public class JdbcPostBookingActions implements PostBookingActions {
  private final JdbcClient jdbc;
  private final JobTracing tracing;

  public JdbcPostBookingActions(JdbcClient jdbc, JobTracing tracing) {
    this.jdbc = jdbc;
    this.tracing = tracing;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void register(UUID appointmentId) {
    for (IntegrationOperation operation : IntegrationOperation.values()) {
      jdbc.sql(
              """
          INSERT INTO integration_job(id,appointment_id,operation,status,traceparent)
          VALUES (:id,:appointment,:operation,:status,:traceparent)
          """)
          .param("id", UUID.randomUUID())
          .param("appointment", appointmentId)
          .param("operation", operation.name())
          .param("status", operation == IntegrationOperation.EMAIL ? "BLOCKED" : "READY")
          .param("traceparent", tracing.currentTraceparent())
          .update();
    }
  }
}
