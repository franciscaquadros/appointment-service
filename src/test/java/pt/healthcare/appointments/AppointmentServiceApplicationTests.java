package pt.healthcare.appointments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pt.healthcare.appointments.booking.application.AllocationConflictException;
import pt.healthcare.appointments.booking.application.AppointmentProgressQuery;
import pt.healthcare.appointments.booking.application.AppointmentRepository;
import pt.healthcare.appointments.booking.application.BookingService;
import pt.healthcare.appointments.booking.application.PostBookingActions;
import pt.healthcare.appointments.booking.domain.BookingErrorCode;
import pt.healthcare.appointments.booking.domain.BookingException;
import pt.healthcare.appointments.booking.domain.BookingPolicy;
import pt.healthcare.appointments.booking.dto.AppointmentView;
import pt.healthcare.appointments.booking.dto.BookAppointmentCommand;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "integrations.enabled=false")
@ActiveProfiles("demo")
class AppointmentServiceApplicationTests {
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17.6-alpine");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }

  @org.springframework.boot.test.web.server.LocalServerPort int port;
  @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
  @Autowired BookingService bookings;
  @Autowired AppointmentRepository appointmentRepository;
  @Autowired PostBookingActions postBookingActions;
  @Autowired AppointmentProgressQuery progressQuery;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clearBookings() {
    jdbc.sql("TRUNCATE integration_job,idempotency_request,appointment,patient CASCADE").update();
  }

  private BookAppointmentCommand request(String email, int hour, int minute) {
    OffsetDateTime start =
        LocalDate.now(ZoneId.of("Europe/Lisbon"))
            .plusDays(2)
            .atTime(hour, minute)
            .atZone(ZoneId.of("Europe/Lisbon"))
            .toOffsetDateTime();
    return new BookAppointmentCommand("Ana Silva", email, "CARDIOLOGY", start);
  }

  @ParameterizedTest
  @ValueSource(strings = {"doctor", "room"})
  void allocationSkipsInactiveResources(String resource) {
    UUID inactive =
        jdbc.sql("SELECT id FROM " + resource + " ORDER BY id LIMIT 1").query(UUID.class).single();
    UUID expected =
        jdbc.sql("SELECT id FROM " + resource + " WHERE id<>:id ORDER BY id LIMIT 1")
            .param("id", inactive)
            .query(UUID.class)
            .single();
    try {
      jdbc.sql("UPDATE " + resource + " SET active=false WHERE id=:id")
          .param("id", inactive)
          .update();
      var booked = bookings.book(UUID.randomUUID(), request("active@example.com", 10, 0));
      assertThat(
              jdbc.sql("SELECT " + resource + "_id FROM appointment WHERE id=:id")
                  .param("id", booked.id())
                  .query(UUID.class)
                  .single())
          .isEqualTo(expected);
    } finally {
      jdbc.sql("UPDATE " + resource + " SET active=true WHERE id=:id")
          .param("id", inactive)
          .update();
    }
  }

  @ParameterizedTest
  @CsvSource({"doctor,NO_DOCTOR_AVAILABLE", "room,NO_ROOM_AVAILABLE"})
  void allResourcesInactiveRejectsBookingAndRollsBack(String resource, BookingErrorCode code) {
    try {
      jdbc.sql("UPDATE " + resource + " SET active=false").update();
      assertThatThrownBy(
              () -> bookings.book(UUID.randomUUID(), request("inactive@example.com", 10, 0)))
          .isInstanceOfSatisfying(
              BookingException.class, failure -> assertThat(failure.code()).isEqualTo(code));
      assertNoBookingRows();
    } finally {
      jdbc.sql("UPDATE " + resource + " SET active=true").update();
    }
  }

