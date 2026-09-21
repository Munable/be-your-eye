#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly ENV_FILE="${BEYOUREYES_RELEASE_ENV_FILE:-$HOME/.config/be-your-eyes/android-upload/release.env}"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

# Freeze the exact source commit before reading release inputs or performing
# any model download/Gradle work. Staged, unstaged, and untracked files all
# fail; files ignored by the checkout's Git rules do not.
GIT_COMMIT="$(bash "$SCRIPT_DIR/require-clean-git-checkout.sh" "$REPO_ROOT")"
readonly GIT_COMMIT

command -v mise >/dev/null 2>&1
test -x "$JAVA17_HOME/bin/java"
JAVA25_HOME="${JAVA25_HOME:-$(mise where 'java@temurin-25')}"
readonly JAVA25_HOME
test -x "$JAVA25_HOME/bin/java"

if [[ ! -f "$ENV_FILE" ]]; then
    printf 'release env is missing: %s\n' "$ENV_FILE" >&2
    exit 1
fi
if [[ "$(stat -f '%Lp' "$ENV_FILE")" != "600" ]]; then
    printf 'release env must have mode 0600: %s\n' "$ENV_FILE" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

readonly REQUIRED_INPUTS=(
    BEYOUREYES_VERSION_CODE
    BEYOUREYES_VERSION_NAME
    BEYOUREYES_RELEASE_STORE_FILE
    BEYOUREYES_RELEASE_STORE_PASSWORD
    BEYOUREYES_RELEASE_KEY_ALIAS
    BEYOUREYES_RELEASE_KEY_PASSWORD
    MODEL_CATALOG_URL
    MODEL_RELEASE_URL
    MODEL_ROLLBACK_CATALOG_URL
    MODEL_ROLLBACK_RELEASE_URL
    PRIVACY_POLICY_URL
    SUPABASE_URL
    SUPABASE_PUBLISHABLE_KEY
    FIREBASE_API_KEY
    FIREBASE_APPLICATION_ID
    FIREBASE_PROJECT_ID
    FIREBASE_GCM_SENDER_ID
)

for name in "${REQUIRED_INPUTS[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        printf 'release input is empty: %s\n' "$name" >&2
        exit 1
    fi
done

if [[ ! "$BEYOUREYES_VERSION_CODE" =~ ^[1-9][0-9]{0,9}$ ]] ||
   ((BEYOUREYES_VERSION_CODE > 2100000000)); then
    printf 'BEYOUREYES_VERSION_CODE must be a canonical Play version code from 1 to 2100000000\n' >&2
    exit 1
fi
if [[ ! "$BEYOUREYES_VERSION_NAME" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]{0,99}$ ]]; then
    printf 'BEYOUREYES_VERSION_NAME is not a canonical release version name\n' >&2
    exit 1
fi

for url_name in MODEL_CATALOG_URL MODEL_RELEASE_URL MODEL_ROLLBACK_CATALOG_URL MODEL_ROLLBACK_RELEASE_URL PRIVACY_POLICY_URL SUPABASE_URL; do
    # ${raw} below is a JavaScript template expression, not a shell expansion.
    # shellcheck disable=SC2016
    node -e '
      const [name, raw] = process.argv.slice(1);
      let parsed;
      try { parsed = new URL(raw); } catch { process.exit(1); }
      const canonical = parsed.toString();
      if (parsed.protocol !== "https:" || parsed.username || parsed.password ||
          parsed.hash || parsed.search || (canonical !== raw && canonical !== `${raw}/`) ||
          (name === "SUPABASE_URL" && parsed.pathname !== "/")) {
        process.exit(1);
      }
    ' "$url_name" "${!url_name}" || {
        printf '%s must be a canonical fixed HTTPS URL without credentials, query, or fragment; SUPABASE_URL must identify the project root\n' \
            "$url_name" >&2
        exit 1
    }
done

