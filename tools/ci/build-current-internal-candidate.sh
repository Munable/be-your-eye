#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
readonly ROOT_DIR
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly INPUT_JSON="$ROOT_DIR/model-tools/v3/releases/current-internal/templates/release-build-input.json"
readonly OUTPUT_DIR="${INTERNAL_RELEASE_OUTPUT_DIR:-$ROOT_DIR/.local/releases/internal-2026.09.15.1}"
readonly CATALOG_KEY="${CATALOG_PRIVATE_KEY:-$HOME/.config/be-your-eyes/release-keys/catalog-2026-a.private.pem}"
readonly MANIFEST_KEY="${MANIFEST_PRIVATE_KEY:-$HOME/.config/be-your-eyes/release-keys/manifest-2026-a.private.pem}"
readonly CATALOG_KEY_ID="${CATALOG_KEY_ID:-catalog-key-2026-a}"
readonly MANIFEST_KEY_ID="${MANIFEST_KEY_ID:-manifest-key-2026-a}"
readonly CATALOG_URL="https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json"

test -r "$INPUT_JSON"
test -r "$CATALOG_KEY"
test -r "$MANIFEST_KEY"
if [[ -e "$OUTPUT_DIR" ]]; then
    printf 'refusing to overwrite existing release directory: %s\n' "$OUTPUT_DIR" >&2
    exit 2
fi

node "$ROOT_DIR/tools/ci/check-current-model-quality.mjs" --channel internal-evaluation

node "$ROOT_DIR/model-tools/catalog-validator/src/release-builder-cli.mjs" \
    "$INPUT_JSON" "$OUTPUT_DIR" "$CATALOG_KEY_ID" "$CATALOG_KEY" \
    "$MANIFEST_KEY_ID" "$MANIFEST_KEY"

node "$ROOT_DIR/tools/ci/check-current-internal-built-manifests.mjs" "$OUTPUT_DIR"

(
    cd "$ROOT_DIR/android"
    env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
        ./gradlew --no-daemon \
            -PMODEL_CATALOG_URL="$CATALOG_URL" \
            :app:assembleInternal
)

printf 'release_dir=%s\n' "$OUTPUT_DIR"
printf 'catalog_sha256=%s\n' "$(shasum -a 256 "$OUTPUT_DIR/catalog.json" | awk '{print $1}')"
printf 'release_sha256=%s\n' "$(shasum -a 256 "$OUTPUT_DIR/release.json" | awk '{print $1}')"
printf 'reference_manifest_sha256=%s\n' "$(shasum -a 256 "$OUTPUT_DIR/manifests/similarity_mediapipe_mobilenet_v3_large_v1.json" | awk '{print $1}')"
printf 'reader_manifest_sha256=%s\n' "$(shasum -a 256 "$OUTPUT_DIR/manifests/numeric_reader_ppocrv6_medium_v1.json" | awk '{print $1}')"
printf 'object_manifest_sha256=%s\n' "$(shasum -a 256 "$OUTPUT_DIR/manifests/efficientdet_lite2_object_v1.json" | awk '{print $1}')"
printf 'object_artifact_sha256=%s\n' "$(shasum -a 256 "$ROOT_DIR/.local/model-artifacts/efficientdet-lite2/efficientdet-lite2.tflite" | awk '{print $1}')"
printf 'apk_sha256=%s\n' "$(shasum -a 256 "$ROOT_DIR/android/app/build/outputs/apk/internal/app-internal.apk" | awk '{print $1}')"
