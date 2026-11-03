package pt.healthcare.appointments.booking.application;

import java.util.UUID;

/** Register durable follow-up work inside the caller's existing booking transaction. */
public interface PostBookingActions {
  void register(UUID appointmentId);
}