bash "$SCRIPT_DIR/validate-public-service-inputs.sh"

readonly OUTPUT_DIR_INPUT="${1:-$REPO_ROOT/.local/google-play/$BEYOUREYES_VERSION_NAME-$BEYOUREYES_VERSION_CODE}"
mkdir -p "$OUTPUT_DIR_INPUT"
OUTPUT_DIR="$(CDPATH='' cd -- "$OUTPUT_DIR_INPUT" && pwd)"
readonly OUTPUT_DIR
readonly MODEL_RELEASE_SNAPSHOT_DIR="$OUTPUT_DIR/model-release-snapshot"

# This is deliberately before Gradle: a signed AAB must never be produced if
# the exact commercial Catalog, Manifests, license text, physical evidence, or
# any distributed model/sidecar byte fails its signed hash/size policy.
node "$REPO_ROOT/model-tools/catalog-validator/src/commercial-release-freezer-cli.mjs" \
    --catalog-url "$MODEL_CATALOG_URL" \
    --release-url "$MODEL_RELEASE_URL" \
    --rollback-catalog-url "$MODEL_ROLLBACK_CATALOG_URL" \
    --rollback-release-url "$MODEL_ROLLBACK_RELEASE_URL" \
    --output "$MODEL_RELEASE_SNAPSHOT_DIR" \
    > "$OUTPUT_DIR/model-release-freeze-result.json"
readonly MODEL_RELEASE_SNAPSHOT="$MODEL_RELEASE_SNAPSHOT_DIR/snapshot.json"
test -s "$MODEL_RELEASE_SNAPSHOT"

(
    cd "$REPO_ROOT/android"
    # AGP 9.3 lint invokes JDK 21+ collection methods, while the production
    # bytecode build remains pinned to the project's JDK 17 toolchain.
    env JAVA_HOME="$JAVA25_HOME" PATH="$JAVA25_HOME/bin:$PATH" \
        ./gradlew --no-daemon --stacktrace :app:lintRelease
    env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
        ./gradlew --no-daemon --stacktrace :app:bundleRelease
)

RELEASE_BUILD_CONFIG="$(find "$REPO_ROOT/android/app/build/generated/source/buildConfig/release" \
    -name BuildConfig.java -print -quit)"
readonly RELEASE_BUILD_CONFIG
if [[ -z "$RELEASE_BUILD_CONFIG" ]] ||
   ! grep -Fq "VERSION_CODE = $BEYOUREYES_VERSION_CODE;" "$RELEASE_BUILD_CONFIG" ||
   ! grep -Fq "VERSION_NAME = \"$BEYOUREYES_VERSION_NAME\";" "$RELEASE_BUILD_CONFIG" ||
   ! grep -Fq 'BUILD_CHANNEL = "commercial";' "$RELEASE_BUILD_CONFIG" ||
   ! grep -Fq 'BUILD_IDENTITY = "commercial";' "$RELEASE_BUILD_CONFIG" ||
   ! grep -Fq 'PRODUCT_RUNTIME_ENABLED = true;' "$RELEASE_BUILD_CONFIG"; then
    printf 'generated Release BuildConfig identity does not match release.env\n' >&2
    exit 1
fi
for name in MODEL_CATALOG_URL PRIVACY_POLICY_URL SUPABASE_URL SUPABASE_PUBLISHABLE_KEY \
    FIREBASE_API_KEY FIREBASE_APPLICATION_ID FIREBASE_PROJECT_ID FIREBASE_GCM_SENDER_ID; do
    if [[ "${!name}" == *[$'\n\r"\\']* ]] ||
       ! grep -Fq "$name = \"${!name}\";" "$RELEASE_BUILD_CONFIG"; then
        printf 'generated Release BuildConfig does not match release input: %s\n' "$name" >&2
        exit 1
    fi
done

