#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly RELEASE_INPUT="$REPO_ROOT/model-tools/v3/releases/current-internal/templates/release-build-input.json"
RELEASE_INPUT_ROOT="$(dirname -- "$RELEASE_INPUT")"
readonly RELEASE_INPUT_ROOT
readonly SOURCE_RELEASE_DIR="${CURRENT_INTERNAL_RELEASE_DIR:-$REPO_ROOT/.local/releases/internal-2026.09.15.1}"
readonly OUTPUT_ROOT="${1:-$REPO_ROOT/.local/functional-fixtures/2026.09.15.1}"

sha256_value() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

materialize_exact() {
    local source="$1"
    local expected_sha="$2"
    local expected_size="$3"
    local destination="$4"
    if [[ -f "$destination" ]] &&
        [[ "$(sha256_value "$destination")" == "$expected_sha" ]] &&
        { [[ -z "$expected_size" ]] ||
            [[ "$(wc -c < "$destination" | tr -d ' ')" == "$expected_size" ]]; }; then
        return
    fi
    [[ -f "$source" ]] || return 1
    [[ "$(sha256_value "$source")" == "$expected_sha" ]] || return 1
    [[ -z "$expected_size" ]] ||
        [[ "$(wc -c < "$source" | tr -d ' ')" == "$expected_size" ]] || return 1
    mkdir -p "$(dirname -- "$destination")"
    local temporary="$destination.copy"
    rm -f "$temporary"
    ln "$source" "$temporary" 2>/dev/null || cp "$source" "$temporary"
    if [[ -n "$expected_size" ]] &&
        [[ "$(wc -c < "$temporary" | tr -d ' ')" != "$expected_size" ]]; then
        rm -f "$temporary"
        return 1
    fi
    if [[ "$(sha256_value "$temporary")" != "$expected_sha" ]]; then
        rm -f "$temporary"
        return 1
    fi
    mv "$temporary" "$destination"
}

for path in catalog.json release.json checksums.json \
    manifests/similarity_mediapipe_mobilenet_v3_large_v1.json \
    manifests/numeric_reader_ppocrv6_medium_v1.json \
    manifests/efficientdet_lite2_object_v1.json; do
    if [[ ! -s "$SOURCE_RELEASE_DIR/$path" ]]; then
        echo "missing current frozen candidate: $SOURCE_RELEASE_DIR/$path" >&2
        echo "run tools/ci/build-current-internal-candidate.sh first" >&2
        exit 3
    fi
done
node "$REPO_ROOT/tools/ci/check-current-internal-built-manifests.mjs" \
    "$SOURCE_RELEASE_DIR"

source_catalog="$SOURCE_RELEASE_DIR/catalog.json"
source_release="$SOURCE_RELEASE_DIR/release.json"
source_checksums="$SOURCE_RELEASE_DIR/checksums.json"
catalog_sha="$(sha256_value "$source_catalog")"
release_sha="$(sha256_value "$source_release")"
expected_catalog_sha="$(jq -er '.catalog_sha256 | select(test("^[0-9a-f]{64}$"))' "$source_checksums")"
expected_release_sha="$(jq -er '.release_sha256 | select(test("^[0-9a-f]{64}$"))' "$source_checksums")"
if [[ "$catalog_sha" != "$expected_catalog_sha" || "$release_sha" != "$expected_release_sha" ]]; then
    echo "current frozen Catalog or release bytes do not match checksums.json" >&2
    exit 4
fi
if [[ "$(jq -r '.catalog_version' "$source_catalog")" != "2026.09.15.1" ]] ||
   [[ "$(jq '[.packages[] | select(.status == "active")] | length' "$source_catalog")" != "3" ]] ||
   [[ "$(jq '.packages | length' "$source_catalog")" != "3" ]]; then
    echo "local candidate is not Catalog 2026.09.15.1 with exactly three active packages" >&2
    exit 4
