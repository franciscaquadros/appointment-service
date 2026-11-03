# Dependency vulnerability check

[README](../README.md#dependency-check)

## Run

Run this command from the project root, the directory containing `pom.xml` and `compose.yaml`.

```sh
sh scripts/check-dependencies.sh
```

Requires Java 25, Docker and internet access to Maven Central, GitHub Container Registry and the OSV vulnerability service. No account or API key is required. The script locates the project even when invoked from another directory.

## Scope and policy

The pinned CycloneDX Maven plugin 2.9.1 generates an inventory of the direct, transitive and test dependencies Maven resolves. OSV-Scanner 2.7.0 runs in Docker with that inventory mounted read-only. Using the resolved inventory respects the project's inherited versions and dependency management.

The command exits nonzero on any reported vulnerability, regardless of severity, or on inventory/scanner failure. A network failure does not count as a clean scan. No vulnerability suppressions are configured. Review an advisory's applicability and update affected dependencies before merging.

This checks known vulnerabilities in Maven project dependencies. Build plugins, container operating-system packages and application source security analysis are outside its scope.

## Reports and CI

| Output | Contents |
|---|---|
| `target/dependency-scan/bom.json` | Resolved CycloneDX dependency inventory |
| `target/dependency-scan/osv-results.json` | Scanned packages and OSV findings |

The [script](../scripts/check-dependencies.sh) regenerates both outputs. [GitHub Actions](../.github/workflows/verify.yml) runs the same command in a separate job and retains artifacts for seven days, including failures. Maven tests and the Compose smoke check run independently, so normal verification does not depend on vulnerability-service availability.

## Dependency alignment and validation

`pom.xml` sets `opentelemetry.version` to 1.67.0 through Spring Boot's OpenTelemetry BOM. This keeps API, SDK, context, propagator and exporter components aligned and includes the trace-context/baggage parsing fixes described in the [trace-context advisory](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-ccrj-gw8r-854m) and [baggage advisory](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-rcgg-9c38-7xpx).

The retained scan checked **186 components with zero known vulnerability matches**, including all 13 resolved OpenTelemetry components at 1.67.0. [Validation evidence](dependency-upgrade-validation.json) contains the scan inventory/result hashes, POM/source identity and associated verification results. Each retained result describes its own source snapshot; run the command above for a fresh scan of the current checkout.

Validation covers a clean inventory returning exit 0, reported vulnerabilities returning exit 1, and unavailable vulnerability-service access returning exit 127. These distinguish clean scans from failed checks.

Tool references: [CycloneDX Maven plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/), [OSV usage](https://google.github.io/osv-scanner/usage/), and [OSV output/exit codes](https://google.github.io/osv-scanner/output/).
