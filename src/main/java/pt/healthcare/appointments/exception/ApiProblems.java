package pt.healthcare.appointments.exception;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Shared error shape for MVC and the security filter chain. */
public final class ApiProblems {
  private ApiProblems() {}

  public static ProblemDetail create(
      HttpStatusCode status, String code, String message, String path) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
    problem.setType(URI.create("about:blank"));
    problem.setProperty("code", code);
    problem.setInstance(URI.create(path));
    return problem;
  }
}
