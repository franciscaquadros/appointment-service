package pt.healthcare.appointments.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Carries only W3C trace identifiers across the database queue, never request baggage. */
@Component
public class JobTracing {
  private final Tracer tracer;
  private final Propagator propagator;

  public JobTracing(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators) {
    tracer = tracers.getIfAvailable(() -> Tracer.NOOP);
    propagator = propagators.getIfAvailable(() -> Propagator.NOOP);
  }

  public String currentTraceparent() {
    Span span = tracer.currentSpan();
    if (span == null) return null;
    Map<String, String> headers = new HashMap<>();
    propagator.inject(span.context(), headers, Map::put);
    return headers.get("traceparent");
  }

  public void process(String traceparent, String operation, Runnable action) {
    Map<String, String> headers =
        traceparent == null ? Map.of() : Map.of("traceparent", traceparent);
    Span span =
        propagator
            .extract(headers, Map::get)
            .name("integration." + operation.toLowerCase(Locale.ROOT))
            .start();
    try (var _ = tracer.withSpan(span)) {
      action.run();
    } catch (RuntimeException failure) {
      // Exception messages can contain SQL/provider payloads; retain only a safe classification.
      span.error(new IllegalStateException(failure.getClass().getSimpleName()));
      throw failure;
    } finally {
      span.end();
    }
  }

  public void markFailure(String code) {
    Span span = tracer.currentSpan();
    if (span != null) span.error(new IllegalStateException(code));
  }
}
