package pt.healthcare.appointments.booking.domain;

/** Stable public booking codes. HTTP mapping belongs to the API adapter. */
public enum BookingErrorCode {
  START_IN_PAST,
  OUTSIDE_WORKING_HOURS,
  UNKNOWN_SPECIALTY,
  INVALID_PAGINATION,
  APPOINTMENT_NOT_FOUND,
  PATIENT_ALREADY_BOOKED,
  NO_DOCTOR_AVAILABLE,
  NO_ROOM_AVAILABLE,
  IDEMPOTENCY_MISMATCH,
  ALLOCATION_BUSY,
  BOOKING_INTERRUPTED
}
