package pt.healthcare.appointments.booking.application;

/** Another booking won a resource allocation race; retry in a fresh transaction. */
public class AllocationConflictException extends RuntimeException {
  public AllocationConflictException(Throwable cause) {
    super("Concurrent resource allocation conflict", cause);
  }
}
