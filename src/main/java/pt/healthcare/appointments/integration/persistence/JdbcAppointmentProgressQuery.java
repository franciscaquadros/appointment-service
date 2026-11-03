package pt.healthcare.appointments.integration.persistence;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.healthcare.appointments.booking.application.AppointmentProgressQuery;

@Repository
public class JdbcAppointmentProgressQuery implements AppointmentProgressQuery {
  private final JdbcClient jdbc;

  public JdbcAppointmentProgressQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, String> findProgress(Set<UUID> appointmentIds) {
    if (appointmentIds.isEmpty()) return Map.of();
    return jdbc
        .sql(
            """
        SELECT appointment_id,
          CASE WHEN bool_or(status='FAILED') THEN 'ATTENTION_REQUIRED'
               WHEN count(*) FILTER (WHERE status='SUCCEEDED')=3 THEN 'COMPLETED'
               ELSE 'PENDING' END AS progress
        FROM integration_job WHERE appointment_id IN (:ids) GROUP BY appointment_id
        """)
        .param("ids", appointmentIds)
        .query(
            (row, _) ->
                Map.entry(row.getObject("appointment_id", UUID.class), row.getString("progress")))
        .list()
        .stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
