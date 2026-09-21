#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
WORK_DIR="$(mktemp -d)"
readonly WORK_DIR
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/empty.cdx.json" <<'JSON'
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "components": []
}
JSON

bash "$SCRIPT_DIR/scan-sbom-vulnerabilities.sh" \
    "$WORK_DIR/reports" \
    "$WORK_DIR/empty.cdx.json" \
    > "$WORK_DIR/result.txt"

grep -Fxq 'osv_scanner=not_invoked_no_dependency_packages' "$WORK_DIR/result.txt"
jq -e '
    .results == [] and
    .be_your_eyes_input.component_count == 0 and
    .be_your_eyes_input.status == "no_dependency_packages"
' "$WORK_DIR/reports/empty.osv.json" >/dev/null

printf 'zero-dependency SBOM vulnerability scan test passed\n'
