package pt.healthcare.appointments.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.application.*;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;
import pt.healthcare.appointments.observability.JobTracing;

@SpringBootTest(properties = "integrations.enabled=false")
@ActiveProfiles("demo")
class IntegrationWorkerTest {
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.6-alpine");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Autowired BookingService bookings;
  @Autowired IntegrationWorker worker;
  @Autowired IntegrationJobRepository repository;
  @Autowired IntegrationProperties settings;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcClient jdbc;
  @Autowired JobTracing tracing;
  @Autowired Tracer tracer;
  @Autowired MeterRegistry metrics;
  @MockitoBean IntegrationGateway gateway;

  @BeforeEach
  void clear() {
    jdbc.sql("TRUNCATE integration_job,idempotency_request,appointment,patient CASCADE").update();
    reset(gateway);
  }

  UUID book() {
    return book(2);
  }

  UUID book(int daysAhead) {
    var start =
        LocalDate.now(ZoneId.of("Europe/Lisbon"))
            .plusDays(daysAhead)
            .atTime(10, 0)
            .atZone(ZoneId.of("Europe/Lisbon"))
            .toOffsetDateTime();
    return bookings
        .book(
            UUID.randomUUID(),
            new BookAppointmentCommand("Ana", "ana@example.com", "CARDIOLOGY", start))
        .id();
  }

  String status(String operation) {
    return jdbc.sql("SELECT status FROM integration_job WHERE operation=:operation")
        .param("operation", operation)
        .query(String.class)
        .single();
  }

  int attempts(String operation) {
    return Objects.requireNonNull(
        jdbc.sql("SELECT attempt_count FROM integration_job WHERE operation=:operation")
            .param("operation", operation)
            .query(Integer.class)
            .single());
  }

