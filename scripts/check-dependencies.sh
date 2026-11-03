#!/bin/sh
# Same dependency vulnerability check locally and in GitHub Actions.
set -eu

project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"
scan_directory="$project_root/target/dependency-scan"
mkdir -p "$scan_directory"
# A failed run must not leave an earlier successful report behind.
rm -f "$scan_directory/bom.json" "$scan_directory/osv-results.json"

./mvnw --batch-mode org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
  -DincludeTestScope=true -DoutputFormat=json \
  -DoutputDirectory="$scan_directory" -Dcyclonedx.skipAttach=true

echo 'Writing dependency scan results to target/dependency-scan/osv-results.json'
scan_status=0
docker run --rm \
  --mount "type=bind,src=$scan_directory,dst=/workspace,readonly" \
  ghcr.io/google/osv-scanner:v2.7.0 scan source \
  -L /workspace/bom.json --format=json --all-packages \
  > "$scan_directory/osv-results.json" || scan_status=$?
case "$scan_status" in
  0) echo 'Dependency scan passed: no known vulnerabilities reported.' ;;
  1) echo 'Known vulnerabilities found. Review target/dependency-scan/osv-results.json' >&2 ;;
  *) echo "Dependency scan could not complete (exit $scan_status). Review the logs and report." >&2 ;;
esac
exit "$scan_status"
