#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly CATALOG_URL="https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json"
readonly CURRENT_RELEASE_DIR="$REPO_ROOT/.local/releases/internal-2026.09.15.1"
readonly FROZEN_CATALOG="$CURRENT_RELEASE_DIR/catalog.json"
readonly APP_ID="app.beyoureyes.monitor.functional"
readonly TEST_ID="app.beyoureyes.monitor.functional.test"
readonly RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
readonly AVD_NAME="${BEYOUREYES_FUNCTIONAL_AVD:-be-your-eyes-api36-8gb}"
readonly PORT="${BEYOUREYES_FUNCTIONAL_PORT:-5554}"
readonly SERIAL="emulator-$PORT"
readonly PRESERVE_APP_DATA="${BEYOUREYES_FUNCTIONAL_PRESERVE_APP_DATA:-false}"
readonly MODEL_AND_CAMERA_TIMEOUT_MILLIS="${BEYOUREYES_FUNCTIONAL_MODEL_TIMEOUT_MILLIS:-1800000}"
# SwiftShader can leave QEMU's CPU threads hung on macOS arm64.  The software
# renderer is deterministic for these CameraX imagefile flows and keeps the
# isolated API 36 runner usable without requiring a host GPU.
readonly GPU_MODE="${BEYOUREYES_FUNCTIONAL_GPU:-off}"
FLOW="${1:-reading}"
EXPECTED_READING="${2:-333}"
CONFIRMED_READING="$EXPECTED_READING"
OFFLINE_REPLAY=false
MIXED_TEXT_READING=false
case "$FLOW" in
    reading)
        if [[ -n "${3:-}" && "${3:-}" != "--offline-replay" ]]; then
            CONFIRMED_READING="$3"
        fi
        if [[ "${3:-}" == "--offline-replay" || "${4:-}" == "--offline-replay" ]]; then
            OFFLINE_REPLAY=true
        fi
        TEST_CLASS="app.beyoureyes.monitor.NumericReadingCameraFlowInstrumentedTest#createConfirmThresholdAndRecordExactlyOneEventFromCameraX"
        INSTRUMENTATION_ARGUMENTS=(
            -e runStaticReadingCameraFlow true
            -e expectedReading "$EXPECTED_READING"
            -e confirmedReading "$CONFIRMED_READING"
        )
        ;;
    reading-mixed-text)
        MIXED_TEXT_READING=true
        FLOW="reading"
        if [[ -n "${3:-}" && "${3:-}" != "--offline-replay" ]]; then
            CONFIRMED_READING="$3"
        fi
        if [[ "${3:-}" == "--offline-replay" || "${4:-}" == "--offline-replay" ]]; then
            OFFLINE_REPLAY=true
        fi
        TEST_CLASS="app.beyoureyes.monitor.NumericReadingCameraFlowInstrumentedTest#createConfirmThresholdAndRecordExactlyOneEventFromCameraX"
        INSTRUMENTATION_ARGUMENTS=(
            -e runStaticReadingCameraFlow true
            -e expectedReading "$EXPECTED_READING"
            -e confirmedReading "$CONFIRMED_READING"
        )
        ;;
    reading-rejection)
        if [[ "${2:-}" == "--offline-replay" ]]; then OFFLINE_REPLAY=true; fi
        EXPECTED_READING=""
        CONFIRMED_READING=""
        TEST_CLASS="app.beyoureyes.monitor.NumericReadingCameraFlowInstrumentedTest#nonNumericCameraSceneRemainsUnavailableAndCannotStartMonitoring"
        INSTRUMENTATION_ARGUMENTS=(-e runStaticReadingRejectionFlow true)
        ;;
    reference)
        if [[ "${2:-}" == "--offline-replay" ]]; then OFFLINE_REPLAY=true; fi
        EXPECTED_READING=""
        CONFIRMED_READING=""
        TEST_CLASS="app.beyoureyes.monitor.ReferenceCameraFlowInstrumentedTest"
        INSTRUMENTATION_ARGUMENTS=(-e runStaticReferenceCameraFlow true)
        ;;
    object)
        if [[ -z "${BEYOUREYES_OBJECT_CAMERA_IMAGE:-}" || ! -f "$BEYOUREYES_OBJECT_CAMERA_IMAGE" ]]; then
            echo "Set BEYOUREYES_OBJECT_CAMERA_IMAGE to a PNG you have permission to use (centered apple)." >&2
            exit 2
        fi
        if [[ "${2:-}" == "--offline-replay" ]]; then OFFLINE_REPLAY=true; fi
        EXPECTED_READING=""
        CONFIRMED_READING=""
        TEST_CLASS="app.beyoureyes.monitor.ObjectDetectionCameraFlowInstrumentedTest"
        INSTRUMENTATION_ARGUMENTS=(-e runStaticObjectCameraFlow true)
        ;;
    *)
        echo "usage: $0 {reading|reading-mixed-text} [structured-reading] [confirmed-reading] [--offline-replay] | $0 {reading-rejection|reference|object} [--offline-replay]" >&2
        exit 2
        ;;
