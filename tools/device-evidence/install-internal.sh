#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
readonly ROOT_DIR
readonly ENV_FILE="${BEYOUREYES_ENV_FILE:-$ROOT_DIR/.env.local}"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly EXPECTED_CATALOG_URL="https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json"
readonly EXPECTED_VERSION_NAME="0.2.24-dev-internal"
readonly EXPECTED_VERSION_CODE="25"
readonly APP_ID="app.beyoureyes.monitor.internal"
readonly ADB_BIN="${ADB_BIN:-adb}"
BUILD_ONLY=false
CLEAN_INSTALL=false
SERIAL=""

usage() {
    echo "usage: $0 [--build-only | --clean] [--serial SERIAL]" >&2
}

parse_args() {
    while (($#)); do
        case "$1" in
            --build-only) BUILD_ONLY=true; shift ;;
            --clean) CLEAN_INSTALL=true; shift ;;
            --serial)
                if (($# < 2)) || [[ -z "$2" ]]; then
                    echo "missing serial" >&2
                    usage
                    return 2
                fi
                SERIAL="$2"
                shift 2
                ;;
            *) usage; return 2 ;;
        esac
    done
    if [[ "$BUILD_ONLY" == true && "$CLEAN_INSTALL" == true ]]; then
        echo "--clean cannot be combined with --build-only" >&2
        usage
        return 2
    fi
}

device_adb() {
    "$ADB_BIN" -s "$SERIAL" "$@"
}

device_is_credential_locked() {
    local trust_dump
    trust_dump="$(device_adb shell dumpsys trust 2>/dev/null || true)"
    grep -Fq 'deviceLocked=1' <<<"$trust_dump"
}

package_is_installed() {
    local package_list
    if ! package_list="$(device_adb shell pm list packages "$APP_ID" 2>&1)"; then
        echo "failed to inspect $APP_ID before installation: $package_list" >&2
        return 2
    fi
    grep -Fqx "package:$APP_ID" <<<"$(tr -d '\r' <<<"$package_list")"
}

install_internal_apk() {
    local apk="$1"
    local previous_install="preserved"
    local app_data_deletion="not_requested"
    local package_status
    local uninstall_output

    if [[ "$CLEAN_INSTALL" == true ]]; then
        if package_is_installed; then
            if ! uninstall_output="$(device_adb uninstall "$APP_ID" 2>&1)"; then
                echo "clean install stopped: uninstall failed for $APP_ID: $uninstall_output" >&2
                return 7
            fi
            if ! grep -Fqx 'Success' <<<"$(tr -d '\r' <<<"$uninstall_output")"; then
                echo "clean install stopped: uninstall did not report success for $APP_ID: $uninstall_output" >&2
                return 7
            fi
            printf '%s\n' "$uninstall_output"
            if package_is_installed; then
                echo "clean install stopped: $APP_ID remains installed after uninstall" >&2
                return 7
            else
                package_status=$?
                if ((package_status != 1)); then
                    return "$package_status"
                fi
            fi
            previous_install="removed"
            app_data_deletion="performed"
        else
            package_status=$?
            if ((package_status != 1)); then
                return "$package_status"
            fi
            previous_install="absent"
            app_data_deletion="not_needed"
        fi
        device_adb install "$apk"
    else
        device_adb install -r "$apk"
    fi

    device_adb shell pm grant "$APP_ID" android.permission.CAMERA >/dev/null 2>&1 || true
    device_adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    device_adb shell am force-stop "$APP_ID"
    if device_is_credential_locked; then
        echo "installed $APP_ID but launch is deferred: unlock the device and rerun" >&2
        return 8
    fi
    if ! device_adb shell am start -W -n "$APP_ID/app.beyoureyes.monitor.MainActivity" >/dev/null; then
        echo "installed $APP_ID but could not launch MainActivity" >&2
        return 8
    fi

    local model
    local sdk
    model="$(device_adb shell getprop ro.product.model | tr -d '\r')"
    sdk="$(device_adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "installed internal-evaluation APK on $model (API $sdk)"
    echo "install_mode=$([[ "$CLEAN_INSTALL" == true ]] && echo clean || echo upgrade)"
    echo "previous_install=$previous_install"
    echo "app_data_deletion=$app_data_deletion"
}

