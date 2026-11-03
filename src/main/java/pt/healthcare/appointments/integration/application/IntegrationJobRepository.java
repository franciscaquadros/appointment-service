package pt.healthcare.appointments.integration.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;
import pt.healthcare.appointments.integration.dto.IntegrationJob;

public interface IntegrationJobRepository {
  /** The caller must keep this transaction open until the attempt's result is saved. */
  Optional<IntegrationJob> lockNextReadyJob(IntegrationOperation operation);

  void markSucceeded(UUID jobId);

  void recordFailedAttempt(UUID jobId, String errorCode, boolean terminal, Duration retryDelay);

  void releaseEligibleEmails();

  long countFailedJobs();

  double oldestPendingSeconds();
}