esac
readonly FLOW EXPECTED_READING CONFIRMED_READING TEST_CLASS OFFLINE_REPLAY
if [[ "$PRESERVE_APP_DATA" != "true" && "$PRESERVE_APP_DATA" != "false" ]]; then
    echo "BEYOUREYES_FUNCTIONAL_PRESERVE_APP_DATA must be true or false" >&2
    exit 2
fi
if [[ ! "$MODEL_AND_CAMERA_TIMEOUT_MILLIS" =~ ^[0-9]+$ ]] ||
   (( MODEL_AND_CAMERA_TIMEOUT_MILLIS < 30000 || MODEL_AND_CAMERA_TIMEOUT_MILLIS > 1800000 )); then
    echo "BEYOUREYES_FUNCTIONAL_MODEL_TIMEOUT_MILLIS must be an integer between 30000 and 1800000" >&2
    exit 2
fi
case "$FLOW" in
    reading)
        INSTRUMENTATION_ARGUMENTS+=(
            -e modelAndCameraTimeoutMillis "$MODEL_AND_CAMERA_TIMEOUT_MILLIS"
        )
        ;;
    reading-rejection)
        INSTRUMENTATION_ARGUMENTS+=(
            -e modelAndCameraTimeoutMillis "$MODEL_AND_CAMERA_TIMEOUT_MILLIS"
        )
        ;;
    object)
        INSTRUMENTATION_ARGUMENTS+=(
            -e modelAndCameraTimeoutMillis "$MODEL_AND_CAMERA_TIMEOUT_MILLIS"
        )
        ;;
esac
readonly OUTPUT_ROOT="$REPO_ROOT/.local/functional-camera"
readonly FIXTURE_ROOT="$REPO_ROOT/.local/functional-fixtures/2026.09.15.1"
SAFE_EXPECTED_READING="$(printf '%s' "$EXPECTED_READING" | tr -c '[:alnum:]._%+-' '-' | cut -c1-48)"
SAFE_CONFIRMED_READING="$(printf '%s' "$CONFIRMED_READING" | tr -c '[:alnum:]._%+-' '-' | cut -c1-48)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$FLOW${SAFE_EXPECTED_READING:+-$SAFE_EXPECTED_READING}"
if [[ "$MIXED_TEXT_READING" == true ]]; then
    RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-reading-mixed-text-$SAFE_EXPECTED_READING"
fi
if [[ "$FLOW" == "reading" && "$CONFIRMED_READING" != "$EXPECTED_READING" ]]; then
    RUN_ID="$RUN_ID-confirmed-$SAFE_CONFIRMED_READING"
fi
readonly RUN_ID
readonly OUTPUT_DIR="$OUTPUT_ROOT/$RUN_ID"
readonly CAMERA_IMAGE="$OUTPUT_DIR/camera.png"

