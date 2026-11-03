package pt.healthcare.appointments;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(
    packages = "pt.healthcare.appointments",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule bookingDoesNotDependOnIntegration =
      noClasses()
          .that()
          .resideInAPackage("..booking..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..integration..");

  @ArchTest
  static final ArchRule controllersUseApplicationServices =
      noClasses()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule servicesDoNotUseDatabaseOrHttpApis =
      noClasses()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..",
              "org.postgresql..",
              "org.springframework.jdbc..",
              "org.springframework.web..",
              "org.springframework.http..");

  @ArchTest
  static final ArchRule servicesDoNotDependOnJdbcImplementations =
      noClasses()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .dependOnClassesThat()
          .haveSimpleNameStartingWith("Jdbc");

  @ArchTest
  static final ArchRule servicesDoNotAcceptWebRequests =
      noClasses()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .dependOnClassesThat()
          .haveSimpleName("BookingRequest");

  @ArchTest
  static final ArchRule policyDoesNotDependOnTransportOrStorage =
      noClasses()
          .that()
          .haveSimpleName("BookingPolicy")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..",
              "org.postgresql..",
              "org.springframework.jdbc..",
              "org.springframework.web..",
              "org.springframework.http..");

  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAnyPackage("..booking.application..", "..integration.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..booking.api..",
              "..booking.persistence..",
              "..integration.api..",
              "..integration.persistence..",
              "..integration.adapter..");

  @ArchTest
  static final ArchRule domainIsIndependent =
      noClasses()
          .that()
          .resideInAPackage("..booking.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "..booking.api..",
              "..booking.application..",
              "..booking.persistence..");
}
