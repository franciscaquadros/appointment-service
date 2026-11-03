package pt.healthcare.appointments.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
public class OpenApiConfiguration {
  private static final String TITLE_PROPERTY = "title";
  private static final String STATUS_PROPERTY = "status";
  private static final String DETAIL_PROPERTY = "detail";
  private static final String INSTANCE_PROPERTY = "instance";

  @Bean
  OpenAPI appointmentApi() {
    var fieldError = new ObjectSchema();
    fieldError.addProperty("field", new StringSchema()).addProperty("message", new StringSchema());
    fieldError.setRequired(List.of("field", "message"));

    var problem = new ObjectSchema();
    problem
        .addProperty("type", new StringSchema().format("uri").example("about:blank"))
        .addProperty(TITLE_PROPERTY, new StringSchema())
        .addProperty(STATUS_PROPERTY, new IntegerSchema())
        .addProperty(DETAIL_PROPERTY, new StringSchema())
        .addProperty(
            INSTANCE_PROPERTY,
            new StringSchema().format("uri-reference").example("/api/v1/appointments"))
        .addProperty("code", new StringSchema())
        .addProperty(
            "fieldErrors",
            new ArraySchema()
                .items(fieldError)
                .description(
                    "Present for request-body field validation failures; rejected values are excluded."));
    problem.setRequired(
        List.of(
            "type", TITLE_PROPERTY, STATUS_PROPERTY, DETAIL_PROPERTY, INSTANCE_PROPERTY, "code"));
    var components =
        new Components()
            .addSchemas("ApiProblem", problem)
            .addSecuritySchemes(
                "adminBasic",
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("basic")
                    .description("Configured administrator credentials; ADMIN role required."))
            .addResponses(
                "BadRequest",
                problem(
                    400,
                    "MALFORMED_REQUEST",
                    "Malformed input, invalid fields/header/cursor, unknown specialty, past start, or outside working hours."))
            .addResponses(
                "Unauthorized",
                problem(
                        401,
                        "AUTHENTICATION_REQUIRED",
                        "Missing/invalid administrator credentials; public booking also rejects supplied invalid credentials.")
                    .addHeaderObject(
                        "WWW-Authenticate",
                        new Header()
                            .schema(new StringSchema())
                            .description("Basic authentication challenge.")))
            .addResponses(
                "Forbidden",
                problem(403, "ACCESS_DENIED", "Authenticated caller does not have the ADMIN role."))
            .addResponses(
                "NotFound",
                problem(
                    404,
                    "APPOINTMENT_NOT_FOUND",
                    "APPOINTMENT_NOT_FOUND: appointment does not exist."))
            .addResponses(
                "Conflict",
                problem(
                    409,
                    "PATIENT_ALREADY_BOOKED",
                    "PATIENT_ALREADY_BOOKED, NO_DOCTOR_AVAILABLE, NO_ROOM_AVAILABLE, or IDEMPOTENCY_MISMATCH."))
            .addResponses(
                "UnsupportedMediaType",
                problem(
                    415,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "UNSUPPORTED_MEDIA_TYPE: booking requires application/json."))
            .addResponses(
                "NotAcceptable",
                problem(
                    406,
                    "NOT_ACCEPTABLE",
                    "NOT_ACCEPTABLE: requested response format is unsupported."))
            .addResponses(
                "InternalError",
                problem(
                    500,
                    "INTERNAL_ERROR",
                    "Unexpected application/storage failure; details are not exposed."))
            .addResponses(
                "RateLimited",
                problem(
                        429,
                        "RATE_LIMITED",
                        "Application instance booking limit exceeded. Retry with the same key and payload.")
                    .addHeaderObject(
                        "Retry-After",
                        new Header()
                            .schema(new StringSchema())
                            .description("Suggested delay in seconds.")))
            .addResponses(
                "Unavailable",
                problem(
                        503,
                        "ALLOCATION_BUSY",
                        "Temporary storage failure or exhausted allocation retries. Retry booking with the same key and payload.")
                    .addHeaderObject(
                        "Retry-After",
                        new Header()
                            .schema(new StringSchema())
                            .description("Suggested delay in seconds.")));
    return new OpenAPI()
        .info(
            new Info()
                .title("Medical appointment API")
                .version("v1")
                .description(
                    "Local booking is confirmed before external processing. Integration progress is reported separately. Public patient email identity is assumed, not verified."))
        .components(components);
  }

  private ApiResponse problem(int status, String code, String description) {
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/problem+json",
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))
                        .example(
                            Map.of(
                                "type",
                                "about:blank",
                                TITLE_PROPERTY,
                                HttpStatus.valueOf(status).getReasonPhrase(),
                                STATUS_PROPERTY,
                                status,
                                DETAIL_PROPERTY,
                                description,
                                INSTANCE_PROPERTY,
                                "/api/v1/appointments",
                                "code",
                                code))));
  }
}
