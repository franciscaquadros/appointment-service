package pt.healthcare.appointments.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@NullMarked
// Spring Security rejects a request before it reaches the controller
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ObjectMapper objectMapper;

  public SecurityProblemHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"appointments\"");
    write(
        response,
        ApiProblems.create(
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "Valid administrator credentials are required",
            request.getRequestURI()));
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
      throws IOException {
    write(
        response,
        ApiProblems.create(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "You do not have permission to access this resource",
            request.getRequestURI()));
  }

  public void rateLimited(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, "1");
    write(
        response,
        ApiProblems.create(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMITED",
            "Booking request limit exceeded. Retry with the same idempotency key and payload.",
            request.getRequestURI()));
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

    // Security runs outside MVC.
    var body = new LinkedHashMap<String, @Nullable Object>();
    body.put("type", problem.getType());
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    body.put("detail", problem.getDetail());
    body.put("instance", problem.getInstance());
    Map<String, @Nullable Object> properties = problem.getProperties();
    if (properties != null) {
      body.putAll(properties);
    }
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
