#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly RELEASE_ENV="${BEYOUREYES_WEBSITE_RELEASE_ENV:-$HOME/.config/be-your-eyes/website-release.env}"
readonly SIGNING_ENV="$HOME/.config/be-your-eyes/android-app-signing/signing.env"
JAVA25_HOME="${JAVA25_HOME:-$(mise where 'java@temurin-25')}"
readonly JAVA25_HOME
readonly OUTPUT_DIR="/Volumes/DevDisk/DeveloperData/be-your-eyes/repo-local/releases/website"
GIT_COMMIT="$(bash "$SCRIPT_DIR/require-clean-git-checkout.sh" "$REPO_ROOT")"
readonly GIT_COMMIT
test -d /Volumes/DevDisk/DeveloperData/be-your-eyes
test -f "$RELEASE_ENV"
test -f "$SIGNING_ENV"
test -x "$JAVA25_HOME/bin/java"
[[ "$(stat -f '%Lp' "$RELEASE_ENV")" == 600 && "$(stat -f '%Lp' "$SIGNING_ENV")" == 600 ]]
set -a
# shellcheck disable=SC1090
source "$RELEASE_ENV"
# shellcheck disable=SC1090
source "$SIGNING_ENV"
set +a
for name in MODEL_CATALOG_URL MODEL_RELEASE_URL MODEL_ROLLBACK_CATALOG_URL MODEL_ROLLBACK_RELEASE_URL \
    BEYOUREYES_VERSION_CODE BEYOUREYES_VERSION_NAME; do
    [[ -n "${!name:-}" ]] || { printf 'Missing website release input: %s\n' "$name" >&2; exit 1; }
done
[[ "$BEYOUREYES_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]
readonly CANDIDATE_DIR="$OUTPUT_DIR/$GIT_COMMIT/$BEYOUREYES_VERSION_CODE"
[[ ! -e "$CANDIDATE_DIR" ]] || { printf 'Candidate already exists: %s\n' "$CANDIDATE_DIR" >&2; exit 1; }
mkdir -p "$CANDIDATE_DIR"
# Direct distribution changes billing, not model licensing or commercial runtime admission.
node "$REPO_ROOT/model-tools/catalog-validator/src/commercial-release-freezer-cli.mjs" \
    --catalog-url "$MODEL_CATALOG_URL" --release-url "$MODEL_RELEASE_URL" \
    --rollback-catalog-url "$MODEL_ROLLBACK_CATALOG_URL" --rollback-release-url "$MODEL_ROLLBACK_RELEASE_URL" \
    --output "$CANDIDATE_DIR/models" > "$CANDIDATE_DIR/model-freeze.json"
(
    cd "$REPO_ROOT/android"
    # AGP 9.3 invokes lint during assembly as well. Keep Gradle on the existing
    # JDK 25 throughout; Android compileOptions/jvmTarget remain Java 17.
    env JAVA_HOME="$JAVA25_HOME" PATH="$JAVA25_HOME/bin:$PATH" \
        ./gradlew --no-daemon :app:lintWebsite :app:assembleWebsite
)
readonly APK="$CANDIDATE_DIR/be-your-eye.apk"
cp "$REPO_ROOT/android/app/build/outputs/apk/website/app-website.apk" "$APK"
readonly APKSIGNER="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/36.0.0/apksigner"
readonly AAPT2="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/36.0.0/aapt2"
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$CANDIDATE_DIR/apk-signature.txt"
"$AAPT2" dump badging "$APK" > "$CANDIDATE_DIR/apk-manifest.txt"
rg -Fq "package: name='app.beyoureyes.monitor' versionCode='$BEYOUREYES_VERSION_CODE' versionName='$BEYOUREYES_VERSION_NAME'" "$CANDIDATE_DIR/apk-manifest.txt"
rg -Fxq "native-code: 'arm64-v8a'" "$CANDIDATE_DIR/apk-manifest.txt"
if rg -q '^application-debuggable' "$CANDIDATE_DIR/apk-manifest.txt"; then
    printf 'Website APK must not be debuggable\n' >&2
    exit 1
fi
readonly CERTIFICATE_SHA256="a451ba73211a813d5099742575240be56d7b19c8402ca56804cb1f52d914809b"
rg -Fxq "Signer #1 certificate SHA-256 digest: $CERTIFICATE_SHA256" "$CANDIDATE_DIR/apk-signature.txt"
shasum -a 256 "$APK" > "$CANDIDATE_DIR/apk.sha256"
env JAVA_HOME="$JAVA25_HOME" PATH="$JAVA25_HOME/bin:$PATH" BEYOUREYES_SBOM_ANDROID_VARIANT=website \
    bash "$REPO_ROOT/tools/sbom/generate.sh" "$CANDIDATE_DIR/sbom" "$APK"
bash "$SCRIPT_DIR/scan-sbom-vulnerabilities.sh" "$CANDIDATE_DIR/vulnerability-scan" \
    "$CANDIDATE_DIR"/sbom/*.cdx.json > "$CANDIDATE_DIR/vulnerability-scan-result.txt"
cp "$REPO_ROOT/android/app/build/reports/lint-results-website.xml" "$CANDIDATE_DIR/lint-results.xml"
[[ "$(bash "$SCRIPT_DIR/require-clean-git-checkout.sh" "$REPO_ROOT")" == "$GIT_COMMIT" ]]
jq -n --arg commit "$GIT_COMMIT" --arg version "$BEYOUREYES_VERSION_NAME" \
    --argjson version_code "$BEYOUREYES_VERSION_CODE" \
    --arg sha256 "$(awk '{print $1}' "$CANDIDATE_DIR/apk.sha256")" \
    --argjson size "$(stat -f '%z' "$APK")" --arg certificate "$CERTIFICATE_SHA256" \
    --arg catalog_url "$MODEL_CATALOG_URL" --arg built_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{format: "beyoureyes.website-candidate.v1", git_commit: $commit, built_at: $built_at,
      application_id: "app.beyoureyes.monitor", version_name: $version, version_code: $version_code,
      apk: {file: "be-your-eye.apk", sha256: $sha256, size_bytes: $size, certificate_sha256: $certificate},
      catalog_url: $catalog_url, model_snapshot: "models/snapshot.json",
      sbom_directory: "sbom", vulnerability_report_directory: "vulnerability-scan",
      release_quality_ready: false,
      scope: "Signed build, lint, commercial model snapshot, SBOM and OSV checks only; physical and hosted payment acceptance remain separate."}' \
    > "$CANDIDATE_DIR/candidate.json"
printf 'Signed website candidate: %s\n' "$APK"
printf 'Fresh install, same-key upgrade, hosted payment and refund acceptance still required before publication.\n'