fi

while IFS=$'\t' read -r package_id expected_manifest_sha catalog_manifest_sha; do
    manifest="$SOURCE_RELEASE_DIR/manifests/$package_id.json"
    actual_manifest_sha="$(sha256_value "$manifest")"
    if [[ "$actual_manifest_sha" != "$expected_manifest_sha" ]] ||
       [[ "$actual_manifest_sha" != "$catalog_manifest_sha" ]]; then
        echo "current frozen Manifest identity mismatch: $package_id" >&2
        exit 4
    fi
done < <(jq -r --slurpfile catalog "$source_catalog" '
    .packages | to_entries[] |
    .key as $package_id |
    [$package_id, .value.manifest_sha256,
      ($catalog[0].packages[] | select(.package_id == $package_id) | .manifest_sha256)] | @tsv
' "$source_checksums")

python3 - "$SOURCE_RELEASE_DIR" <<'PY'
import base64
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
release = json.loads((root / "release.json").read_text())
catalog_bytes = (root / "catalog.json").read_bytes()
if base64.b64decode(release["catalog_bytes_base64"], validate=True) != catalog_bytes:
    raise SystemExit("release descriptor does not embed the exact frozen Catalog")
catalog = json.loads(catalog_bytes)
active = {entry["package_id"]: entry for entry in catalog["packages"] if entry["status"] == "active"}
if set(release["packages"]) != set(active):
    raise SystemExit("release descriptor package set differs from the frozen Catalog")
for package_id, package in release["packages"].items():
    manifest = (root / "manifests" / f"{package_id}.json").read_bytes()
    if base64.b64decode(package["manifest_bytes_base64"], validate=True) != manifest:
        raise SystemExit(f"release descriptor does not embed the exact {package_id} Manifest")
    if package["manifest_url"] != active[package_id]["manifest_url"]:
        raise SystemExit(f"release descriptor URL differs for {package_id}")
PY

mkdir -p "$OUTPUT_ROOT/release/manifests" "$OUTPUT_ROOT/artifacts"
cp "$source_release" "$OUTPUT_ROOT/release/release.json"
cp "$source_catalog" "$OUTPUT_ROOT/release/catalog.json"
while IFS= read -r package_id; do
    cp "$SOURCE_RELEASE_DIR/manifests/$package_id.json" \
        "$OUTPUT_ROOT/release/manifests/$package_id.json"
done < <(jq -r '.packages[] | select(.status == "active") | .package_id' "$source_catalog")

release_file="$OUTPUT_ROOT/release/release.json"

while IFS=$'\t' read -r package_id role url artifact_sha artifact_size source_relative; do
    [[ -n "$role" ]]
    filename="$(basename -- "$url")"
    source_path="$(cd "$RELEASE_INPUT_ROOT" && realpath "$source_relative")"
    materialize_exact "$source_path" "$artifact_sha" "$artifact_size" \
        "$OUTPUT_ROOT/artifacts/$package_id/$filename"
done < <(jq -r --slurpfile input "$RELEASE_INPUT" '
    .packages | to_entries[] |
    .key as $package_id |
    .value.artifact_descriptors_by_role | to_entries[] |
    .key as $role |
    [$package_id, $role, .value.url, .value.sha256, .value.size_bytes,
      ($input[0].packages[] | select(.package_id == $package_id) | .artifacts_by_role[$role])] | @tsv
' "$release_file")

jq -n \
    --arg source_release_dir "$SOURCE_RELEASE_DIR" \
    --arg release_sha256 "$release_sha" \
    --arg catalog_sha256 "$catalog_sha" \
    '{source:"local_frozen_candidate", source_release_dir:$source_release_dir,
      release_sha256:$release_sha256, catalog_sha256:$catalog_sha256}' \
    > "$OUTPUT_ROOT/identity.json"

printf '%s\n' "$OUTPUT_ROOT"
