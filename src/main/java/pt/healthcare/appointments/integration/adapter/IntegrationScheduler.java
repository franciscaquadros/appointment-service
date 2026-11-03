package pt.healthcare.appointments.integration.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.application.IntegrationWorker;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;

@Component
public class IntegrationScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(IntegrationScheduler.class);
  private final IntegrationWorker worker;
  private final IntegrationProperties settings;

  public IntegrationScheduler(IntegrationWorker worker, IntegrationProperties settings) {
    this.worker = worker;
    this.settings = settings;
  }

  @Scheduled(
      fixedDelayString = "${integrations.poll-delay:1s}",
      scheduler = "integrationTaskScheduler")
  public void pollCalendar() {
    poll(IntegrationOperation.CALENDAR);
  }

  @Scheduled(
      fixedDelayString = "${integrations.poll-delay:1s}",
      scheduler = "integrationTaskScheduler")
  public void pollRoom() {
    poll(IntegrationOperation.ROOM);
  }

  @Scheduled(
      fixedDelayString = "${integrations.poll-delay:1s}",
      scheduler = "integrationTaskScheduler")
  public void pollEmail() {
    poll(IntegrationOperation.EMAIL);
  }

  private void poll(IntegrationOperation operation) {
    if (!settings.enabled()) return;
    try {
      while (!worker.isStopping()
          && !Thread.currentThread().isInterrupted()
          && worker.runOnce(operation) == settings.jobsPerPoll()) {
        // A full batch may leave ready work; only pause after catching up.
      }
    } catch (RuntimeException failure) {
      LOG.error(
          "Integration processing rolled back; pending work will be retried exceptionType={}",
          failure.getClass().getName());
    }
  }
}