if [[ "$FLOW" == "reading" && ( -z "$EXPECTED_READING" || -z "$CONFIRMED_READING" ) ]]; then
    echo "structured reading and confirmed reading must not be empty" >&2
    exit 2
fi
for tool in adb curl jq magick node shasum strings unzip; do command -v "$tool" >/dev/null 2>&1; done
test -x "$JAVA17_HOME/bin/java"
test -x "$EMULATOR_BIN"
if [[ ! -s "$FROZEN_CATALOG" ]]; then
    echo "missing current frozen Catalog: $FROZEN_CATALOG" >&2
    echo "run tools/ci/build-current-internal-candidate.sh first" >&2
    exit 3
fi
node "$REPO_ROOT/tools/ci/check-current-internal-built-manifests.mjs" \
    "$CURRENT_RELEASE_DIR"
CATALOG_SHA256="$(shasum -a 256 "$FROZEN_CATALOG" | awk '{print $1}')"
readonly CATALOG_SHA256
if [[ "$(jq -r '.catalog_version' "$FROZEN_CATALOG")" != "2026.09.15.1" ]] ||
   [[ "$(jq '[.packages[] | select(.status == "active")] | length' "$FROZEN_CATALOG")" != "3" ]] ||
   [[ "$(jq '.packages | length' "$FROZEN_CATALOG")" != "3" ]]; then
    echo "local authority is not the current frozen three-package Catalog" >&2
    exit 4
fi
mkdir -p "$OUTPUT_DIR"

if adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "$SERIAL is already running; stop it before the isolated camera test." >&2
    exit 3
fi

CATALOG_FILE="$OUTPUT_DIR/catalog.json"
if [[ "$OFFLINE_REPLAY" == true ]]; then
    bash "$SCRIPT_DIR/prepare-functional-fixtures.sh" "$FIXTURE_ROOT" \
        >"$OUTPUT_DIR/prepare-fixtures.log"
    cp "$FIXTURE_ROOT/release/catalog.json" "$CATALOG_FILE"
else
    # A stale loopback proxy must not turn an otherwise reachable R2 Catalog into
    # a false matrix failure. Keep the caller's proxy as the first attempt, then
    # retry once without proxy variables; this preserves intentional proxies while
    # making local validation deterministic after a proxy process exits.
    if ! curl --fail --silent --show-error --location "$CATALOG_URL" --output "$CATALOG_FILE"; then
        echo "Catalog download through the configured proxy failed; retrying direct." >&2
        env -u http_proxy -u https_proxy -u all_proxy \
            -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY \
            curl --fail --silent --show-error --location "$CATALOG_URL" --output "$CATALOG_FILE"
    fi
fi
ACTUAL_CATALOG_SHA256="$(shasum -a 256 "$CATALOG_FILE" | awk '{print $1}')"
if [[ "$ACTUAL_CATALOG_SHA256" != "$CATALOG_SHA256" ]]; then
    echo "signed three-package Catalog hash changed" >&2
    exit 4
fi
if [[ "$(jq -r '.catalog_version' "$CATALOG_FILE")" != "2026.09.15.1" ]] ||
   [[ "$(jq '[.packages[] | select(.status == "active")] | length' "$CATALOG_FILE")" != "3" ]] ||
   [[ "$(jq '.packages | length' "$CATALOG_FILE")" != "3" ]]; then
    echo "functional Catalog is not the frozen three-package internal release" >&2
    exit 4
fi

if [[ "$FLOW" == "reading" ]]; then
    # Android Emulator's imagefile camera is landscape-sensor aligned. This placement was calibrated
    # once against PreviewView FILL_CENTER so the fixture matches the pixels visible to the user.
    if [[ "$MIXED_TEXT_READING" == true ]]; then
        magick -size 480x640 canvas:white -gravity center \
            -font '/System/Library/Fonts/STHeiti Medium.ttc' \
            -pointsize 52 -fill black -annotate -50+0 \
            "读数 $EXPECTED_READING 正常" "$CAMERA_IMAGE"
    else
        # Keep long structured values (for example 00:02:22 or -1,234.50) fully inside the
        # calibrated sensor area. A fixed 90 pt annotation clipped the first time digit and turned
        # an OCR contract test into a camera-fixture failure.
        magick -size 480x640 canvas:white \
            \( +size -background none \
                -font '/System/Library/Fonts/Supplemental/Arial Bold.ttf' \
                -pointsize 90 -fill black "label:$EXPECTED_READING" \
                -resize '250x120>' \) \
            -gravity center -geometry -100+0 -composite "$CAMERA_IMAGE"
    fi
