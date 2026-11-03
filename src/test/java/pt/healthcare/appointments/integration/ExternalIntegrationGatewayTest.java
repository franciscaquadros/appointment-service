package pt.healthcare.appointments.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.adapter.ExternalIntegrationGateway;
import pt.healthcare.appointments.integration.application.ConfirmationEmailComposer;
import pt.healthcare.appointments.integration.application.IntegrationFailure;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

class ExternalIntegrationGatewayTest {
  HttpServer server;
  JavaMailSender mail;
  ExternalIntegrationGateway gateway;
  ConfirmationEmailComposer composer;
  final AtomicInteger status = new AtomicInteger(201);
  final AtomicReference<String> key = new AtomicReference<>();
  final AtomicReference<String> payload = new AtomicReference<>();
  final AtomicReference<String> resource = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/reservations/",
        exchange -> {
          key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          resource.set(exchange.getRequestURI().getPath());
          exchange.getResponseHeaders().add("Retry-After", "120");
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    mail = mock(JavaMailSender.class);
    when(mail.createMimeMessage())
        .thenAnswer(_ -> new MimeMessage(Session.getInstance(new Properties())));
    URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var settings =
        new IntegrationProperties(
            false,
            10,
            5,
            Duration.ofSeconds(5),
            Duration.ofMinutes(15),
            Duration.ofSeconds(2),
            url,
            url,
            "",
            "",
            "appointments@example.invalid");
    composer = spy(new ConfirmationEmailComposer());
    gateway =
        new ExternalIntegrationGateway(
            RestClient.builder(), mail, settings, Clock.systemUTC(), composer);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  IntegrationJob job(IntegrationOperation operation) {
    return job(operation, OffsetDateTime.now().plusDays(1));
  }

  IntegrationJob job(IntegrationOperation operation, OffsetDateTime start) {
    return new IntegrationJob(
        UUID.randomUUID(),
        UUID.randomUUID(),
        operation,
        1,
        null,
        "doctor-1",
        "room-1",
        "Doctor Silva",
        "Room A",
        "ana@example.com",
        "Ana",
        start,
        start.plusMinutes(30));
  }

  @ParameterizedTest
  @EnumSource(
      value = IntegrationOperation.class,
      names = {"CALENDAR", "ROOM"})
  void sendsStableOperationKeyAndOnlyReservationData(IntegrationOperation operation) {
    IntegrationJob job = job(operation);
    gateway.execute(job);
    gateway.execute(job);
    assertThat(key.get()).isEqualTo(job.appointmentId() + ":" + operation.name());
    assertThat(job.operationKey()).isEqualTo(key.get());
    assertThat(resource.get()).isEqualTo("/reservations/" + job.appointmentId());
    assertThat(payload.get())
        .contains(
            operation == IntegrationOperation.CALENDAR ? "doctor-1" : "room-1",
            "startsAt",
            "endsAt")
        .doesNotContain("Ana", "ana@example.com");
  }

  @Test
  void retryableResponsesPreserveBoundedRetryAfterAndPermanentErrorsDoNotRetry() {
    status.set(429);
    assertThatThrownBy(() -> gateway.execute(job(IntegrationOperation.ROOM)))
        .isInstanceOfSatisfying(
            IntegrationFailure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("HTTP_429");
              assertThat(failure.retryable()).isTrue();
              assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(120));
            });
    status.set(401);
    assertThatThrownBy(() -> gateway.execute(job(IntegrationOperation.ROOM)))
        .isInstanceOfSatisfying(
            IntegrationFailure.class, failure -> assertThat(failure.retryable()).isFalse());
  }

  @Test
  void connectionFailureIsRetryable() {
    server.stop(0);
    assertThatThrownBy(() -> gateway.execute(job(IntegrationOperation.CALENDAR)))
        .isInstanceOfSatisfying(
            IntegrationFailure.class,
            failure -> {
              assertThat(failure.retryable()).isTrue();
              assertThat(failure.code()).isEqualTo("HTTP_UNAVAILABLE");
            });
  }

  @Test
  void buildsEmailWithDoctorRoomLisbonTimeAndStableMessageId() throws Exception {
    IntegrationJob job = job(IntegrationOperation.EMAIL);
    gateway.execute(job);
    var sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
    verify(mail).send(sent.capture());
    MimeMessage message = sent.getValue();
    assertThat(message.getHeader("Message-ID", null)).contains(job.appointmentId().toString());
    assertThat(message.getContent().toString()).contains("Doctor Silva", "Room A", "Ana");
    assertThat(message.getAllRecipients()[0].toString()).isEqualTo("ana@example.com");
  }

  @Test
  void usesComposedSubjectAndBodyForSmtpDelivery() throws Exception {
    var job = job(IntegrationOperation.EMAIL);
    doReturn(new ConfirmationEmailComposer.EmailContent("Composed subject", "Composed body"))
        .when(composer)
        .compose(job);
    gateway.execute(job);
    var sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
    verify(mail).send(sent.capture());
    assertThat(sent.getValue().getSubject()).isEqualTo("Composed subject");
    assertThat(sent.getValue().getContent()).isEqualTo("Composed body");
    assertThat(sent.getValue().getAllRecipients()[0].toString()).isEqualTo(job.patientEmail());
    assertThat(sent.getValue().getHeader("Message-ID", null))
        .isEqualTo("<" + job.appointmentId() + ".confirmation@appointments.local>");
  }

  @Test
  void doesNotSubmitConfirmationAfterItsStartTime() {
    var job = job(IntegrationOperation.EMAIL, OffsetDateTime.now().minusMinutes(1));
    assertThatThrownBy(() -> gateway.execute(job))
        .isInstanceOfSatisfying(
            IntegrationFailure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("CONFIRMATION_DEADLINE_PASSED");
              assertThat(failure.retryable()).isFalse();
            });
    verify(mail, never()).send(any(MimeMessage.class));
  }

  @Test
  void smtpFailureHasSafeRetryableClassification() {
    doThrow(new MailSendException("sensitive provider response"))
        .when(mail)
        .send(any(MimeMessage.class));
    assertThatThrownBy(() -> gateway.execute(job(IntegrationOperation.EMAIL)))
        .isInstanceOfSatisfying(
            IntegrationFailure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("SMTP_DELIVERY_FAILED");
              assertThat(failure.retryable()).isTrue();
              assertThat(failure.getMessage()).doesNotContain("sensitive");
            });
  }
}
