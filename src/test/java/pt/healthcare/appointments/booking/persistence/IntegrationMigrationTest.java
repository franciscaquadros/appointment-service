package pt.healthcare.appointments.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IntegrationMigrationTest {
  @Test
  void simplifiesExistingJobsWithoutLosingBookingsOrCompletedWork() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.6-alpine")) {
      database.start();
      Flyway.configure()
          .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
          .locations("classpath:db/migration", "classpath:db/demo")
          .target("1003")
          .load()
          .migrate();
      try (var connection =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var statement = connection.createStatement()) {
        statement.execute(
            """
            INSERT INTO patient VALUES ('30000000-0000-0000-0000-000000000001','Ana','ana@example.com','ana@example.com');
            INSERT INTO appointment(id,patient_id,doctor_id,room_id,specialty_id,starts_at,ends_at)
            VALUES ('40000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
              '10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',
              '00000000-0000-0000-0000-000000000001','2030-01-02T10:00Z','2030-01-02T10:30Z');
            INSERT INTO idempotency_request(request_key,request_hash,appointment_id)
            VALUES ('50000000-0000-0000-0000-000000000001','fingerprint','40000000-0000-0000-0000-000000000001');
            INSERT INTO integration_job(id,appointment_id,operation,status,attempt_count,lease_token,lease_expires_at)
            VALUES ('60000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001',
              'CALENDAR','PROCESSING',2,'70000000-0000-0000-0000-000000000001',now()+interval '1 minute'),
              ('60000000-0000-0000-0000-000000000002','40000000-0000-0000-0000-000000000001','ROOM','SUCCEEDED',1,NULL,NULL),
              ('60000000-0000-0000-0000-000000000003','40000000-0000-0000-0000-000000000001','EMAIL','BLOCKED',0,NULL,NULL);
            INSERT INTO integration_job_attempt(id,job_id,event)
            VALUES ('80000000-0000-0000-0000-000000000001','60000000-0000-0000-0000-000000000001','CLAIMED');
            """);
        var upgrade =
            Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target("1004")
                .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        try (var jobs =
            statement.executeQuery(
                "SELECT operation,status,attempt_count FROM integration_job ORDER BY operation")) {
          assertThat(jobs.next()).isTrue();
          assertThat(jobs.getString("operation")).isEqualTo("CALENDAR");
          assertThat(jobs.getString("status")).isEqualTo("READY");
          assertThat(jobs.getInt("attempt_count")).isEqualTo(1);
          assertThat(jobs.next()).isTrue();
          assertThat(jobs.getString("status")).isEqualTo("BLOCKED");
          assertThat(jobs.next()).isTrue();
          assertThat(jobs.getString("status")).isEqualTo("SUCCEEDED");
          assertThat(jobs.next()).isFalse();
        }
        try (var retained =
            statement.executeQuery(
                """
            SELECT (SELECT count(*) FROM appointment) AS appointments,
              (SELECT count(*) FROM patient) AS patients,
              (SELECT count(*) FROM idempotency_request) AS requests,
              to_regclass('integration_job_attempt') AS old_history
            """)) {
          assertThat(retained.next()).isTrue();
          assertThat(retained.getInt("appointments")).isEqualTo(1);
          assertThat(retained.getInt("patients")).isEqualTo(1);
          assertThat(retained.getInt("requests")).isEqualTo(1);
          assertThat(retained.getObject("old_history")).isNull();
        }
      }
    }
  }
}
