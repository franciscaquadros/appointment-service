package pt.healthcare.appointments.configuration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("integrations")
public record IntegrationProperties(
    boolean enabled,
    int jobsPerPoll,
    int maximumAttempts,
    Duration initialRetryDelay,
    Duration maximumRetryDelay,
    Duration requestTimeout,
    URI calendarUrl,
    URI roomUrl,
    String calendarToken,
    String roomToken,
    String emailFrom) {
  public IntegrationProperties {
    if (jobsPerPoll < 1 || jobsPerPoll > 100 || maximumAttempts < 1 || maximumAttempts > 100)
      throw new IllegalArgumentException("Invalid integration worker limits");
    for (Duration duration :
        new Duration[] {initialRetryDelay, maximumRetryDelay, requestTimeout}) {
      if (duration == null || duration.isNegative() || duration.isZero())
        throw new IllegalArgumentException("Integration durations must be positive");
    }
    if (maximumRetryDelay.compareTo(initialRetryDelay) < 0)
      throw new IllegalArgumentException(
          "Maximum retry delay must not be less than the initial delay");
    for (URI url : new URI[] {calendarUrl, roomUrl}) {
      if (url == null
          || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
          || url.getHost() == null
          || url.getUserInfo() != null
          || url.getQuery() != null)
        throw new IllegalArgumentException(
            "External URLs must be HTTP(S) without credentials/query");
    }
  }
}
