#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly VERSION='1.18.3'
readonly EXPECTED_SHA256='a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
readonly DOWNLOAD_URL="https://github.com/google/bundletool/releases/download/$VERSION/bundletool-all-$VERSION.jar"
readonly CACHE_DIR="${BUNDLETOOL_CACHE_DIR:-$REPO_ROOT/.local/tools/bundletool}"
readonly JAR="${BUNDLETOOL_JAR:-$CACHE_DIR/bundletool-all-$VERSION.jar}"

sha256_value() {
    shasum -a 256 "$1" | awk '{print $1}'
}

if [[ -f "$JAR" ]] && [[ "$(sha256_value "$JAR")" == "$EXPECTED_SHA256" ]]; then
    printf '%s\n' "$JAR"
    exit 0
fi
if [[ -n "${BUNDLETOOL_JAR:-}" ]]; then
    printf 'BUNDLETOOL_JAR does not match pinned bundletool %s: %s\n' "$VERSION" "$JAR" >&2
    exit 1
fi

mkdir -p "$CACHE_DIR"
readonly PARTIAL="$JAR.partial"
curl --fail --location --retry 3 --connect-timeout 15 \
    --output "$PARTIAL" "$DOWNLOAD_URL"
if [[ "$(sha256_value "$PARTIAL")" != "$EXPECTED_SHA256" ]]; then
    printf 'downloaded bundletool hash does not match pinned release %s\n' "$VERSION" >&2
    exit 1
fi
mv "$PARTIAL" "$JAR"
printf '%s\n' "$JAR"
