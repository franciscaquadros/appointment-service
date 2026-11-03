package pt.healthcare.appointments.integration.application;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;
import pt.healthcare.appointments.observability.JobTracing;

@Service
public class IntegrationWorker {
  private static final Logger LOG = LoggerFactory.getLogger(IntegrationWorker.class);
  private final IntegrationJobRepository repository;
  private final IntegrationGateway gateway;
  private final TransactionTemplate transaction;
  private final IntegrationProperties settings;
  private final Clock clock;
  private final JobTracing tracing;
  private volatile boolean stopping;

  public IntegrationWorker(
      IntegrationJobRepository repository,
      IntegrationGateway gateway,
      TransactionTemplate transaction,
      IntegrationProperties settings,
      Clock clock,
      JobTracing tracing) {
    this.tracing = tracing;
    this.repository = repository;
    this.gateway = gateway;
    this.transaction = transaction;
    this.settings = settings;
    this.clock = clock;
  }

  @EventListener(ContextClosedEvent.class)
  public void stop() {
    stopping = true;
  }

  public boolean isStopping() {
    return stopping;
  }

  public int runOnce() {
    return runOnce(null);
  }

  /** Processes one bounded batch, optionally restricted to one operation. */
  public int runOnce(IntegrationOperation operation) {
    if (stopping || Thread.currentThread().isInterrupted()) return 0;
    // Sweep once per batch in a separate transaction, before taking any job lock.
    if (!Thread.currentThread().isInterrupted()
        && (operation == null || operation == IntegrationOperation.EMAIL))
      transaction.executeWithoutResult(_ -> repository.releaseEligibleEmails());
    int processed = 0;
    while (processed < settings.jobsPerPoll()
        && !stopping
        && !Thread.currentThread().isInterrupted()) {
      boolean found = Boolean.TRUE.equals(transaction.execute(_ -> processNextJob(operation)));
      if (!found) break;
      processed++;
    }
    return processed;
  }

  private boolean processNextJob(IntegrationOperation operation) {
    if (stopping || Thread.currentThread().isInterrupted()) return false;
    var nextJob = repository.lockNextReadyJob(operation);
    if (nextJob.isEmpty() || stopping || Thread.currentThread().isInterrupted()) return false;
    IntegrationJob job = nextJob.get();
    tracing.process(job.traceparent(), job.operation().name(), () -> process(job));
    return true;
  }

  private void process(IntegrationJob job) {
    IntegrationFailure failure = execute(job);
    // Save outside the adapter catch block: storage failure must roll back the whole attempt.
    if (failure == null) {
      repository.markSucceeded(job.id());
    } else {
      tracing.markFailure(failure.code());
      boolean terminal = !failure.retryable() || job.attemptCount() >= settings.maximumAttempts();
      repository.recordFailedAttempt(
          job.id(), failure.code(), terminal, retryDelay(job.attemptCount(), failure.retryAfter()));
      if (terminal)
        LOG.error(
            "Integration job requires operator attention jobId={} operation={} code={}",
            job.id(),
            job.operation(),
            failure.code());
    }
  }

  private IntegrationFailure execute(IntegrationJob job) {
    try {
      if (job.operation() == IntegrationOperation.EMAIL
          && !job.startsAt().toInstant().isAfter(clock.instant()))
        throw new IntegrationFailure("CONFIRMATION_DEADLINE_PASSED", false);
      // The database row lock prevents another instance executing this job simultaneously.
      gateway.execute(job);
      LOG.info(
          "Integration call succeeded jobId={} operation={} attempt={}",
          job.id(),
          job.operation(),
          job.attemptCount());
      return null;
    } catch (IntegrationFailure failure) {
      LOG.warn(
          "Integration call failed jobId={} operation={} attempt={} code={}",
          job.id(),
          job.operation(),
          job.attemptCount(),
          failure.code());
      return failure;
    } catch (RuntimeException failure) {
      LOG.error(
          "Unexpected integration failure jobId={} operation={} exceptionType={}",
          job.id(),
          job.operation(),
          failure.getClass().getName());
      return new IntegrationFailure("UNEXPECTED_INTEGRATION_ERROR", false);
    }
  }

  private Duration retryDelay(int attempt, Duration retryAfter) {
    long exponentialDelay =
        settings.initialRetryDelay().toMillis() * (1L << Math.min(attempt - 1, 20));
    long boundedDelay = Math.min(settings.maximumRetryDelay().toMillis(), exponentialDelay);
    long boundedHint = Math.min(settings.maximumRetryDelay().toMillis(), retryAfter.toMillis());
    return Duration.ofMillis(Math.max(boundedDelay, boundedHint));
  }
}
