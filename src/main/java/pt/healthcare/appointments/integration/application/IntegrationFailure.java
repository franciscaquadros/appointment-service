package pt.healthcare.appointments.integration.application;

import java.time.Duration;

/** Safe classification only: never persist provider bodies or exception messages. */
public class IntegrationFailure extends RuntimeException {
  private final String code;
  private final boolean retryable;
  private final Duration retryAfter;

  public IntegrationFailure(String code, boolean retryable) {
    this(code, retryable, Duration.ZERO);
  }

  public IntegrationFailure(String code, boolean retryable, Duration retryAfter) {
    super(code);
    this.code = code;
    this.retryable = retryable;
    this.retryAfter = retryAfter;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