readonly SOURCE_AAB="$REPO_ROOT/android/app/build/outputs/bundle/release/app-release.aab"
test -s "$SOURCE_AAB"
readonly AAB_NAME="be-your-eye-$BEYOUREYES_VERSION_NAME-$BEYOUREYES_VERSION_CODE.aab"
readonly FROZEN_AAB="$OUTPUT_DIR/$AAB_NAME"
cp "$SOURCE_AAB" "$FROZEN_AAB"

set +e
jarsigner -J-Duser.language=en -J-Duser.country=US -verify -strict "$FROZEN_AAB" \
    > "$OUTPUT_DIR/aab-jarsigner-verification.txt" 2>&1
JARSIGNER_STATUS=$?
set -e
# A repository-generated upload certificate is intentionally self-signed, for
# which jarsigner's strict mode returns bit 4. That bit also covers expired,
# not-yet-valid, and disabled-algorithm certificates, so status 4 is accepted
# only when the English diagnostic names self-signing and none of those other
# conditions. Every other strict warning or verification failure remains fatal.
if ((JARSIGNER_STATUS != 0 && JARSIGNER_STATUS != 4)); then
    printf 'AAB JAR signature verification failed with status %s\n' "$JARSIGNER_STATUS" >&2
    exit 1
fi
if ((JARSIGNER_STATUS == 4)) && {
    ! grep -Fqi 'signer certificate is self-signed' "$OUTPUT_DIR/aab-jarsigner-verification.txt" ||
    grep -Eqi 'expired|not yet valid|disabled' "$OUTPUT_DIR/aab-jarsigner-verification.txt";
}; then
    printf 'AAB JAR signature has a strict error other than the expected self-signed certificate\n' >&2
    exit 1
fi

CERTIFICATE_SHA256="$(
    keytool -printcert -jarfile "$FROZEN_AAB" |
        awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}'
)"
readonly CERTIFICATE_SHA256
EXPECTED_CERTIFICATE_SHA256="$(
    keytool -list -v \
        -keystore "$BEYOUREYES_RELEASE_STORE_FILE" \
        -alias "$BEYOUREYES_RELEASE_KEY_ALIAS" \
        -storepass:env BEYOUREYES_RELEASE_STORE_PASSWORD |
        awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}'
)"
readonly EXPECTED_CERTIFICATE_SHA256
if [[ ! "$CERTIFICATE_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
   [[ "$EXPECTED_CERTIFICATE_SHA256" != "$CERTIFICATE_SHA256" ]]; then
    printf 'AAB and configured upload-key certificates do not match\n' >&2
    exit 1
fi

bash "$SCRIPT_DIR/build-universal-apk-from-aab.sh" "$FROZEN_AAB" "$OUTPUT_DIR/aab-smoke" \
    > "$OUTPUT_DIR/aab-smoke-result.txt"
readonly UNIVERSAL_APK="$OUTPUT_DIR/aab-smoke/universal.apk"
test -s "$UNIVERSAL_APK"
UNIVERSAL_CERTIFICATE_SHA256="$(
    awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}' \
        "$OUTPUT_DIR/aab-smoke/universal-apk-signature.txt"
)"
readonly UNIVERSAL_CERTIFICATE_SHA256
if [[ "$UNIVERSAL_CERTIFICATE_SHA256" != "$CERTIFICATE_SHA256" ]]; then
    printf 'universal APK and AAB upload-key certificates do not match\n' >&2
    exit 1
fi

AAPT_BIN="${AAPT_BIN:-}"
if [[ -z "$AAPT_BIN" ]]; then
    AAPT_BIN="$(find /opt/homebrew/share/android-commandlinetools/build-tools \
        -name aapt -type f -print 2>/dev/null | sort -V | tail -1)"
fi
if [[ ! -x "$AAPT_BIN" ]]; then
    printf 'aapt is not executable; set AAPT_BIN\n' >&2
    exit 1
