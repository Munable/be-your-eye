#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly ENV_FILE="${BEYOUREYES_ENV_FILE:-$REPO_ROOT/.env.local}"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
readonly AVD_NAME="${BEYOUREYES_CLOUD_AVD:-be-your-eyes-api36-8gb}"
readonly PORT="${BEYOUREYES_CLOUD_PORT:-5556}"
readonly RECEIVER_SERIAL="emulator-$PORT"
readonly APP_ID="app.beyoureyes.monitor.functional"
readonly TEST_ID="app.beyoureyes.monitor.functional.test"
readonly RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="app.beyoureyes.monitor.HostedPushAcceptanceInstrumentedTest"
readonly CATALOG_URL="https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json"
# shellcheck source=tools/ci/install-android-apk.sh
source "$SCRIPT_DIR/install-android-apk.sh"
# shellcheck source=tools/ci/hosted-test-account.sh
source "$SCRIPT_DIR/hosted-test-account.sh"
SOURCE_SERIAL=""

usage() {
    echo "usage: $0 [--source-serial SERIAL]" >&2
}

while (($#)); do
    case "$1" in
        --source-serial) SOURCE_SERIAL="${2:-}"; shift 2 ;;
        *) usage; exit 2 ;;
    esac
done
if [[ -z "$SOURCE_SERIAL" ]]; then
    while read -r serial state _; do
        if [[ "$state" == "device" ]] &&
           [[ "$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" != "1" ]]; then
            SOURCE_SERIAL="$serial"
            break
        fi
    done < <(adb devices -l | tail -n +2)
fi
[[ -n "$SOURCE_SERIAL" ]] || { echo "connect one physical Android source device" >&2; exit 3; }

for tool in adb curl jq security shasum uuidgen; do command -v "$tool" >/dev/null 2>&1; done
test -x "$JAVA17_HOME/bin/java"
test -x "$EMULATOR_BIN"
test -r "$ENV_FILE"
adb -s "$SOURCE_SERIAL" get-state >/dev/null
[[ "$(adb -s "$SOURCE_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]] || {
    echo "source must be a physical Android device" >&2
    exit 3
}
if adb -s "$RECEIVER_SERIAL" get-state >/dev/null 2>&1; then
    echo "$RECEIVER_SERIAL is already running; stop it before the isolated hosted test." >&2
    exit 3
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
for name in SUPABASE_URL SUPABASE_PUBLISHABLE_KEY FIREBASE_DEBUG_API_KEY \
    FIREBASE_DEBUG_APPLICATION_ID FIREBASE_DEBUG_PROJECT_ID FIREBASE_DEBUG_GCM_SENDER_ID; do
    [[ -n "${!name:-}" ]] || { echo "missing $name in local product configuration" >&2; exit 4; }
done
if [[ "${MODEL_CATALOG_URL:-}" != "$CATALOG_URL" ]]; then
    echo "MODEL_CATALOG_URL must be the frozen three-package internal Catalog" >&2
    exit 4
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
readonly RUN_ID
readonly OUTPUT_DIR="$REPO_ROOT/.local/hosted-cloud/$RUN_ID"
mkdir -p "$OUTPUT_DIR"
NONCE="$(uuidgen | tr '[:upper:]' '[:lower:]')"
readonly NONCE
readonly EMAIL="hosted-push-$NONCE@example.com"
readonly PASSWORD="Push${NONCE//-/}Aa9"
COMPLETION_MARKER="push-complete-$(printf '%s' "$NONCE" | cut -c1-24)"
readonly COMPLETION_MARKER
HOSTED_TEST_ACCOUNT_ID=""

cleanup() {
    set +e
    adb -s "$SOURCE_SERIAL" uninstall "$TEST_ID" >/dev/null 2>&1
    adb -s "$SOURCE_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1
    adb -s "$RECEIVER_SERIAL" uninstall "$TEST_ID" >/dev/null 2>&1
    adb -s "$RECEIVER_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1
    adb -s "$RECEIVER_SERIAL" emu kill >/dev/null 2>&1
    if [[ -n "${EMULATOR_PID:-}" ]]; then wait "$EMULATOR_PID" >/dev/null 2>&1; fi
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
        ./gradlew --no-daemon --stacktrace \
            -PMODEL_CATALOG_URL="$CATALOG_URL" \
            :app:assembleFunctionalTest \
            :app:assembleFunctionalTestAndroidTest
) >"$OUTPUT_DIR/gradle.log" 2>&1

