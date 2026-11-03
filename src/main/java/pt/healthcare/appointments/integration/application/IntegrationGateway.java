package pt.healthcare.appointments.integration.application;

import pt.healthcare.appointments.integration.dto.IntegrationJob;

public interface IntegrationGateway {
  void execute(IntegrationJob job);
}
