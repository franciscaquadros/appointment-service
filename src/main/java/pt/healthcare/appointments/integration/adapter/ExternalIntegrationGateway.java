package pt.healthcare.appointments.integration.adapter;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.http.HttpClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.application.*;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

@Component
public class ExternalIntegrationGateway implements IntegrationGateway {
  private final RestClient calendar;
  private final RestClient rooms;
  private final JavaMailSender mail;
  private final IntegrationProperties settings;
  private final Clock clock;
  private final ConfirmationEmailComposer composer;

  public ExternalIntegrationGateway(
      RestClient.Builder builder,
      JavaMailSender mail,
      IntegrationProperties settings,
      Clock clock,
      ConfirmationEmailComposer composer) {
    var http =
        HttpClient.newBuilder()
            .connectTimeout(settings.requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(settings.requestTimeout());
    this.calendar =
        client(
            builder.clone(), requests, settings.calendarUrl().toString(), settings.calendarToken());
    this.rooms =
        client(builder.clone(), requests, settings.roomUrl().toString(), settings.roomToken());
    this.mail = mail;
    this.settings = settings;
    this.clock = clock;
    this.composer = composer;
  }

  private RestClient client(
      RestClient.Builder builder, JdkClientHttpRequestFactory requests, String url, String token) {
    builder.baseUrl(url).requestFactory(requests);
    if (token != null && !token.isBlank())
      builder.defaultHeader("Authorization", "Bearer " + token);
    return builder.build();
  }

  @Override
  public void execute(IntegrationJob job) {
    Runnable action =
        switch (job.operation()) {
          case CALENDAR -> () -> reserve(calendar, job, job.doctorExternalId());
          case ROOM -> () -> reserve(rooms, job, job.roomExternalId());
          case EMAIL -> () -> sendConfirmation(job);
        };
    action.run();
  }

  private void reserve(RestClient client, IntegrationJob job, String resourceId) {
    try {
      client
          .put()
          .uri("/reservations/{appointmentId}", job.appointmentId())
          .header("Idempotency-Key", job.operationKey())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              new Reservation(
                  job.appointmentId().toString(), resourceId, job.startsAt(), job.endsAt()))
          .exchange(
              (_, response) -> {
                int status = response.getStatusCode().value();
                if (status == 200 || status == 201 || status == 204) return null;
                boolean retryable = status == 408 || status == 429 || status >= 500;
                throw new IntegrationFailure(
                    "HTTP_" + status,
                    retryable,
                    retryAfter(response.getHeaders().getFirst("Retry-After")));
              });
    } catch (ResourceAccessException _) {
      throw new IntegrationFailure("HTTP_UNAVAILABLE", true);
    } catch (RestClientException _) {
      throw new IntegrationFailure("HTTP_PROTOCOL_ERROR", false);
    }
  }

  private Duration retryAfter(String header) {
    if (header == null) return Duration.ZERO;
    try {
      long seconds = Long.parseLong(header);
      return Duration.ofSeconds(Math.clamp(seconds, 0, settings.maximumRetryDelay().toSeconds()));
    } catch (NumberFormatException _) {
      try {
        Duration delay =
            Duration.between(
                clock.instant(),
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        if (delay.isNegative()) return Duration.ZERO;
        if (delay.compareTo(settings.maximumRetryDelay()) > 0) return settings.maximumRetryDelay();
        return delay;
      } catch (DateTimeParseException _) {
        return Duration.ZERO;
      }
    }
  }

  private void sendConfirmation(IntegrationJob job) {
    try {
      MimeMessage message = mail.createMimeMessage();
      var helper = new MimeMessageHelper(message, false, "UTF-8");
      helper.setFrom(settings.emailFrom());
      helper.setTo(job.patientEmail());
      var content = composer.compose(job);
      helper.setSubject(content.subject());
      helper.setText(content.body());
      message.setHeader(
          "Message-ID", "<" + job.appointmentId() + ".confirmation@appointments.local>");
      // Recheck immediately before submission, including manual replay.
      if (!job.startsAt().toInstant().isAfter(clock.instant()))
        throw new IntegrationFailure("CONFIRMATION_DEADLINE_PASSED", false);
      mail.send(message);
    } catch (MailAuthenticationException _) {
      throw new IntegrationFailure("SMTP_AUTHENTICATION_FAILED", false);
    } catch (MailParseException | MessagingException _) {
      throw new IntegrationFailure("SMTP_INVALID_MESSAGE", false);
    } catch (MailException _) {
      // Includes uncertain delivery: retry can duplicate mail, even with a stable Message-ID.
      throw new IntegrationFailure("SMTP_DELIVERY_FAILED", true);
    }
  }

  private record Reservation(
      String appointmentId, String resourceId, OffsetDateTime startsAt, OffsetDateTime endsAt) {}
}
