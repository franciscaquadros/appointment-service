package pt.healthcare.appointments.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DemoCatalogMigrationTest {
  @Test
  void productionLocationsDoNotInstallDemoCatalog() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.6-alpine")) {
      database.start();
      var flyway =
          Flyway.configure()
              .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
              .locations("classpath:db/migration")
              .load();
      flyway.migrate();
      flyway.validate();
      try (var connection =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  """
              SELECT (SELECT count(*) FROM specialty) AS specialties,
                (SELECT count(*) FROM doctor) AS doctors,
                (SELECT count(*) FROM room) AS rooms,
                (SELECT count(*) FROM flyway_schema_history WHERE script IN ('V1000__demo_catalog.sql','R__demo_catalog.sql')) AS demo_migrations
              """)) {
        assertThat(result.next()).isTrue();
        for (String column : new String[] {"specialties", "doctors", "rooms", "demo_migrations"})
          assertThat(result.getInt(column)).isZero();
      }
    }
  }

  @Test
  void freshDemoAndChangedRepeatablePreserveExistingCatalog(@TempDir Path updated)
      throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.6-alpine")) {
      database.start();
      var flyway =
          Flyway.configure()
              .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
              .locations("classpath:db/migration", "classpath:db/demo")
              .load();
      flyway.migrate();
      flyway.validate();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      try (var connection =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var statement = connection.createStatement()) {
        statement.execute(
            "UPDATE doctor SET name='Edited locally',active=false WHERE external_id='doctor-silva'");
        // Simulate a future seed release without modifying any applied versioned file.
        for (String name : new String[] {"V1000__demo_catalog.sql", "R__demo_catalog.sql"}) {
          try (var input = getClass().getClassLoader().getResourceAsStream("db/demo/" + name)) {
            Files.copy(input, updated.resolve(name));
          }
        }
        Files.writeString(
            updated.resolve("R__demo_catalog.sql"),
            """

            INSERT INTO specialty (id,code,name)
            VALUES ('00000000-0000-0000-0000-000000000003','NEUROLOGY','Neurologia')
            ON CONFLICT (id) DO NOTHING;
            """,
            java.nio.file.StandardOpenOption.APPEND);
        var upgrade =
            Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration", "filesystem:" + updated)
                .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try (var result =
            statement.executeQuery(
                """
            SELECT (SELECT count(*) FROM specialty) AS specialties,
              (SELECT count(*) FROM doctor) AS doctors,
              (SELECT count(*) FROM doctor_specialty) AS qualifications,
              (SELECT count(*) FROM room) AS rooms,
              name,active FROM doctor WHERE external_id='doctor-silva'
            """)) {
          assertThat(result.next()).isTrue();
          assertThat(result.getInt("specialties")).isEqualTo(3);
          assertThat(result.getInt("doctors")).isEqualTo(2);
          assertThat(result.getInt("qualifications")).isEqualTo(3);
          assertThat(result.getInt("rooms")).isEqualTo(2);
          assertThat(result.getString("name")).isEqualTo("Edited locally");
          assertThat(result.getBoolean("active")).isFalse();
        }
      }
    }
  }
}