elif [[ "$FLOW" == "reading-rejection" ]]; then
    # A deterministic nonnumeric scene that the reader may decode as a low-confidence glyph.
    # The signed minimum_confidence gate must keep it unavailable before stabilization.
    magick -size 480x640 canvas:'#d8dde3' \
        -fill '#6c7680' -draw 'roundrectangle 80,120 400,240 18,18' \
        -fill '#97a3ad' \
        -draw 'circle 150,420 150,360 circle 330,420 330,360' \
        "$CAMERA_IMAGE"
elif [[ "$FLOW" == "object" ]]; then
    # The caller supplies a licensed, nonprivate PNG of a centered apple.
    cp "$BEYOUREYES_OBJECT_CAMERA_IMAGE" "$CAMERA_IMAGE"
else
    cp "$REPO_ROOT/android/app/src/androidTest/assets/reference-camera-target-1.png" "$CAMERA_IMAGE"
fi

cleanup() {
    set +e
    adb -s "$SERIAL" emu kill >/dev/null 2>&1
    if [[ -n "${EMULATOR_PID:-}" ]]; then wait "$EMULATOR_PID" >/dev/null 2>&1; fi
}
trap cleanup EXIT INT TERM

GRADLE_FIXTURE_ARGUMENTS=()
if [[ "$OFFLINE_REPLAY" == true ]]; then
    GRADLE_FIXTURE_ARGUMENTS=(
        "-PinternalReferenceReleaseDir=$FIXTURE_ROOT/release"
        "-PinternalReferencePrimaryArtifact=$FIXTURE_ROOT/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/mobile_object_localizer_v1.tflite"
        "-PinternalReferenceObjectCropArtifact=$FIXTURE_ROOT/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/prominent_object_candidates_v2.json"
        "-PinternalReferenceEmbedderArtifact=$FIXTURE_ROOT/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/primary.tflite"
        "-PinternalReferenceSimilarityHead=$FIXTURE_ROOT/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/l2_prototype_cosine_candidates_v2.json"
        "-PinternalReadingReleaseDir=$FIXTURE_ROOT/release"
        "-PinternalReadingPrimaryArtifact=$FIXTURE_ROOT/artifacts/numeric_reader_ppocrv6_medium_v1/primary.onnx"
        "-PinternalReadingLocatorArtifact=$FIXTURE_ROOT/artifacts/numeric_reader_ppocrv6_medium_v1/locator.onnx"
        "-PinternalReadingVocabularyArtifact=$FIXTURE_ROOT/artifacts/numeric_reader_ppocrv6_medium_v1/vocabulary.json"
        "-PinternalObjectReleaseDir=$FIXTURE_ROOT/release"
        "-PinternalObjectPrimaryArtifact=$FIXTURE_ROOT/artifacts/efficientdet_lite2_object_v1/efficientdet_lite2.tflite"
    )
fi

run_gradle_with_catalog() {
    if [[ "$OFFLINE_REPLAY" == true ]]; then
        env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
            ./gradlew --no-daemon --stacktrace \
                -PMODEL_CATALOG_URL="$CATALOG_URL" \
                "${GRADLE_FIXTURE_ARGUMENTS[@]}" "$@"
    else
        env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
            ./gradlew --no-daemon --stacktrace \
                -PMODEL_CATALOG_URL="$CATALOG_URL" \
                "$@"
    fi
}