  @Test
  void continuesBookingTraceAcrossPersistedJobs() {
    var bookingSpan = tracer.nextSpan().name("booking-test").start();
    String traceId = bookingSpan.context().traceId();
    try (var _ = tracer.withSpan(bookingSpan)) {
      book();
    } finally {
      bookingSpan.end();
    }
    var saved = jdbc.sql("SELECT traceparent FROM integration_job").query(String.class).list();
    assertThat(saved).hasSize(3).allMatch(context -> context.startsWith("00-" + traceId + "-"));
    List<String> processingContexts = new ArrayList<>();
    doAnswer(
            _ -> {
              processingContexts.add(tracing.currentTraceparent());
              return null;
            })
        .when(gateway)
        .execute(any());
    worker.runOnce();
    worker.runOnce();
    assertThat(processingContexts)
        .hasSize(3)
        .allMatch(context -> context.startsWith("00-" + traceId + "-"));
    assertThat(processingContexts).doesNotContain(saved.getFirst());
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test
  void executesResourcesBeforeEmailAndUsesTransactionLocks() {
    UUID appointment = book();
    List<IntegrationOperation> operations = new ArrayList<>();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              IntegrationJob job = call.getArgument(0);
              if (job.operation() == IntegrationOperation.EMAIL)
                assertThat(operations)
                    .containsExactlyInAnyOrder(
                        IntegrationOperation.CALENDAR, IntegrationOperation.ROOM);
              operations.add(job.operation());
              return null;
            })
        .when(gateway)
        .execute(any());
    assertThat(worker.runOnce()).isEqualTo(2);
    assertThat(status("EMAIL")).isEqualTo("BLOCKED");
    assertThat(operations)
        .containsExactlyInAnyOrder(IntegrationOperation.CALENDAR, IntegrationOperation.ROOM);
    assertThat(worker.runOnce()).isEqualTo(1);
    assertThat(operations.getLast()).isEqualTo(IntegrationOperation.EMAIL);
    assertThat(bookings.findAppointment(appointment).integrationStatus()).isEqualTo("COMPLETED");
    assertThat(attempts("EMAIL")).isEqualTo(1);
  }

  @Test
  void rollbackAfterRemoteSuccessReleasesLockAndUsesSameOperationKeyOnRetry() {
    UUID appointment = book();
    var failingRepository =
        mock(IntegrationJobRepository.class, org.mockito.AdditionalAnswers.delegatesTo(repository));
    doThrow(new DataAccessResourceFailureException("Simulated result write outage"))
        .when(failingRepository)
        .markSucceeded(any());
    var interruptedWorker =
        new IntegrationWorker(
            failingRepository, gateway, transaction, settings, Clock.systemUTC(), tracing);
    assertThatThrownBy(interruptedWorker::runOnce)
        .isInstanceOf(DataAccessResourceFailureException.class);
    verify(failingRepository, never()).recordFailedAttempt(any(), anyString(), anyBoolean(), any());
    assertThat(status("CALENDAR")).isEqualTo("READY");
    assertThat(status("ROOM")).isEqualTo("READY");
    assertThat(attempts("CALENDAR") + attempts("ROOM")).isZero();
    // No lease expiry or recovery process: rollback has already released the job's lock.
    worker.runOnce();
    worker.runOnce();
    assertThat(bookings.findAppointment(appointment).integrationStatus()).isEqualTo("COMPLETED");
    var calls = org.mockito.ArgumentCaptor.forClass(IntegrationJob.class);
    verify(gateway, times(4)).execute(calls.capture());
    assertThat(calls.getAllValues().stream().map(IntegrationJob::operationKey).distinct())
        .hasSize(3);
  }

  @Test
  void concurrentWorkersDoNotExecuteTheSameJob() throws Exception {
    UUID appointment = book();
    var resourcesStarted = new CountDownLatch(2);
    var releaseResources = new CountDownLatch(1);
    Queue<UUID> executedJobs = new ConcurrentLinkedQueue<>();
    doAnswer(
            call -> {
              IntegrationJob job = call.getArgument(0);
              executedJobs.add(job.id());
              if (job.operation() != IntegrationOperation.EMAIL) {
                resourcesStarted.countDown();
                assertThat(releaseResources.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return null;
            })
        .when(gateway)
        .execute(any());
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 8; i++) runs.add(executor.submit(() -> worker.runOnce()));
      try {
        assertThat(resourcesStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executedJobs).hasSize(2).doesNotHaveDuplicates();
        assertThat(status("EMAIL")).isEqualTo("BLOCKED");
      } finally {
        releaseResources.countDown();
      }
      for (var run : runs) run.get(10, TimeUnit.SECONDS);
    }
    worker.runOnce();
    assertThat(executedJobs).hasSize(3).doesNotHaveDuplicates();
    assertThat(bookings.findAppointment(appointment).integrationStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void slowEmailDoesNotBlockCalendarOrRoom() throws Exception {
    book();
    assertThat(worker.runOnce(IntegrationOperation.CALENDAR)).isEqualTo(1);
    assertThat(worker.runOnce(IntegrationOperation.ROOM)).isEqualTo(1);
    var emailStarted = new CountDownLatch(1);
    var releaseEmail = new CountDownLatch(1);
    doAnswer(
            call -> {
              IntegrationJob job = call.getArgument(0);
              if (job.operation() == IntegrationOperation.EMAIL) {
                emailStarted.countDown();
                assertThat(releaseEmail.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return null;
            })
        .when(gateway)
        .execute(any());

    try (var executor = Executors.newSingleThreadExecutor()) {
      var email = executor.submit(() -> worker.runOnce(IntegrationOperation.EMAIL));
      try {
        assertThat(emailStarted.await(10, TimeUnit.SECONDS)).isTrue();
        UUID nextAppointment = book(3);
        assertThat(worker.runOnce(IntegrationOperation.CALENDAR)).isEqualTo(1);
        assertThat(worker.runOnce(IntegrationOperation.ROOM)).isEqualTo(1);
        assertThat(
                jdbc.sql(
                        "SELECT count(*) FROM integration_job WHERE appointment_id=:id AND status='SUCCEEDED'")
                    .param("id", nextAppointment)
                    .query(Long.class)
                    .single())
            .isEqualTo(2);
      } finally {
        releaseEmail.countDown();
      }
      email.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void shutdownFinishesCurrentJobAndLeavesTheRestPending() {
    book();
    var stoppingWorker =
        new IntegrationWorker(
            repository, gateway, transaction, settings, Clock.systemUTC(), tracing);
    doAnswer(
            _ -> {
              // Simulate the close event arriving while the external call is in flight.
              stoppingWorker.stop();
              return null;
            })
        .when(gateway)
        .execute(any());
    assertThat(stoppingWorker.runOnce()).isEqualTo(1);
    verify(gateway, times(1)).execute(any());
    assertThat(
            jdbc.sql("SELECT count(*) FROM integration_job WHERE status='SUCCEEDED'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(stoppingWorker.runOnce()).isZero();
    verifyNoMoreInteractions(gateway);
    // A live instance can pick up the remaining durable jobs.
    worker.runOnce();
    worker.runOnce();
    assertThat(
            jdbc.sql("SELECT count(*) FROM integration_job WHERE status='SUCCEEDED'")
                .query(Integer.class)
                .single())
        .isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 30})
  void slowFailurePreservesFullBackoffAndRetryAfter(long retryAfterSeconds) {
    book();
    var failedAt = new AtomicReference<OffsetDateTime>();
    doAnswer(
            _ -> {
              // Simulate provider latency inside the job transaction. Use the database clock
              // for both timestamps so host/container clock differences cannot affect the check.
              jdbc.sql("SELECT pg_sleep(1)").query((row, _) -> true).single();
              failedAt.set(
                  jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single());
              throw new IntegrationFailure("HTTP_503", true, Duration.ofSeconds(retryAfterSeconds));
            })
        .when(gateway)
        .execute(any());

    assertThat(worker.runOnce(IntegrationOperation.CALENDAR)).isEqualTo(1);
    var nextAttempt =
        jdbc.sql("SELECT next_attempt_at FROM integration_job WHERE operation='CALENDAR'")
            .query(OffsetDateTime.class)
            .single();
    Duration expectedDelay =
        Duration.ofMillis(
            Math.max(
                settings.initialRetryDelay().toMillis(),
                Duration.ofSeconds(retryAfterSeconds).toMillis()));
    assertThat(nextAttempt.toInstant())
        .isAfterOrEqualTo(failedAt.get().toInstant().plus(expectedDelay));
    assertThat(status("CALENDAR")).isEqualTo("READY");
    assertThat(attempts("CALENDAR")).isEqualTo(1);
    assertThat(worker.runOnce(IntegrationOperation.CALENDAR)).isZero();
    verify(gateway).execute(any());
  }

  @Test
  void transientFailureIsScheduledThenRecoveredWithoutRepeatingSuccessfulWork() {
    UUID appointment = book();
    doAnswer(
            call -> {
              IntegrationJob job = call.getArgument(0);
              if (job.operation() == IntegrationOperation.CALENDAR)
                throw new IntegrationFailure("HTTP_429", true, Duration.ofSeconds(30));
              return null;
            })
        .when(gateway)
        .execute(any());
    worker.runOnce();
    assertThat(status("CALENDAR")).isEqualTo("READY");
    assertThat(status("ROOM")).isEqualTo("SUCCEEDED");
    assertThat(status("EMAIL")).isEqualTo("BLOCKED");
    var nextAttempt =
        jdbc.sql("SELECT next_attempt_at FROM integration_job WHERE operation='CALENDAR'")
            .query(OffsetDateTime.class)
            .single();
    assertThat(Objects.requireNonNull(nextAttempt).toInstant())
        .isAfter(Instant.now().plusSeconds(20));
    reset(gateway);
    jdbc.sql("UPDATE integration_job SET next_attempt_at=now() WHERE status='READY'").update();
    assertThat(worker.runOnce()).isEqualTo(1);
    assertThat(status("EMAIL")).isEqualTo("BLOCKED");
    assertThat(worker.runOnce()).isEqualTo(1);
    verify(gateway, never()).execute(argThat(job -> job.operation() == IntegrationOperation.ROOM));
    assertThat(bookings.findAppointment(appointment).integrationStatus()).isEqualTo("COMPLETED");
    assertThat(attempts("CALENDAR")).isEqualTo(2);
  }

  @Test
  void permanentFailureKeepsAppointmentConfirmedAndEmailBlocked() {
    UUID appointment = book();
    jdbc.sql("UPDATE integration_job SET created_at=now()-interval '10 minutes'").update();
    assertThat(metrics.get("integration.jobs.failed").gauge().value()).isZero();
    assertThat(metrics.get("integration.oldest.pending.seconds").gauge().value())
        .isGreaterThanOrEqualTo(600);
    doAnswer(
            call -> {
              IntegrationJob job = call.getArgument(0);
              if (job.operation() == IntegrationOperation.ROOM)
                throw new IntegrationFailure("HTTP_401", false);
              return null;
            })
        .when(gateway)
        .execute(any());
    worker.runOnce();
    assertThat(bookings.findAppointment(appointment).status()).isEqualTo("CONFIRMED");
    assertThat(bookings.findAppointment(appointment).integrationStatus())
        .isEqualTo("ATTENTION_REQUIRED");
    assertThat(status("ROOM")).isEqualTo("FAILED");
    assertThat(metrics.get("integration.jobs.failed").gauge().value()).isEqualTo(1);
    assertThat(status("EMAIL")).isEqualTo("BLOCKED");
    reset(gateway);
    assertThat(worker.runOnce()).isZero();
    verifyNoInteractions(gateway);
  }

  @Test
  void retriesStopAfterConfiguredMaximum() {
    book();
    doThrow(new IntegrationFailure("HTTP_503", true)).when(gateway).execute(any());
    for (int attempt = 1; attempt <= settings.maximumAttempts(); attempt++) {
      worker.runOnce();
      jdbc.sql("UPDATE integration_job SET next_attempt_at=now() WHERE status='READY'").update();
    }
    assertThat(attempts("CALENDAR")).isEqualTo(settings.maximumAttempts());
    assertThat(status("CALENDAR")).isEqualTo("FAILED");
    assertThat(status("ROOM")).isEqualTo("FAILED");
    assertThat(worker.runOnce()).isZero();
  }

  @Test
  void emailIsNotSentAfterAppointmentStart() {
    book();
    var lateWorker =
        new IntegrationWorker(
            repository,
            gateway,
            transaction,
            settings,
            Clock.offset(Clock.systemUTC(), Duration.ofDays(3)),
            tracing);
    lateWorker.runOnce();
    lateWorker.runOnce();
    verify(gateway, never()).execute(argThat(job -> job.operation() == IntegrationOperation.EMAIL));
    assertThat(status("EMAIL")).isEqualTo("FAILED");
    assertThat(
            jdbc.sql("SELECT last_error_code FROM integration_job WHERE operation='EMAIL'")
                .query(String.class)
                .single())
        .isEqualTo("CONFIRMATION_DEADLINE_PASSED");
  }
}
