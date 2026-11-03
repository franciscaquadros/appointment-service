package pt.healthcare.appointments.integration;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import pt.healthcare.appointments.configuration.IntegrationProperties;
import pt.healthcare.appointments.integration.adapter.IntegrationScheduler;
import pt.healthcare.appointments.integration.application.IntegrationWorker;
import pt.healthcare.appointments.integration.domain.IntegrationOperation;

class IntegrationSchedulerTest {
  private final IntegrationWorker worker = mock(IntegrationWorker.class);
  private final IntegrationProperties settings = mock(IntegrationProperties.class);
  private final IntegrationScheduler scheduler = new IntegrationScheduler(worker, settings);

  @Test
  void drainsFullBatchesForEachOperationUntilPartialBatch() {
    when(settings.enabled()).thenReturn(true);
    when(settings.jobsPerPoll()).thenReturn(10);
    for (IntegrationOperation operation : IntegrationOperation.values()) {
      when(worker.runOnce(operation)).thenReturn(10, 10, 3);
    }
    scheduler.pollCalendar();
    scheduler.pollRoom();
    scheduler.pollEmail();
    for (IntegrationOperation operation : IntegrationOperation.values()) {
      verify(worker, times(3)).runOnce(operation);
    }
  }

  @Test
  void disabledSchedulerDoesNotProcessJobs() {
    scheduler.pollCalendar();
    scheduler.pollRoom();
    scheduler.pollEmail();
    verifyNoInteractions(worker);
  }

  @Test
  void shutdownStopsDrainEvenWhenTheLastBatchWasFull() {
    when(settings.enabled()).thenReturn(true);
    when(settings.jobsPerPoll()).thenReturn(10);
    when(worker.runOnce(IntegrationOperation.CALENDAR))
        .thenAnswer(
            _ -> {
              when(worker.isStopping()).thenReturn(true);
              return 10;
            });
    scheduler.pollCalendar();
    scheduler.pollRoom();
    scheduler.pollEmail();
    verify(worker).runOnce(IntegrationOperation.CALENDAR);
    verify(worker, never()).runOnce(IntegrationOperation.ROOM);
    verify(worker, never()).runOnce(IntegrationOperation.EMAIL);
  }

  @Test
  void databaseFailureEndsDrainAndNextPollRetries() {
    when(settings.enabled()).thenReturn(true);
    when(settings.jobsPerPoll()).thenReturn(10);
    when(worker.runOnce(IntegrationOperation.CALENDAR))
        .thenThrow(new IllegalStateException("outage"))
        .thenReturn(0);
    scheduler.pollCalendar();
    scheduler.pollCalendar();
    verify(worker, times(2)).runOnce(IntegrationOperation.CALENDAR);
  }
}
