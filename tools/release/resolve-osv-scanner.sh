#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
# v2.5.1 restores package namespaces in CycloneDX/OSV queries (upstream #2978).
readonly VERSION='2.5.1'
readonly EXPECTED_SHA256='75c44d6332f892a1e56286f4105a98ed751ae28d215ca0a8b65cc00d84103054'
readonly DOWNLOAD_URL="https://github.com/google/osv-scanner/releases/download/v$VERSION/osv-scanner_darwin_arm64"
readonly CACHE_DIR="${OSV_SCANNER_CACHE_DIR:-$REPO_ROOT/.local/tools/osv-scanner}"
readonly BIN="${OSV_SCANNER_BIN:-$CACHE_DIR/osv-scanner-$VERSION-darwin-arm64}"

if [[ "$(uname -s)" != 'Darwin' || "$(uname -m)" != 'arm64' ]]; then
    printf 'the pinned release scanner supports the repository macOS arm64 release host only\n' >&2
    exit 1
fi

sha256_value() {
    shasum -a 256 "$1" | awk '{print $1}'
}

if [[ -f "$BIN" ]] && [[ "$(sha256_value "$BIN")" == "$EXPECTED_SHA256" ]]; then
    chmod 700 "$BIN"
    printf '%s\n' "$BIN"
    exit 0
fi
if [[ -n "${OSV_SCANNER_BIN:-}" ]]; then
    printf 'OSV_SCANNER_BIN does not match pinned osv-scanner %s: %s\n' "$VERSION" "$BIN" >&2
    exit 1
fi

mkdir -p "$CACHE_DIR"
readonly PARTIAL="$BIN.partial"
curl --fail --location --retry 3 --connect-timeout 15 \
    --output "$PARTIAL" "$DOWNLOAD_URL"
if [[ "$(sha256_value "$PARTIAL")" != "$EXPECTED_SHA256" ]]; then
    printf 'downloaded osv-scanner hash does not match pinned release %s\n' "$VERSION" >&2
    exit 1
fi
chmod 700 "$PARTIAL"
mv "$PARTIAL" "$BIN"
printf '%s\n' "$BIN"