fi
APK_BADGING="$($AAPT_BIN dump badging "$UNIVERSAL_APK")"
readonly APK_BADGING
APK_PACKAGE_LINE="${APK_BADGING%%$'\n'*}"
readonly APK_PACKAGE_LINE
if [[ "$APK_PACKAGE_LINE" != *"name='app.beyoureyes.monitor'"* ]] ||
   [[ "$APK_PACKAGE_LINE" != *"versionCode='$BEYOUREYES_VERSION_CODE'"* ]] ||
   [[ "$APK_PACKAGE_LINE" != *"versionName='$BEYOUREYES_VERSION_NAME'"* ]]; then
    printf 'universal APK package/version identity does not match release.env\n' >&2
    exit 1
fi
if [[ "$APK_BADGING" != *"targetSdkVersion:'36'"* ]]; then
    printf 'universal APK target SDK is not 36\n' >&2
    exit 1
fi

sha256_value() {
    shasum -a 256 "$1" | awk '{print $1}'
}

AAB_SHA256="$(sha256_value "$FROZEN_AAB")"
readonly AAB_SHA256
UNIVERSAL_APK_SHA256="$(sha256_value "$UNIVERSAL_APK")"
readonly UNIVERSAL_APK_SHA256
printf '%s  %s\n' \
    "$UNIVERSAL_APK_SHA256" \
    "universal.apk" \
    > "$UNIVERSAL_APK.sha256"