(
    cd "$REPO_ROOT/android"
    # AGP does not reliably invalidate this generated source when a previous local compile used an
    # empty Gradle property. Regenerate only this task with the exact functional Catalog input so
    # local task order can never silently produce a UI-only APK for the CameraX product test.
    run_gradle_with_catalog :app:generateFunctionalTestBuildConfig --rerun-tasks
    if ! grep -R --fixed-strings --quiet "$CATALOG_URL" \
        app/build/generated/source/buildConfig/functionalTest; then
        echo "functional BuildConfig does not contain the frozen Catalog URL" >&2
        exit 7
    fi
    run_gradle_with_catalog \
        :app:assembleFunctionalTest \
        :app:assembleFunctionalTestAndroidTest
) >"$OUTPUT_DIR/gradle.log" 2>&1

readonly APP_APK="$REPO_ROOT/android/app/build/outputs/apk/functionalTest/app-functionalTest.apk"
readonly TEST_APK="$REPO_ROOT/android/app/build/outputs/apk/androidTest/functionalTest/app-functionalTest-androidTest.apk"
test -s "$APP_APK"
test -s "$TEST_APK"
if ! (set +o pipefail; unzip -p "$APP_APK" 'classes*.dex' | strings | grep -Fq -- "$CATALOG_URL"); then
    echo "functional APK does not contain the frozen Catalog URL" >&2
    exit 7
fi

# Build before allocating the 8 GB product-boundary emulator. Running Gradle dex/package work and
# QEMU together creates avoidable memory pressure on the 16 GB development Mac without increasing
# camera coverage.
"$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu "$GPU_MODE" -port "$PORT" -camera-back "imagefile:$CAMERA_IMAGE" \
    >"$OUTPUT_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!

# The 8 GB AVD lives on the external development disk and must cold boot because the camera image
# changes per flow. On a busy 16 GB host, API 36 can need a little over three minutes to finish.
for _ in $(seq 1 300); do
    if adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -qx 1; then
        break
    fi
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "emulator stopped during boot; see $OUTPUT_DIR/emulator.log" >&2
        exit 5
    fi
    sleep 1
