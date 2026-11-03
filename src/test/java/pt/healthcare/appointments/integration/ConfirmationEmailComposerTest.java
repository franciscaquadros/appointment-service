package pt.healthcare.appointments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.healthcare.appointments.integration.application.ConfirmationEmailComposer;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

class ConfirmationEmailComposerTest {
  @ParameterizedTest
  @CsvSource({
    "2030-01-02T10:00:00Z,2030-01-02T10:30:00Z,2 de janeiro de 2030,10:00,10:30",
    "2030-07-02T10:00:00Z,2030-07-02T10:30:00Z,2 de julho de 2030,11:00,11:30",
    "2030-07-02T13:15:00+02:00,2030-07-02T14:00:00+02:00,2 de julho de 2030,12:15,13:00"
  })
  void composesPortugueseDetailsAndActualTimeRangeInLisbon(
      String start, String end, String date, String localStart, String localEnd) {
    var job =
        new IntegrationJob(
            UUID.randomUUID(),
            UUID.randomUUID(),
            IntegrationOperation.EMAIL,
            1,
            null,
            "doctor-1",
            "room-1",
            "Dra. Silva",
            "Sala A",
            "ana@example.com",
            "Ana",
            OffsetDateTime.parse(start),
            OffsetDateTime.parse(end));
    var content = new ConfirmationEmailComposer().compose(job);
    assertThat(content.subject()).isEqualTo("Confirmação de consulta");
    assertThat(content.body())
        .contains(
            "Olá Ana,",
            date,
            "das " + localStart + " às " + localEnd + " (hora de Portugal continental)",
            "Médico/a: Dra. Silva",
            "Sala: Sala A",
            "Referência: " + job.appointmentId());
  }
}
