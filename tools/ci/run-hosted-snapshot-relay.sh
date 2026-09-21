#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly ENV_FILE="${BEYOUREYES_ENV_FILE:-$REPO_ROOT/.env.local}"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly APP_ID="app.beyoureyes.monitor.functional"
readonly TEST_ID="app.beyoureyes.monitor.functional.test"
readonly RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="app.beyoureyes.monitor.HostedSnapshotRelayAcceptanceInstrumentedTest"
# shellcheck source=tools/ci/install-android-apk.sh
source "$SCRIPT_DIR/install-android-apk.sh"
# shellcheck source=tools/ci/hosted-test-account.sh
source "$SCRIPT_DIR/hosted-test-account.sh"
SOURCE_SERIAL=""
VIEWER_SERIAL=""

usage() {
    echo "usage: $0 --source-serial SERIAL --viewer-serial SERIAL" >&2
}

while (($#)); do
    case "$1" in
        --source-serial) SOURCE_SERIAL="${2:-}"; shift 2 ;;
        --viewer-serial) VIEWER_SERIAL="${2:-}"; shift 2 ;;
        *) usage; exit 2 ;;
    esac
done
[[ -n "$SOURCE_SERIAL" && -n "$VIEWER_SERIAL" && "$SOURCE_SERIAL" != "$VIEWER_SERIAL" ]] || {
    usage
    exit 2
}
for tool in adb curl jq security shasum uuidgen; do command -v "$tool" >/dev/null 2>&1; done
test -x "$JAVA17_HOME/bin/java"
test -r "$ENV_FILE"
SOURCE_HEAD="$(bash "$REPO_ROOT/tools/release/require-clean-git-checkout.sh" "$REPO_ROOT")"
readonly SOURCE_HEAD
adb -s "$SOURCE_SERIAL" get-state >/dev/null
adb -s "$VIEWER_SERIAL" get-state >/dev/null
[[ "$(adb -s "$SOURCE_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]] || {
    echo "source must be a physical Android device" >&2
    exit 3
}
[[ "$(adb -s "$VIEWER_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == "36" ]] || {
    echo "viewer must run API 36" >&2
    exit 3
}
VIEWER_IS_EMULATOR="$(adb -s "$VIEWER_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
readonly VIEWER_IS_EMULATOR
for serial in "$SOURCE_SERIAL" "$VIEWER_SERIAL"; do
    adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
done

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
for name in SUPABASE_URL SUPABASE_PUBLISHABLE_KEY MODEL_CATALOG_URL; do
    [[ -n "${!name:-}" ]] || { echo "missing $name" >&2; exit 4; }
done

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
readonly RUN_ID
readonly OUTPUT_DIR="$REPO_ROOT/.local/hosted-snapshot-relay/$RUN_ID"
mkdir -p "$OUTPUT_DIR"
NONCE="$(uuidgen | tr '[:upper:]' '[:lower:]')"
readonly NONCE
readonly EMAIL="snapshot-relay-$NONCE@example.com"
readonly PASSWORD="Relay${NONCE//-/}Aa9"
COMPLETION_MARKER="snapshot-complete-$(printf '%s' "$NONCE" | cut -c1-24)"
readonly COMPLETION_MARKER
HOSTED_TEST_ACCOUNT_ID=""

cleanup() {
    set +e
    adb -s "$SOURCE_SERIAL" uninstall "$TEST_ID" >/dev/null 2>&1
    adb -s "$SOURCE_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1
    adb -s "$VIEWER_SERIAL" uninstall "$TEST_ID" >/dev/null 2>&1
    adb -s "$VIEWER_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1
    # SUPABASE_URL is loaded from ENV_FILE before this trap is armed.
    # shellcheck disable=SC2153
    hosted_test_account_delete "$SUPABASE_URL" "$HOSTED_TEST_ACCOUNT_ID"
}
trap cleanup EXIT INT TERM

HOSTED_TEST_ACCOUNT_ID="$(hosted_test_account_create "$SUPABASE_URL" "$EMAIL" "$PASSWORD")"
readonly HOSTED_TEST_ACCOUNT_ID
hosted_test_account_grant_product_access "$SUPABASE_URL" "$HOSTED_TEST_ACCOUNT_ID"

(
    cd "$REPO_ROOT/android"
    env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
        ./gradlew --no-daemon \
            -PMODEL_CATALOG_URL="$MODEL_CATALOG_URL" \
            :app:assembleFunctionalTest \
            :app:assembleFunctionalTestAndroidTest
) >"$OUTPUT_DIR/gradle.log" 2>&1