main() {
    parse_args "$@"

    if [[ ! -r "$ENV_FILE" ]]; then
        echo "missing readable local product configuration: $ENV_FILE" >&2
        exit 3
    fi
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
    if [[ "${MODEL_CATALOG_URL:-}" != "$EXPECTED_CATALOG_URL" ]]; then
        echo "MODEL_CATALOG_URL must be the current three-package Be Your Eye internal Catalog" >&2
        exit 4
    fi
    for name in SUPABASE_URL SUPABASE_PUBLISHABLE_KEY FIREBASE_API_KEY \
        FIREBASE_APPLICATION_ID FIREBASE_PROJECT_ID FIREBASE_GCM_SENDER_ID \
        PRIVACY_POLICY_URL; do
        if [[ -z "${!name:-}" ]]; then
            echo "missing local product configuration: $name" >&2
            exit 4
        fi
    done

    (
        cd "$ROOT_DIR/android"
        env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
            ./gradlew --no-daemon --stacktrace \
                -PMODEL_CATALOG_URL="$MODEL_CATALOG_URL" \
                :app:assembleInternal
    )

    local build_config
    build_config="$(find "$ROOT_DIR/android/app/build/generated/source/buildConfig/internal" \
        -name BuildConfig.java -print -quit)"
    if [[ -z "$build_config" ]] ||
       ! grep -q 'BUILD_CHANNEL = "internal-evaluation"' "$build_config" ||
       ! grep -q 'BUILD_IDENTITY = "internal-evaluation"' "$build_config" ||
       ! grep -Fq "MODEL_CATALOG_URL = \"$EXPECTED_CATALOG_URL\"" "$build_config" ||
       ! grep -Fq "VERSION_CODE = $EXPECTED_VERSION_CODE" "$build_config" ||
       ! grep -Fq "VERSION_NAME = \"$EXPECTED_VERSION_NAME\"" "$build_config" ||
       grep -Eq '(SUPABASE_URL|SUPABASE_PUBLISHABLE_KEY|FIREBASE_API_KEY|FIREBASE_APPLICATION_ID|FIREBASE_PROJECT_ID|FIREBASE_GCM_SENDER_ID|PRIVACY_POLICY_URL) = ""' "$build_config"; then
        echo "refusing to install: internal APK identity or Catalog is wrong" >&2
        exit 5
    fi

    local apk="$ROOT_DIR/android/app/build/outputs/apk/internal/app-internal.apk"
    test -s "$apk"
    apk_dex_contains() {
        local expected="$1"
        local dex
        while IFS= read -r dex; do
            if unzip -p "$apk" "$dex" | strings | grep -F "$expected" >/dev/null; then
                return 0
            fi
        done < <(zipinfo -1 "$apk" 'classes*.dex')
        return 1
    }
    if ! apk_dex_contains "$EXPECTED_CATALOG_URL" ||
       ! apk_dex_contains 'internal-evaluation'; then
        echo "refusing to install: packaged DEX does not contain the verified internal identity" >&2
        exit 5
    fi
    local apk_sha256
    apk_sha256="$(shasum -a 256 "$apk" | awk '{print $1}')"
    if [[ "$BUILD_ONLY" == true ]]; then
        echo "internal APK ready: $apk"
        echo "apk_sha256=$apk_sha256"
        exit 0
    fi

    if [[ -z "$SERIAL" ]]; then
        local physical_devices
        physical_devices="$($ADB_BIN devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {print $1}')"
        if [[ "$(printf '%s\n' "$physical_devices" | awk 'NF {count++} END {print count+0}')" != "1" ]]; then
            echo "expected one authorized physical device; pass --serial when needed" >&2
            "$ADB_BIN" devices -l >&2
            exit 6
        fi
        SERIAL="$(printf '%s\n' "$physical_devices" | awk 'NF {print; exit}')"
    fi
    if [[ "$(device_adb get-state 2>/dev/null || true)" != "device" ]]; then
        echo "selected Android device is not authorized" >&2
        exit 6
    fi

    install_internal_apk "$apk"
    echo "apk_sha256=$apk_sha256"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
