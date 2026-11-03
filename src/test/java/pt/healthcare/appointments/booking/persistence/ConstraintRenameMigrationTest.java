package pt.healthcare.appointments.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ConstraintRenameMigrationTest {
  @Test
  void upgradesExistingDemoDatabaseWithoutChangingAppliedMigrations(@TempDir Path legacy)
      throws Exception {
    // Reconstruct the old release: no repeatable seed existed yet.
    for (String resource :
        new String[] {
          "db/migration/V1__appointment_schema.sql", "db/demo/V1000__demo_catalog.sql"
        }) {
      try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
        Files.copy(input, legacy.resolve(Path.of(resource).getFileName()));
      }
    }
    try (var database = new PostgreSQLContainer("postgres:17.6-alpine")) {
      database.start();
      Flyway.configure()
          .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
          .locations("filesystem:" + legacy)
          .target("1000")
          .load()
          .migrate();

      var upgrade =
          Flyway.configure()
              .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
              .locations("classpath:db/migration", "classpath:db/demo")
              .target("1001")
              .load();
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2); // Rename plus new repeatable.
      upgrade.validate();

      try (var connection =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var statement = connection.createStatement()) {
        var names = new ArrayList<String>();
        try (var constraints =
            statement.executeQuery(
                "SELECT conname FROM pg_constraint WHERE conrelid='appointment'::regclass AND contype='x'")) {
          while (constraints.next()) names.add(constraints.getString(1));
        }
        assertThat(names)
            .containsExactlyInAnyOrder("patient_overlap", "doctor_overlap", "room_overlap");
        try (var doctors = statement.executeQuery("SELECT count(*) FROM doctor")) {
          assertThat(doctors.next()).isTrue();
          assertThat(doctors.getInt(1)).isEqualTo(2);
        }
      }
    }
  }
}
