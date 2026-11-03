package pt.healthcare.appointments.integration.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

/** Portuguese confirmation content, independent of SMTP message construction and delivery. */
@Component
public class ConfirmationEmailComposer {
  private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu", Locale.forLanguageTag("pt-PT"));
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  public EmailContent compose(IntegrationJob job) {
    var start = job.startsAt().atZoneSameInstant(LISBON);
    var end = job.endsAt().atZoneSameInstant(LISBON);
    String body =
        """
        Olá %s,

        A sua consulta está confirmada para %s,
        das %s às %s (hora de Portugal continental).

        Médico/a: %s
        Sala: %s
        Referência: %s
        """
            .formatted(
                job.patientName(),
                DATE.format(start),
                TIME.format(start),
                TIME.format(end),
                job.doctorName(),
                job.roomCode(),
                job.appointmentId());
    return new EmailContent("Confirmação de consulta", body);
  }

  public record EmailContent(String subject, String body) {}
}
