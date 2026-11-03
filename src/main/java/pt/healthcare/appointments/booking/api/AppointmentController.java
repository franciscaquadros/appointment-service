package pt.healthcare.appointments.booking.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.dto.AppointmentPage;
import pt.healthcare.appointments.booking.dto.AppointmentView;
import pt.healthcare.appointments.booking.dto.BookingRequest;

@Tag(name = "Appointments")
@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
@ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized")
@ApiResponse(responseCode = "406", ref = "#/components/responses/NotAcceptable")
@ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
@ApiResponse(responseCode = "503", ref = "#/components/responses/Unavailable")
@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {
  private final BookingService bookings;

  public AppointmentController(BookingService bookings) {
    this.bookings = bookings;
  }

  @Operation(
      summary = "Book an appointment",
      description =
          """
      Public endpoint. Atomically allocates a qualified doctor and room, confirms the local booking,
      and registers calendar, room, and email work. External processing is asynchronous; failure
      does not cancel the appointment. Repeating the same idempotency key and canonical payload
      returns the original appointment with current progress and HTTP 201, even after its start.
      A different payload with that key returns 409. Duration defaults to 30 minutes.
      """)
  @ApiResponse(
      responseCode = "201",
      description = "Confirmed appointment, including idempotent replay.",
      headers =
          @Header(
              name = "Location",
              description = "Protected appointment detail URL.",
              schema = @Schema(type = "string", format = "uri-reference")),
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = AppointmentView.class)))
  @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
  @ApiResponse(responseCode = "429", ref = "#/components/responses/RateLimited")
  @ApiResponse(responseCode = "415", ref = "#/components/responses/UnsupportedMediaType")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppointmentView> create(
      @Parameter(
              description =
                  "Client-generated UUID. Retain it and the same payload for retries; treat it as a secret recovery token.",
              required = true)
          @RequestHeader("Idempotency-Key")
          UUID requestKey,
      @Valid @RequestBody BookingRequest request) {
    AppointmentView appointment = bookings.book(requestKey, request.toCommand());
    return ResponseEntity.created(URI.create("/api/v1/appointments/" + appointment.id()))
        .body(appointment);
  }

  @Operation(
      summary = "Get an appointment",
      description = "Returns the appointment and current integration progress.")
  @SecurityRequirement(name = "adminBasic")
  @ApiResponse(
      responseCode = "200",
      description = "Appointment found.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = AppointmentView.class)))
  @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
  @GetMapping("/{id}")
  public AppointmentView get(@PathVariable UUID id) {
    return bookings.findAppointment(id);
  }

  @Operation(
      summary = "List appointments",
      description =
          "Ordered by start and ID. Optional from/to timestamps select starts within [from,to). Both require explicit offsets. Pass nextCursor as cursor to continue; filters are preserved inside it. Repeated filters must match. A null nextCursor means no further page.")
  @SecurityRequirement(name = "adminBasic")
  @ApiResponse(
      responseCode = "200",
      description = "A page of appointments; may be empty.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = AppointmentPage.class)))
  @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
  @GetMapping
  public AppointmentPage list(
      @Parameter(description = "Page size.", schema = @Schema(minimum = "1", maximum = "100"))
          @RequestParam(defaultValue = "20")
          int limit,
      @Parameter(description = "Opaque nextCursor from the previous page; omit for the first page.")
          @RequestParam(required = false)
          String cursor,
      @Parameter(description = "Inclusive appointment start lower bound, with explicit UTC offset.")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @Parameter(description = "Exclusive appointment start upper bound, with explicit UTC offset.")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return bookings.listAppointments(limit, cursor, from, to);
  }
}