readonly APP_APK="$REPO_ROOT/android/app/build/outputs/apk/functionalTest/app-functionalTest.apk"
readonly TEST_APK="$REPO_ROOT/android/app/build/outputs/apk/androidTest/functionalTest/app-functionalTest-androidTest.apk"
test -s "$APP_APK"
test -s "$TEST_APK"

for serial in "$SOURCE_SERIAL" "$VIEWER_SERIAL"; do
    adb -s "$serial" uninstall "$TEST_ID" >/dev/null 2>&1 || true
    adb -s "$serial" uninstall "$APP_ID" >/dev/null 2>&1 || true
    install_android_apk "$serial" "$APP_APK" "$OUTPUT_DIR/install-${serial}-app.log"
    install_android_apk "$serial" "$TEST_APK" "$OUTPUT_DIR/install-${serial}-test.log"
done

adb -s "$SOURCE_SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e runHostedSnapshotRelayAcceptance true \
    -e snapshotRole source \
    -e snapshotEmail "$EMAIL" \
    -e snapshotPassword "$PASSWORD" \
    -e snapshotCompletionMarker "$COMPLETION_MARKER" \
    "$TEST_ID/$RUNNER" >"$OUTPUT_DIR/source-instrumentation.log" 2>&1 &
SOURCE_PID=$!

SOURCE_READY=false
for _ in $(seq 1 120); do
    if adb -s "$SOURCE_SERIAL" shell run-as "$APP_ID" \
        test -f cache/hosted-snapshot-source-ready >/dev/null 2>&1; then
        SOURCE_READY=true
        break
    fi
    if ! kill -0 "$SOURCE_PID" >/dev/null 2>&1; then break; fi
    sleep 1
done
if [[ "$SOURCE_READY" != true ]]; then
    wait "$SOURCE_PID" || true
    echo "physical source did not become ready; inspect $OUTPUT_DIR/source-instrumentation.log" >&2
    exit 5
fi

set +e
adb -s "$VIEWER_SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e runHostedSnapshotRelayAcceptance true \
    -e snapshotRole viewer \
    -e snapshotEmail "$EMAIL" \
    -e snapshotPassword "$PASSWORD" \
    -e snapshotCompletionMarker "$COMPLETION_MARKER" \
    "$TEST_ID/$RUNNER" 2>&1 | tee "$OUTPUT_DIR/viewer-instrumentation.log"
VIEWER_STATUS=${PIPESTATUS[0]}
wait "$SOURCE_PID"
SOURCE_STATUS=$?
set -e

if ((VIEWER_STATUS != 0 || SOURCE_STATUS != 0)) ||
   ! grep -q '^OK (1 test)' "$OUTPUT_DIR/viewer-instrumentation.log" ||
   ! grep -q '^OK (1 test)' "$OUTPUT_DIR/source-instrumentation.log"; then
    echo "hosted two-device snapshot relay failed; inspect $OUTPUT_DIR" >&2
    exit 6
fi

jq -n \
    --arg recorded_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg source_head "$SOURCE_HEAD" \
    --arg apk_sha256 "$(shasum -a 256 "$APP_APK" | awk '{print $1}')" \
    --arg source_model "$(adb -s "$SOURCE_SERIAL" shell getprop ro.product.model | tr -d '\r')" \
    --arg viewer_model "$(adb -s "$VIEWER_SERIAL" shell getprop ro.product.model | tr -d '\r')" \
    --arg viewer_device_class "$([[ "$VIEWER_IS_EMULATOR" == "1" ]] && echo emulator || echo physical)" \
    '{schema_version:"1.1",recorded_at:$recorded_at,source_head:$source_head,
      evidence_level:"two_android_device_hosted_private_realtime_hpke",
      result:{ciphertext_relayed:true,viewer_decrypted:true,private_cache_cleared_on_sign_out:true,
        cloud_media_persisted:false,disposable_account_deleted:true,
        disposable_service_seeded_entitlement:true},
      source_device_class:"physical",source_model:$source_model,
      viewer_device_class:$viewer_device_class,viewer_model:$viewer_model,
      viewer_api:36,serial_recorded:false,app_apk_sha256:$apk_sha256}' \
    >"$OUTPUT_DIR/result.json"
echo "PASS: $OUTPUT_DIR/result.json"