# Artifact identity and all three signer identities are now fail-closed. Only
# then spend time generating and querying the release SBOMs.
bash "$REPO_ROOT/tools/sbom/generate.sh" "$OUTPUT_DIR/sbom" "$FROZEN_AAB"
SBOM_FILES=("$OUTPUT_DIR"/sbom/*.cdx.json)
if [[ ! -e "${SBOM_FILES[0]}" ]]; then
    printf 'release SBOM generation produced no CycloneDX documents\n' >&2
    exit 1
fi
bash "$SCRIPT_DIR/scan-sbom-vulnerabilities.sh" "$OUTPUT_DIR/vulnerability-scan" "${SBOM_FILES[@]}" \
    > "$OUTPUT_DIR/vulnerability-scan-result.txt"
SBOM_RECORDS='[]'
for sbom_file in "${SBOM_FILES[@]}"; do
    provenance_file="$sbom_file.provenance.json"
    test -s "$provenance_file"
    SBOM_RECORDS="$(
        jq -c \
            --arg file "${sbom_file#"$OUTPUT_DIR/"}" \
            --arg sha256 "$(sha256_value "$sbom_file")" \
            --arg provenance_file "${provenance_file#"$OUTPUT_DIR/"}" \
            --arg provenance_sha256 "$(sha256_value "$provenance_file")" \
            '. + [{
                file: $file,
                sha256: $sha256,
                provenance_file: $provenance_file,
                provenance_sha256: $provenance_sha256
            }]' <<<"$SBOM_RECORDS"
    )"
done
readonly SBOM_RECORDS
VULNERABILITY_REPORT_FILES=("$OUTPUT_DIR"/vulnerability-scan/*.osv.json)
if [[ ! -e "${VULNERABILITY_REPORT_FILES[0]}" ]]; then
    printf 'release vulnerability scan produced no reports\n' >&2
    exit 1
fi
VULNERABILITY_REPORT_RECORDS='[]'
for report_file in "${VULNERABILITY_REPORT_FILES[@]}"; do
    VULNERABILITY_REPORT_RECORDS="$(
        jq -c \
            --arg file "${report_file#"$OUTPUT_DIR/"}" \
            --arg sha256 "$(sha256_value "$report_file")" \
            '. + [{file: $file, sha256: $sha256}]' \
            <<<"$VULNERABILITY_REPORT_RECORDS"
    )"
done
readonly VULNERABILITY_REPORT_RECORDS
MODEL_RELEASE_SNAPSHOT_SHA256="$(sha256_value "$MODEL_RELEASE_SNAPSHOT")"
readonly MODEL_RELEASE_SNAPSHOT_SHA256
printf '%s  %s\n' \
    "$MODEL_RELEASE_SNAPSHOT_SHA256" \
    "model-release-snapshot/snapshot.json" \
    > "$MODEL_RELEASE_SNAPSHOT.sha256"

# Re-check after all build/SBOM commands so the record cannot claim a clean
# source if the checkout or HEAD changed while the candidate was produced.
FINAL_GIT_COMMIT="$(bash "$SCRIPT_DIR/require-clean-git-checkout.sh" "$REPO_ROOT")"
readonly FINAL_GIT_COMMIT
if [[ "$FINAL_GIT_COMMIT" != "$GIT_COMMIT" ]]; then
    printf 'release source commit changed during build: expected %s, got %s\n' \
        "$GIT_COMMIT" "$FINAL_GIT_COMMIT" >&2
    exit 1
fi

jq -n \
    --slurpfile model_release "$MODEL_RELEASE_SNAPSHOT" \
    --arg generated_at "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
    --arg version_name "$BEYOUREYES_VERSION_NAME" \
    --argjson version_code "$BEYOUREYES_VERSION_CODE" \
    --arg aab_file "$AAB_NAME" \
    --arg aab_sha256 "$AAB_SHA256" \
    --arg universal_apk_sha256 "$UNIVERSAL_APK_SHA256" \
    --arg certificate_sha256 "$CERTIFICATE_SHA256" \
    --arg catalog_url "$MODEL_CATALOG_URL" \
    --arg privacy_policy_url "$PRIVACY_POLICY_URL" \
    --arg model_release_snapshot_sha256 "$MODEL_RELEASE_SNAPSHOT_SHA256" \
    --arg git_commit "$GIT_COMMIT" \
    --argjson sbom "$SBOM_RECORDS" \
    --argjson vulnerability_reports "$VULNERABILITY_REPORT_RECORDS" \
    '{
        status: "local_release_candidate",
        generated_at: $generated_at,
        application_id: "app.beyoureyes.monitor",
        version_name: $version_name,
        version_code: $version_code,
        artifact: {file: $aab_file, sha256: $aab_sha256},
        universal_apk: {
            file: "aab-smoke/universal.apk",
            sha256: $universal_apk_sha256,
            signature_verified: true,
            clean_install_smoke: "open"
        },
        upload_certificate_sha256: $certificate_sha256,
        model_catalog_url: $catalog_url,
        model_release: ($model_release[0] + {
            snapshot_file: "model-release-snapshot/snapshot.json",
            snapshot_sha256: $model_release_snapshot_sha256
        }),
        privacy_policy_url: $privacy_policy_url,
        source: {git_commit: $git_commit, dirty_worktree: false},
        sbom: $sbom,
        vulnerability_scan: {
            scanner: "osv-scanner",
            scanner_version: "2.5.1",
            status: "passed",
            reports: $vulnerability_reports
        },
        boundary: "Local signed AAB and signature-verified universal APK bound to a retained, content-addressed commercial model release snapshot, release-variant SBOM, and passing OSV scan. Clean device install, Play upload, testing tracks, and publication remain open."
    }' > "$OUTPUT_DIR/release-record.json"

printf '%s  %s\n' "$AAB_SHA256" "$AAB_NAME" > "$FROZEN_AAB.sha256"
printf '%s  %s\n' \
    "$(sha256_value "$OUTPUT_DIR/release-record.json")" \
    "release-record.json" \
    > "$OUTPUT_DIR/release-record.json.sha256"
printf 'aab=%s\n' "$FROZEN_AAB"
printf 'aab_sha256=%s\n' "$AAB_SHA256"
printf 'release_record=%s\n' "$OUTPUT_DIR/release-record.json"