readonly APP_APK="$REPO_ROOT/android/app/build/outputs/apk/functionalTest/app-functionalTest.apk"
readonly TEST_APK="$REPO_ROOT/android/app/build/outputs/apk/androidTest/functionalTest/app-functionalTest-androidTest.apk"
test -s "$APP_APK"
test -s "$TEST_APK"

"$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu swiftshader -port "$PORT" >"$OUTPUT_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!
for _ in $(seq 1 180); do
    if adb -s "$RECEIVER_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -qx 1; then
        break
    fi
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "emulator stopped during boot; see $OUTPUT_DIR/emulator.log" >&2
        exit 5
    fi
    sleep 1
done
if [[ "$(adb -s "$RECEIVER_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]] ||
   [[ "$(adb -s "$RECEIVER_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" != "36" ]]; then
    echo "Google Play API 36 emulator did not finish booting" >&2
    exit 5
fi

for serial in "$SOURCE_SERIAL" "$RECEIVER_SERIAL"; do
    adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb -s "$serial" uninstall "$TEST_ID" >/dev/null 2>&1 || true
    adb -s "$serial" uninstall "$APP_ID" >/dev/null 2>&1 || true
    install_android_apk "$serial" "$APP_APK" "$OUTPUT_DIR/install-${serial}-app.log"
    install_android_apk "$serial" "$TEST_APK" "$OUTPUT_DIR/install-${serial}-test.log"
done
adb -s "$RECEIVER_SERIAL" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS

adb -s "$SOURCE_SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e runHostedPushAcceptance true \
    -e pushRole source \
    -e pushEmail "$EMAIL" \
    -e pushPassword "$PASSWORD" \
    -e pushCompletionMarker "$COMPLETION_MARKER" \
    "$TEST_ID/$RUNNER" >"$OUTPUT_DIR/source-instrumentation.log" 2>&1 &
SOURCE_PID=$!

SOURCE_READY=false
for _ in $(seq 1 120); do
    if adb -s "$SOURCE_SERIAL" shell run-as "$APP_ID" \
        test -f cache/hosted-push-source-ready >/dev/null 2>&1; then
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
adb -s "$RECEIVER_SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e runHostedPushAcceptance true \
    -e pushRole receiver \
    -e pushEmail "$EMAIL" \
    -e pushPassword "$PASSWORD" \
    -e pushCompletionMarker "$COMPLETION_MARKER" \
    "$TEST_ID/$RUNNER" 2>&1 | tee "$OUTPUT_DIR/receiver-instrumentation.log"
RECEIVER_STATUS=${PIPESTATUS[0]}
wait "$SOURCE_PID"
SOURCE_STATUS=$?
set -e

for serial in "$SOURCE_SERIAL" "$RECEIVER_SERIAL"; do
    adb -s "$serial" logcat -d -v threadtime '*:W' >"$OUTPUT_DIR/logcat-warnings-${serial}.txt" || true
done
if ((RECEIVER_STATUS != 0 || SOURCE_STATUS != 0)) ||
   ! grep -q '^OK (1 test)' "$OUTPUT_DIR/receiver-instrumentation.log" ||
   ! grep -q '^OK (1 test)' "$OUTPUT_DIR/source-instrumentation.log"; then
    echo "hosted physical-source FCM acceptance failed; inspect $OUTPUT_DIR" >&2
    exit 6
fi

jq -n \
    --arg recorded_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg source_head "$(git -C "$REPO_ROOT" rev-parse HEAD)" \
    --arg source_diff_sha256 "$(git -C "$REPO_ROOT" diff --binary HEAD | shasum -a 256 | awk '{print $1}')" \
    --arg apk_sha256 "$(shasum -a 256 "$APP_APK" | awk '{print $1}')" \
    --arg source_model "$(adb -s "$SOURCE_SERIAL" shell getprop ro.product.model | tr -d '\r')" \
    '{schema_version:"1.1",recorded_at:$recorded_at,source_head:$source_head,
      source_worktree_clean:false,source_tracked_diff_sha256:$source_diff_sha256,
      evidence_level:"physical_android_source_hosted_supabase_edge_fcm_api36_gms_receiver",
      result:{events_uploaded:1,events_received:1,duplicate_notifications:0,
        source_real_product_device_id:true,receiver_real_product_device_id:true,
        fcm_only_delivery_after_sync_cancelled:true,disposable_account_deleted:true,
        disposable_service_seeded_entitlement:true},
      source_device_class:"physical",source_model:$source_model,
      receiver_device_class:"emulator",receiver_api:36,serial_recorded:false,
      app_apk_sha256:$apk_sha256}' >"$OUTPUT_DIR/result.json"
echo "PASS: $OUTPUT_DIR/result.json"
