package pt.healthcare.appointments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import pt.healthcare.appointments.integration.application.IntegrationJobRepository;

/** Two database-backed gauges, refreshed on scrape; no extra polling task or cached state. */
@Component
public class IntegrationMetrics implements MeterBinder {
  private final IntegrationJobRepository repository;

  public IntegrationMetrics(IntegrationJobRepository repository) {
    this.repository = repository;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    registry.gauge(
        "integration.jobs.failed", repository, IntegrationJobRepository::countFailedJobs);
    registry.gauge(
        "integration.oldest.pending.seconds",
        repository,
        IntegrationJobRepository::oldestPendingSeconds);
  }
}
