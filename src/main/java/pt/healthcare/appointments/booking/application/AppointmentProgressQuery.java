package pt.healthcare.appointments.booking.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Batch lookup: missing follow-up work is pending, never evidence of completion. */
public interface AppointmentProgressQuery {
  Map<UUID, String> findProgress(Set<UUID> appointmentIds);
}