done
if [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
    echo "API 36 emulator did not finish booting" >&2
    exit 5
fi
if [[ "$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" != "36" ]]; then
    echo "functional emulator must run API 36" >&2
    exit 5
fi

if [[ "$PRESERVE_APP_DATA" == true ]] &&
   adb -s "$SERIAL" shell pm path "$APP_ID" >/dev/null 2>&1; then
    # A failed live-R2 run may leave a verified partial download. Preserve the app-private bytes so
    # the next isolated retry exercises the production Range resume path instead of throwing useful
    # progress away. Tests still snapshot their own IDs and delete every monitor/event they create.
    adb -s "$SERIAL" install -r -t "$APP_APK" >"$OUTPUT_DIR/install-app.log"
    adb -s "$SERIAL" install -r -t "$TEST_APK" >"$OUTPUT_DIR/install-test.log"
else
    adb -s "$SERIAL" uninstall "$TEST_ID" >/dev/null 2>&1 || true
    adb -s "$SERIAL" uninstall "$APP_ID" >/dev/null 2>&1 || true
    adb -s "$SERIAL" install -t "$APP_APK" >"$OUTPUT_DIR/install-app.log"
    adb -s "$SERIAL" install -t "$TEST_APK" >"$OUTPUT_DIR/install-test.log"
fi
adb -s "$SERIAL" shell pm grant "$APP_ID" android.permission.CAMERA
adb -s "$SERIAL" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
if [[ "$PRESERVE_APP_DATA" == true ]]; then
    # Range-resume and exact installed-package reuse deliberately preserve app-private model bytes.
    # Diagnostics are per functional run, so prior successful events must not inflate this run's
    # assertions or evidence counts.
    adb -s "$SERIAL" shell \
        "run-as $APP_ID sh -c 'cat /dev/null > files/diagnostics/runtime.jsonl'" \
        >/dev/null 2>&1 || true
fi

pull_app_file() {
    local app_path="$1"
    local output_path="$2"
    if ! adb -s "$SERIAL" shell run-as "$APP_ID" test -f "$app_path" >/dev/null 2>&1; then
        rm -f "$output_path"
        return
    fi
    if ! adb -s "$SERIAL" exec-out run-as "$APP_ID" cat "$app_path" >"$output_path" 2>/dev/null; then
        rm -f "$output_path"
    fi
}

capture_instrumentation_failure() {
    adb -s "$SERIAL" exec-out screencap -p >"$OUTPUT_DIR/failure-post-test-screen.png" || true
    pull_app_file cache/functional-failure-screen.png "$OUTPUT_DIR/failure-screen.png"
    pull_app_file cache/functional-failure-ui.txt "$OUTPUT_DIR/failure-ui.txt"
    pull_app_file cache/functional-failure-phase.txt "$OUTPUT_DIR/failure-phase.txt"
    pull_app_file files/diagnostics/runtime.jsonl "$OUTPUT_DIR/runtime-diagnostics.jsonl"
    adb -s "$SERIAL" logcat -d -v threadtime >"$OUTPUT_DIR/failure-logcat.txt" 2>&1 || true
    adb -s "$SERIAL" shell uiautomator dump /sdcard/functional-failure-ui.xml \
        >"$OUTPUT_DIR/failure-uiautomator.log" 2>&1 || true
    adb -s "$SERIAL" exec-out cat /sdcard/functional-failure-ui.xml \
        >"$OUTPUT_DIR/failure-uiautomator.xml" 2>/dev/null || true
}

run_instrumentation() {
    local label="$1"
    local log="$OUTPUT_DIR/instrumentation-$label.log"
    set +e
    adb -s "$SERIAL" shell am instrument -w -r \
        -e class "$TEST_CLASS" \
        "${INSTRUMENTATION_ARGUMENTS[@]}" \
        "$TEST_ID/$RUNNER" 2>&1 | tee "$log"
    local status=${PIPESTATUS[0]}
    set -e
    if ((status != 0)) || ! grep -q '^OK (1 test)' "$log"; then
        capture_instrumentation_failure
        return 1
    fi
}

if [[ "$OFFLINE_REPLAY" == true ]]; then
    if ! adb -s "$SERIAL" shell am instrument -w -r \
        -e class app.beyoureyes.monitor.OfflineProductPackageSeedInstrumentedTest \
        -e seedOfflinePackages true \
        "$TEST_ID/$RUNNER" 2>&1 | tee "$OUTPUT_DIR/instrumentation-seed.log"; then
        capture_instrumentation_failure
        exit 1
    fi
    if ! grep -q '^OK (1 test)' "$OUTPUT_DIR/instrumentation-seed.log"; then
        capture_instrumentation_failure
        exit 1
    fi
    adb -s "$SERIAL" shell cmd connectivity airplane-mode enable >/dev/null
    sleep 2
    if [[ "$(adb -s "$SERIAL" shell settings get global airplane_mode_on | tr -d '\r')" != "1" ]]; then
        echo "emulator did not enter airplane mode" >&2
        exit 6
    fi
    run_instrumentation offline
    adb -s "$SERIAL" shell cmd connectivity airplane-mode disable >/dev/null || true
else
    run_instrumentation online
fi
adb -s "$SERIAL" exec-out screencap -p >"$OUTPUT_DIR/final-screen.png" || true
adb -s "$SERIAL" exec-out run-as "$APP_ID" cat files/diagnostics/runtime.jsonl \
    >"$OUTPUT_DIR/runtime-diagnostics.jsonl"

EVENT_COUNT="$(grep -c '"event":"event_inserted"' "$OUTPUT_DIR/runtime-diagnostics.jsonl" || true)"
FIRST_FRAME_COUNT="$(grep -c '"event":"monitoring_first_frame"' "$OUTPUT_DIR/runtime-diagnostics.jsonl" || true)"
OBSERVATION_COUNT="$(grep -c '"event":"monitoring_observation_available"' "$OUTPUT_DIR/runtime-diagnostics.jsonl" || true)"
NOTIFICATION_DISABLED_COUNT="$(grep -c '"event":"notification_skipped_disabled"' "$OUTPUT_DIR/runtime-diagnostics.jsonl" || true)"
EXPECTED_RUN_COUNT=1
if [[ "$FLOW" == "reading-rejection" ]]; then EXPECTED_RUN_COUNT=0; fi
if [[ "$EVENT_COUNT" != "$EXPECTED_RUN_COUNT" || "$FIRST_FRAME_COUNT" != "$EXPECTED_RUN_COUNT" ||
      "$OBSERVATION_COUNT" != "$EXPECTED_RUN_COUNT" ||
      "$NOTIFICATION_DISABLED_COUNT" != "$EXPECTED_RUN_COUNT" ]]; then
    echo "functional camera flow failed; inspect $OUTPUT_DIR" >&2
    exit 6
fi

jq -n \
    --arg recorded_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg source_head "$(git -C "$REPO_ROOT" rev-parse HEAD)" \
    --arg source_tracked_diff_sha256 "$(git -C "$REPO_ROOT" diff --binary HEAD | shasum -a 256 | awk '{print $1}')" \
    --argjson source_worktree_clean "$(if [[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then printf true; else printf false; fi)" \
    --arg flow "$FLOW" \
    --arg expected "$EXPECTED_READING" \
    --arg confirmed "$CONFIRMED_READING" \
    --arg scene "$(if [[ "$MIXED_TEXT_READING" == true ]]; then printf mixed_text; else printf isolated_value; fi)" \
    --arg catalog_url "$CATALOG_URL" \
    --arg catalog_sha256 "$CATALOG_SHA256" \
    --arg app_apk_sha256 "$(shasum -a 256 "$APP_APK" | awk '{print $1}')" \
    --argjson offline_replay "$OFFLINE_REPLAY" \
    '{schema_version:"1.0", recorded_at:$recorded_at, source_head:$source_head,
      source_worktree_clean:$source_worktree_clean,
      source_tracked_diff_sha256:$source_tracked_diff_sha256,
      evidence_level:"api36_emulator_real_camerax_static_image", flow:$flow,
      expected_reading:(if $flow == "reading" then $expected else null end),
      confirmed_reading:(if $flow == "reading" then $confirmed else null end),
      reading_scene:(if $flow == "reading" then $scene else null end),
      catalog:{url:$catalog_url,sha256:$catalog_sha256,active_package_count:3},
      offline_replay:$offline_replay,
      package_source:(if $offline_replay then "preinstalled_exact_signed_fixture_cache" else "signed_r2_https" end),
      result:(if $flow == "reading" then
        {stable_reading:true,user_confirmed_reading:$confirmed,threshold_confirmation:true,event_inserted:1,
         duplicate_events_after_15_seconds:0,notification_default_off:true,monitoring_stopped:true,
         monitor_and_event_deleted:true}
        elif $flow == "reading-rejection" then
        {nonnumeric_scene_unavailable:true,confirmation_absent:true,monitoring_not_started:true,
         event_inserted:0,draft_deleted_on_back:true}
        elif $flow == "object" then
        {apple_target_selected:true,direct_start_without_test_recognition:true,
         lite2_package_bound:true,event_inserted:1,duplicate_events_after_3_seconds:0,
         monitoring_stopped:true,monitor_and_event_deleted:true}
        else
        {three_images_imported:true,automatic_name:true,prototype_created:true,
         direct_start_without_test_recognition:true,event_inserted:1,
         trigger_snapshot_saved:true,trigger_snapshot_decodable:true,trigger_snapshot_deleted_with_monitor:true,
         duplicate_events_after_15_seconds:0,notification_default_off:true,monitoring_stopped:true,
         monitor_images_and_event_deleted:true}
        end),
      app_apk_sha256:$app_apk_sha256,physical_device:false}' \
    >"$OUTPUT_DIR/result.json"

echo "PASS: $OUTPUT_DIR/result.json"