  private void assertNoBookingRows() {
    for (String table :
        List.of("appointment", "integration_job", "patient", "idempotency_request")) {
      assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single())
          .as("Rows in %s", table)
          .isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-30, 0, 15, 30})
  void availabilityUsesHalfOpenIntervals(int offsetMinutes) {
    var command = request("range@example.com", 10, 0);
    UUID appointment = bookings.book(UUID.randomUUID(), command).id();
    record Resources(UUID patient, UUID doctor, UUID room, UUID specialty, OffsetDateTime start) {}
    var ids =
        jdbc.sql(
                "SELECT patient_id,doctor_id,room_id,specialty_id,starts_at FROM appointment WHERE id=:id")
            .param("id", appointment)
            .query(
                (row, _) ->
                    new Resources(
                        row.getObject("patient_id", UUID.class),
                        row.getObject("doctor_id", UUID.class),
                        row.getObject("room_id", UUID.class),
                        row.getObject("specialty_id", UUID.class),
                        row.getObject("starts_at", OffsetDateTime.class)))
            .single();
    var start = ids.start().plusMinutes(offsetMinutes);
    var end = start.plusMinutes(30);
    boolean overlaps = offsetMinutes > -30 && offsetMinutes < 30;
    transaction.executeWithoutResult(
        _ -> {
          assertThat(appointmentRepository.patientHasOverlap(ids.patient(), start, end))
              .isEqualTo(overlaps);
          var doctor = appointmentRepository.findAvailableDoctor(ids.specialty(), start, end);
          var room = appointmentRepository.findAvailableRoom(start, end);
          if (overlaps) {
            assertThat(doctor.orElse(null)).isNotEqualTo(ids.doctor());
            assertThat(room.orElse(null)).isNotEqualTo(ids.room());
          } else {
            assertThat(doctor).contains(ids.doctor());
            assertThat(room).contains(ids.room());
          }
        });
  }

  @Test
  void openApiContractDocumentsActualEndpointsSecurityAndErrors() throws Exception {
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      String authorization =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      "demo-admin:demo-admin-only"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String root = "http://localhost:" + port;
      for (String path : List.of("/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui/index.html")) {
        var anonymous =
            client.send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var authenticated =
            client.send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + path))
                    .header("Authorization", authorization)
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(authenticated.statusCode()).isEqualTo(200);
        if (path.endsWith(".yaml"))
          assertThat(authenticated.body()).contains("openapi:", "adminBasic:");
        if (path.endsWith(".html")) assertThat(authenticated.body()).contains("Swagger UI");
      }
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(URI.create(root + "/v3/api-docs"))
                  .header("Authorization", authorization)
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      var document = com.jayway.jsonpath.JsonPath.parse(response.body());
      assertThat(document.<Map<String, Object>>read("$.paths"))
          .containsOnlyKeys("/api/v1/appointments", "/api/v1/appointments/{id}");
      assertThat(document.<String>read("$.components.securitySchemes.adminBasic.type"))
          .isEqualTo("http");
      assertThat(document.<String>read("$.components.securitySchemes.adminBasic.scheme"))
          .isEqualTo("basic");
      assertThat(document.<Map<String, Object>>read("$.paths['/api/v1/appointments'].post"))
          .doesNotContainKey("security");
      assertThat(
              document.<List<Object>>read(
                  "$.paths['/api/v1/appointments'].get.security[0].adminBasic"))
          .isEmpty();
      assertThat(
              document.<List<Object>>read(
                  "$.paths['/api/v1/appointments/{id}'].get.security[0].adminBasic"))
          .isEmpty();
      assertThat(
              document.<List<Map<String, Object>>>read(
                  "$.paths['/api/v1/appointments'].post.parameters"))
          .anySatisfy(
              parameter -> {
                assertThat(parameter)
                    .containsEntry("name", "Idempotency-Key")
                    .containsEntry("in", "header")
                    .containsEntry("required", true);
                assertThat(com.jayway.jsonpath.JsonPath.<String>read(parameter, "$.schema.format"))
                    .isEqualTo("uuid");
              });
      assertThat(
              document.<List<Map<String, Object>>>read(
                  "$.paths['/api/v1/appointments'].get.parameters"))
          .extracting(parameter -> parameter.get("name"))
          .containsExactlyInAnyOrder("limit", "cursor", "from", "to");
      assertThat(
              document.<String>read(
                  "$.paths['/api/v1/appointments'].get.responses['200'].content['application/json'].schema['$ref']"))
          .endsWith("/AppointmentPage");
      assertThat(document.<List<String>>read("$.components.schemas.AppointmentPage.required"))
          .containsExactlyInAnyOrder("items", "nextCursor");
      assertThat(document.<List<String>>read("$.components.schemas.BookingRequest.required"))
          .containsExactlyInAnyOrder("patientName", "patientEmail", "specialtyCode", "startsAt");
      assertThat(
              document.<Integer>read(
                  "$.components.schemas.BookingRequest.properties.patientName.maxLength"))
          .isEqualTo(160);
      assertThat(
              document.<Integer>read(
                  "$.components.schemas.BookingRequest.properties.patientEmail.maxLength"))
          .isEqualTo(254);
      assertThat(
              document.<String>read(
                  "$.components.schemas.BookingRequest.properties.startsAt.format"))
          .isEqualTo("date-time");
      assertThat(
              document.<Map<String, Object>>read("$.paths['/api/v1/appointments'].post.responses"))
          .containsKeys("201", "400", "401", "409", "415", "429", "500", "503");
      assertThat(
              document.<Map<String, Object>>read(
                  "$.paths['/api/v1/appointments'].post.responses['201'].headers"))
          .containsKey("Location");
      assertThat(document.<Map<String, Object>>read("$.components.responses.Unavailable.headers"))
          .containsKey("Retry-After");
      assertThat(document.<Map<String, Object>>read("$.components.responses.RateLimited.headers"))
          .containsKey("Retry-After");
      assertThat(document.<Map<String, Object>>read("$.components.responses.Unauthorized.headers"))
          .containsKey("WWW-Authenticate");
      assertThat(
              document.<String>read(
                  "$.components.responses.Conflict.content['application/problem+json'].schema['$ref']"))
          .isEqualTo("#/components/schemas/ApiProblem");
      assertThat(
              document.<Integer>read(
                  "$.components.responses.Unauthorized.content['application/problem+json'].example.status"))
          .isEqualTo(401);
      assertThat(document.<List<String>>read("$.components.schemas.ApiProblem.required"))
          .contains("type", "title", "status", "detail", "instance", "code");
      assertThat(document.<Map<String, Object>>read("$.components.schemas.ApiProblem.properties"))
          .containsKey("fieldErrors");
    }
  }

  @Test
  void filteredListingPaginatesEqualStartTimesAndPreservesRange() {
    bookings.book(UUID.randomUUID(), request("before@example.com", 9, 30));
    var first = bookings.book(UUID.randomUUID(), request("one@example.com", 10, 0));
    var second = bookings.book(UUID.randomUUID(), request("two@example.com", 10, 0));
    bookings.book(UUID.randomUUID(), request("end@example.com", 11, 0));
    var from = first.startsAt();
    var to = from.plusHours(1);
    var page = bookings.listAppointments(1, null, from, to);
    assertThat(page.items()).hasSize(1);
    assertThat(page.nextCursor()).matches("[A-Za-z0-9_-]+");
    var next = bookings.listAppointments(1, page.nextCursor(), null, null);
    assertThat(next.items()).hasSize(1);
    assertThat(next.nextCursor()).isNull();
    assertThat(List.of(page.items().getFirst().id(), next.items().getFirst().id()))
        .containsExactlyInAnyOrder(first.id(), second.id())
        .doesNotHaveDuplicates();
    var full = bookings.listAppointments(2, null, from, to);
    assertThat(full.items())
        .containsExactlyElementsOf(
            java.util.stream.Stream.concat(page.items().stream(), next.items().stream()).toList());
    assertThat(full.nextCursor()).isNull();
    // Equivalent offsets are accepted; changing the date window is not.
    assertThat(
            bookings
                .listAppointments(
                    1, page.nextCursor(), from.withOffsetSameInstant(ZoneOffset.ofHours(1)), to)
                .items())
        .isEqualTo(next.items());
    assertThatThrownBy(
            () -> bookings.listAppointments(1, page.nextCursor(), from.plusMinutes(1), to))
        .isInstanceOf(BookingException.class);
    assertThat(bookings.listAppointments(20, null, to, null).items()).hasSize(1);
    assertThat(bookings.listAppointments(20, null, null, from).items()).hasSize(1);
    var empty = bookings.listAppointments(20, null, to.plusHours(1), null);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isNull();
  }

  private java.net.http.HttpResponse<String> listingResponse(
      java.net.http.HttpClient client, String query) throws Exception {
    String auth =
        Base64.getEncoder()
            .encodeToString(
                "demo-admin:demo-admin-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return client.send(
        java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/appointments?" + query))
            .header("Authorization", "Basic " + auth)
            .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void listingHttpContractUsesOpaqueCursorAndValidatesFilters() throws Exception {
    var first = bookings.book(UUID.randomUUID(), request("http-one@example.com", 10, 0));
    var second = bookings.book(UUID.randomUUID(), request("http-two@example.com", 10, 0));
    bookings.book(UUID.randomUUID(), request("http-end@example.com", 11, 0));
    String from =
        java.net.URLEncoder.encode(
            first.startsAt().toString(), java.nio.charset.StandardCharsets.UTF_8);
    String to =
        java.net.URLEncoder.encode(
            first.startsAt().plusHours(1).toString(), java.nio.charset.StandardCharsets.UTF_8);
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response = listingResponse(client, "limit=1&from=" + from + "&to=" + to);
      assertThat(response.statusCode()).isEqualTo(200);
      var page = com.jayway.jsonpath.JsonPath.parse(response.body());
      String cursor = page.read("$.nextCursor");
      assertThat(cursor).matches("[A-Za-z0-9_-]+");
      assertThat(page.<List<Object>>read("$.items")).hasSize(1);
      String id = page.read("$.items[0].id");
      var nextResponse = listingResponse(client, "limit=1&cursor=" + cursor);
      assertThat(nextResponse.statusCode()).isEqualTo(200);
      var next = com.jayway.jsonpath.JsonPath.parse(nextResponse.body());
      assertThat(next.<List<Object>>read("$.items")).hasSize(1);
      assertThat(next.<Object>read("$.nextCursor")).isNull();
      assertThat(List.of(id, next.<String>read("$.items[0].id")))
          .containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
      for (String query :
          List.of(
              "cursor=invalid",
              "from=" + from + "&to=" + from,
              "from=2030-01-01T10:00:00",
              "cursor=" + cursor + "&to=" + from)) {
        var invalid = listingResponse(client, query);
        assertThat(invalid.statusCode()).isEqualTo(400);
      }
    }
  }

  @Test
  void createsBookingAndThreeJobsAtomically() {
    AppointmentView created = bookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0));
    assertThat(created.integrationStatus()).isEqualTo("PENDING");
    assertThat(created.endsAt().toInstant())
        .isEqualTo(created.startsAt().toInstant().plusSeconds(1800));
    assertThat(jdbc.sql("SELECT count(*) FROM integration_job").query(Integer.class).single())
        .isEqualTo(3);
    assertThat(bookings.listAppointments(20, null, null, null).items()).hasSize(1);
  }

  @Test
  void failedFollowUpRegistrationRollsBackAppointmentPatientJobsAndRequest() {
    PostBookingActions failingActions = mock(PostBookingActions.class);
    doAnswer(
            invocation -> {
              postBookingActions.register(invocation.getArgument(0));
              assertThat(
                      jdbc.sql("SELECT count(*) FROM integration_job")
                          .query(Integer.class)
                          .single())
                  .isEqualTo(3);
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "Simulated registration failure");
            })
        .when(failingActions)
        .register(any());
    var failingBookings =
        new BookingService(
            appointmentRepository, failingActions, progressQuery, transaction, policyForTest());
    assertThatThrownBy(
            () -> failingBookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0)))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Integer.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM patient").query(Integer.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM integration_job").query(Integer.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM idempotency_request").query(Integer.class).single())
        .isZero();
  }

  @Test
  void followUpRegistrationRequiresTheBookingTransaction() {
    assertThatThrownBy(() -> postBookingActions.register(UUID.randomUUID()))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void progressLookupDerivesDifferentStatesForTheSamePage() {
    UUID completed = bookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0)).id();
    UUID failed = bookings.book(UUID.randomUUID(), request("bruno@example.com", 11, 0)).id();
    jdbc.sql("UPDATE integration_job SET status='SUCCEEDED' WHERE appointment_id=:id")
        .param("id", completed)
        .update();
    jdbc.sql(
            "UPDATE integration_job SET status='FAILED' WHERE appointment_id=:id AND operation='ROOM'")
        .param("id", failed)
        .update();
    assertThat(bookings.listAppointments(20, null, null, null).items())
        .extracting(AppointmentView::integrationStatus)
        .containsExactly("COMPLETED", "ATTENTION_REQUIRED");
    assertThat(progressQuery.findProgress(Set.of())).isEmpty();
  }

  @Test
  void reusesPatientWithoutOverwritingNameAndAllowsAdjacentBooking() {
    bookings.book(UUID.randomUUID(), request("Ana@example.com", 10, 0));
    BookAppointmentCommand second = request("ana@example.com", 10, 30);
    AppointmentView next =
        bookings.book(
            UUID.randomUUID(),
            new BookAppointmentCommand(
                "Different Name",
                second.patientEmail(),
                second.specialtyCode(),
                second.startsAt()));
    assertThat(next.patientName()).isEqualTo("Ana Silva");
    assertThat(jdbc.sql("SELECT count(*) FROM patient").query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void rejectsPatientOverlapAndRollsBackIdempotencyClaim() {
    bookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0));
    assertThatThrownBy(() -> bookings.book(UUID.randomUUID(), request("ANA@example.com", 10, 15)))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(BookingErrorCode.PATIENT_ALREADY_BOOKED));
    assertThat(jdbc.sql("SELECT count(*) FROM idempotency_request").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void replaysSameBookingWithCurrentIntegrationStatus() {
    UUID key = UUID.randomUUID();
    BookAppointmentCommand request = request("ana@example.com", 10, 0);
    AppointmentView first = bookings.book(key, request);
    jdbc.sql("UPDATE integration_job SET status='SUCCEEDED'").update();
    AppointmentView replay = bookings.book(key, request);
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.integrationStatus()).isEqualTo("COMPLETED");
    assertThatThrownBy(() -> bookings.book(key, request("other@example.com", 10, 0)))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.code()).isEqualTo(BookingErrorCode.IDEMPOTENCY_MISMATCH));
  }

  @RepeatedTest(5)
  void concurrentBookingsNeverOverbookAvailablePairs() throws Exception {
    List<Outcome> outcomes =
        race(
            8,
            index ->
                bookings.book(
                    UUID.randomUUID(), request("patient" + index + "@example.com", 10, 0)));
    assertThat(outcomes.stream().filter(result -> result.appointment() != null)).hasSize(2);
    assertThat(
            outcomes.stream()
                .filter(result -> result.failure() != null)
                .map(result -> result.failure().code()))
        .allMatch(
            code ->
                Set.of(BookingErrorCode.NO_DOCTOR_AVAILABLE, BookingErrorCode.NO_ROOM_AVAILABLE)
                    .contains(code));
    assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Integer.class).single())
        .isEqualTo(2);
  }

  @Test
  void concurrentIdenticalKeysCreateOnlyOneBooking() throws Exception {
    UUID key = UUID.randomUUID();
    List<Outcome> outcomes =
        race(8, index -> bookings.book(key, request("ana@example.com", 10, 0)));
    assertThat(outcomes).allMatch(result -> result.failure() == null);
    assertThat(outcomes.stream().map(result -> result.appointment().id()).distinct()).hasSize(1);
    assertThat(jdbc.sql("SELECT count(*) FROM integration_job").query(Integer.class).single())
        .isEqualTo(3);
  }

  @Test
  void concurrentDistinctKeysForSamePatientCannotOverlap() throws Exception {
    List<Outcome> outcomes =
        race(8, index -> bookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0)));
    assertThat(outcomes.stream().filter(result -> result.appointment() != null)).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(result -> result.failure() != null)
                .map(result -> result.failure().code()))
        .containsOnly(BookingErrorCode.PATIENT_ALREADY_BOOKED);
    assertThat(jdbc.sql("SELECT count(*) FROM patient").query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void replayAfterStartStillWorksAndDoesNotRecalculateDuration() {
    UUID key = UUID.randomUUID();
    BookAppointmentCommand request = request("ana@example.com", 10, 0);
    AppointmentView first = bookings.book(key, request);
    BookingPolicy laterPolicy =
        new BookingPolicy(
            Clock.fixed(request.startsAt().toInstant().plusSeconds(3600), ZoneOffset.UTC),
            Duration.ofMinutes(45),
            "08:00",
            "20:00");
    BookingService laterService =
        new BookingService(
            appointmentRepository, postBookingActions, progressQuery, transaction, laterPolicy);
    AppointmentView replay = laterService.book(key, request);
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.endsAt()).isEqualTo(first.endsAt());
  }

  @Test
  void multiSpecialtyDoctorCannotOverlapAcrossSpecialties() {
    BookAppointmentCommand cardiology = request("ana@example.com", 10, 0);
    bookings.book(UUID.randomUUID(), cardiology);
    assertThatThrownBy(
            () ->
                bookings.book(
                    UUID.randomUUID(),
                    new BookAppointmentCommand(
                        "Other Patient",
                        "other@example.com",
                        "DERMATOLOGY",
                        cardiology.startsAt())))
        .isInstanceOfSatisfying(
            BookingException.class,
            failure -> assertThat(failure.code()).isEqualTo(BookingErrorCode.NO_DOCTOR_AVAILABLE));
    AppointmentView later =
        bookings.book(
            UUID.randomUUID(),
            new BookAppointmentCommand(
                "Other Patient",
                "other@example.com",
                "DERMATOLOGY",
                cardiology.startsAt().plusMinutes(30)));
    assertThat(later.doctor()).isEqualTo("Dra. Silva");
  }

  @Test
  void listingRequiresAuthentication() throws Exception {
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      var uri = java.net.URI.create("http://localhost:" + port + "/api/v1/appointments");
      var anonymous =
          client.send(
              java.net.http.HttpRequest.newBuilder(uri).GET().build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(anonymous.statusCode()).isEqualTo(401);
      String credentials =
          Base64.getEncoder()
              .encodeToString(
                  "demo-admin:demo-admin-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var authenticated =
          client.send(
              java.net.http.HttpRequest.newBuilder(uri)
                  .header("Authorization", "Basic " + credentials)
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(authenticated.statusCode()).isEqualTo(200);
      var page = com.jayway.jsonpath.JsonPath.parse(authenticated.body());
      assertThat(page.<List<Object>>read("$.items")).isEmpty();
      assertThat(page.<Object>read("$.nextCursor")).isNull();
    }
  }

  @Test
  void publicBookingMapsRequestsAndPatientConflictsToHttp() throws Exception {
    BookAppointmentCommand command = request("ana@example.com", 10, 0);
    String body =
        """
        {"patientName":"Ana Silva","patientEmail":"ana@example.com",
         "specialtyCode":"CARDIOLOGY","startsAt":"%s"}
        """
            .formatted(command.startsAt());
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      var endpoint = java.net.URI.create("http://localhost:" + port + "/api/v1/appointments");
      var firstRequest =
          java.net.http.HttpRequest.newBuilder(endpoint)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
              .build();
      var created = client.send(firstRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(created.statusCode()).isEqualTo(201);
      assertThat(created.headers().firstValue("Location")).isPresent();
      var conflictingRequest =
          java.net.http.HttpRequest.newBuilder(endpoint)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
              .build();
      var conflict =
          client.send(conflictingRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(conflict.statusCode()).isEqualTo(409);
      assertThat(conflict.body()).contains("PATIENT_ALREADY_BOOKED");
    }
  }

  @Test
  void unknownSpecialtyReturnsHttpProblemWithoutPersistingBooking() throws Exception {
    String body =
        """
        {"patientName":"Ana Silva","patientEmail":"unknown@example.com",
         "specialtyCode":"UNKNOWN","startsAt":"%s"}
        """
            .formatted(request("unknown@example.com", 10, 0).startsAt());
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/api/v1/appointments"))
                  .header("Content-Type", "application/json")
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValueSatisfying(
              contentType -> assertThat(contentType).startsWith("application/problem+json"));
      var problem = com.jayway.jsonpath.JsonPath.parse(response.body());
      assertThat(problem.<String>read("$.code")).isEqualTo("UNKNOWN_SPECIALTY");
      assertThat(problem.<Integer>read("$.status")).isEqualTo(400);
      assertNoBookingRows();
    }
  }

  @Test
  void retriesExclusionTimeoutInFreshTransaction() throws Exception {
    CountDownLatch resourceReserved = new CountDownLatch(1);
    CountDownLatch releaseReservation = new CountDownLatch(1);
    CountDownLatch reservationCommitted = new CountDownLatch(1);
    AtomicBoolean timeoutObserved = new AtomicBoolean();
    BookAppointmentCommand competingRequest = request("contender@example.com", 10, 0);
    OffsetDateTime start = competingRequest.startsAt();
    OffsetDateTime end = start.plusMinutes(30);

    AppointmentRepository timeoutRepository =
        mock(AppointmentRepository.class, delegatesTo(appointmentRepository));
    doAnswer(
            invocation -> {
              // A short deadline makes the real PostgreSQL wait deterministic without timing
              // sleeps.
              jdbc.sql("SET LOCAL statement_timeout = '100ms'").update();
              try {
                appointmentRepository.saveAppointment(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    invocation.getArgument(6));
                return null;
              } catch (QueryTimeoutException timeout) {
                timeoutObserved.set(true);
                releaseReservation.countDown();
                assertThat(reservationCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                throw timeout;
              }
            })
        .when(timeoutRepository)
        .saveAppointment(any(), any(), any(), any(), any(), any(), any());
    BookingService contender =
        new BookingService(
            timeoutRepository, postBookingActions, progressQuery, transaction, policyForTest());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> reservation =
          executor.submit(
              () -> {
                transaction.executeWithoutResult(
                    status -> {
                      UUID specialtyId =
                          appointmentRepository.findSpecialty("CARDIOLOGY").orElseThrow();
                      UUID patientId =
                          appointmentRepository.findOrCreatePatient(
                              request("holder@example.com", 10, 0));
                      // Simulate an independent writer bypassing resource-allocation locks.
                      UUID doctorId = UUID.fromString("10000000-0000-0000-0000-000000000001");
                      UUID roomId = UUID.fromString("20000000-0000-0000-0000-000000000001");
                      UUID appointmentId = UUID.randomUUID();
                      appointmentRepository.saveAppointment(
                          appointmentId, patientId, specialtyId, doctorId, roomId, start, end);
                      postBookingActions.register(appointmentId);
                      resourceReserved.countDown();
                      try {
                        if (!releaseReservation.await(10, TimeUnit.SECONDS))
                          throw new IllegalStateException("Reservation was not released");
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                      }
                    });
                reservationCommitted.countDown();
              });
      try {
        assertThat(resourceReserved.await(10, TimeUnit.SECONDS)).isTrue();
        AppointmentView booked = contender.book(UUID.randomUUID(), competingRequest);
        assertThat(timeoutObserved).isTrue();
        assertThat(booked.doctor()).isEqualTo("Dr. Costa");
        assertThat(booked.room()).isEqualTo("Room 2");
        reservation.get(10, TimeUnit.SECONDS);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Integer.class).single())
            .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM integration_job").query(Integer.class).single())
            .isEqualTo(6);
        assertThat(
                jdbc.sql("SELECT count(*) FROM idempotency_request").query(Integer.class).single())
            .isEqualTo(1);
      } finally {
        releaseReservation.countDown();
      }
    }
  }

  private BookingPolicy policyForTest() {
    return new BookingPolicy(Clock.systemUTC(), Duration.ofMinutes(30), "08:00", "20:00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"doctor", "room", "patient"})
  void translatesActualDatabaseOverlapConstraints(String conflictingResource) {
    AppointmentView existing = bookings.book(UUID.randomUUID(), request("ana@example.com", 10, 0));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      UUID patientId =
                          conflictingResource.equals("patient")
                              ? appointmentRepository.findOrCreatePatient(
                                  request("ana@example.com", 10, 0))
                              : appointmentRepository.findOrCreatePatient(
                                  request("other@example.com", 10, 0));
                      UUID doctorId =
                          UUID.fromString(
                              conflictingResource.equals("doctor")
                                  ? "10000000-0000-0000-0000-000000000001"
                                  : "10000000-0000-0000-0000-000000000002");
                      UUID roomId =
                          UUID.fromString(
                              conflictingResource.equals("room")
                                  ? "20000000-0000-0000-0000-000000000001"
                                  : "20000000-0000-0000-0000-000000000002");
                      UUID specialtyId =
                          appointmentRepository.findSpecialty("CARDIOLOGY").orElseThrow();
                      // Bypass selection deliberately: verify database violations are translated
                      // correctly.
                      appointmentRepository.saveAppointment(
                          UUID.randomUUID(),
                          patientId,
                          specialtyId,
                          doctorId,
                          roomId,
                          existing.startsAt(),
                          existing.endsAt());
                    }))
        .satisfies(
            failure -> {
              if (conflictingResource.equals("patient")) {
                assertThat(failure)
                    .isInstanceOfSatisfying(
                        BookingException.class,
                        bookingFailure ->
                            assertThat(bookingFailure.code())
                                .isEqualTo(BookingErrorCode.PATIENT_ALREADY_BOOKED));
              } else {
                assertThat(failure).isInstanceOf(AllocationConflictException.class);
              }
            });
    assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Integer.class).single())
        .isEqualTo(1);
  }

  private record Outcome(AppointmentView appointment, BookingException failure) {}

  private List<Outcome> race(int count, java.util.function.IntFunction<AppointmentView> action)
      throws Exception {
    CyclicBarrier start = new CyclicBarrier(count);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures =
          IntStream.range(0, count)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            start.await(10, TimeUnit.SECONDS);
                            try {
                              return new Outcome(action.apply(index), null);
                            } catch (BookingException failure) {
                              return new Outcome(null, failure);
                            }
                          }))
              .toList();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
      return outcomes;
    }
  }
}
