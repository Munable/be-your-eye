#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR

if (($# < 2)); then
    printf 'usage: %s OUTPUT_DIR SBOM...\n' "$0" >&2
    exit 2
fi
readonly OUTPUT_DIR="$1"
shift
mkdir -p "$OUTPUT_DIR"

OSV_SCANNER=''
failed=false
for sbom in "$@"; do
    test -s "$sbom"
    name="$(basename "$sbom" .cdx.json)"
    report="$OUTPUT_DIR/$name.osv.json"
    component_count="$(
        jq -er '
            if .bomFormat == "CycloneDX" and (.components | type) == "array"
            then .components | length
            else error("invalid CycloneDX component list")
            end
        ' "$sbom"
    )"
    if ((component_count == 0)); then
        jq -n \
            --arg input "$(basename "$sbom")" \
            '{
                results: [],
                be_your_eyes_input: {
                    file: $input,
                    component_count: 0,
                    status: "no_dependency_packages"
                }
            }' > "$report"
        continue
    fi
    if [[ -z "$OSV_SCANNER" ]]; then
        OSV_SCANNER="$(bash "$SCRIPT_DIR/resolve-osv-scanner.sh")"
    fi
    if ! "$OSV_SCANNER" scan -L "$sbom" --format json --output-file "$report"; then
        failed=true
    fi
    if [[ ! -s "$report" ]]; then
        printf 'OSV scanner produced no report for %s\n' "$sbom" >&2
        failed=true
    fi
done

if [[ "$failed" == true ]]; then
    printf 'OSV vulnerability gate failed; inspect %s\n' "$OUTPUT_DIR" >&2
    exit 1
fi
printf 'osv_scanner=%s\n' "${OSV_SCANNER:-not_invoked_no_dependency_packages}"
printf 'report_dir=%s\n' "$OUTPUT_DIR"
