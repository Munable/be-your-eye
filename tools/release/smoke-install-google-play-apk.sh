#!/usr/bin/env bash
set -euo pipefail

readonly APP_ID="app.beyoureyes.monitor"
readonly MAIN_ACTIVITY="app.beyoureyes.monitor.MainActivity"
readonly ADB_BIN="${ADB_BIN:-adb}"
SERIAL=""

usage() {
    printf 'usage: %s RELEASE_DIR [--serial SERIAL]\n' "$0" >&2
}

if (($# < 1)); then
    usage
    exit 2
fi
RELEASE_DIR="$1"
shift
while (($#)); do
    case "$1" in
        --serial)
            if (($# < 2)) || [[ -z "$2" ]]; then
                usage
                exit 2
            fi
            SERIAL="$2"
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

readonly RELEASE_RECORD="$RELEASE_DIR/release-record.json"
readonly APK="$RELEASE_DIR/aab-smoke/universal.apk"
test -s "$RELEASE_RECORD"
test -s "$APK"

if [[ "$(jq -r '.status' "$RELEASE_RECORD")" != "local_release_candidate" ]] ||
   [[ "$(jq -r '.application_id' "$RELEASE_RECORD")" != "$APP_ID" ]] ||
   [[ "$(jq -r '.source.dirty_worktree' "$RELEASE_RECORD")" != "false" ]]; then
    printf 'release record is not an immutable production candidate\n' >&2
    exit 3
fi

EXPECTED_VERSION_NAME="$(jq -er '.version_name' "$RELEASE_RECORD")"
readonly EXPECTED_VERSION_NAME
EXPECTED_VERSION_CODE="$(jq -er '.version_code | tostring' "$RELEASE_RECORD")"
readonly EXPECTED_VERSION_CODE
EXPECTED_APK_SHA256="$(jq -er '.universal_apk.sha256' "$RELEASE_RECORD")"
readonly EXPECTED_APK_SHA256
ACTUAL_APK_SHA256="$(shasum -a 256 "$APK" | awk '{print $1}')"
readonly ACTUAL_APK_SHA256
if [[ "$ACTUAL_APK_SHA256" != "$EXPECTED_APK_SHA256" ]]; then
    printf 'universal APK SHA-256 does not match release record\n' >&2
    exit 3
fi

AAPT_BIN="${AAPT_BIN:-}"
if [[ -z "$AAPT_BIN" ]]; then
    AAPT_BIN="$(find /opt/homebrew/share/android-commandlinetools/build-tools \
        -name aapt -type f -print 2>/dev/null | sort -V | tail -1)"
fi
if [[ ! -x "$AAPT_BIN" ]]; then
    printf 'aapt is not executable; set AAPT_BIN\n' >&2
    exit 3
fi
BADGING="$($AAPT_BIN dump badging "$APK" | head -1)"
if [[ "$BADGING" != *"name='$APP_ID'"* ]] ||
   [[ "$BADGING" != *"versionCode='$EXPECTED_VERSION_CODE'"* ]] ||
   [[ "$BADGING" != *"versionName='$EXPECTED_VERSION_NAME'"* ]]; then
    printf 'universal APK identity does not match release record\n' >&2
    exit 3
fi

if [[ -z "$SERIAL" ]]; then
    PHYSICAL_DEVICES="$($ADB_BIN devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {print $1}')"
    if [[ "$(printf '%s\n' "$PHYSICAL_DEVICES" | awk 'NF {count++} END {print count+0}')" != "1" ]]; then
        printf 'expected one authorized physical device; pass --serial when needed\n' >&2
        exit 4
    fi
    SERIAL="$(printf '%s\n' "$PHYSICAL_DEVICES" | awk 'NF {print; exit}')"
fi

device_adb() {
    "$ADB_BIN" -s "$SERIAL" "$@"
}

package_is_installed() {
    local package_list
    if ! package_list="$(device_adb shell pm list packages "$APP_ID" 2>&1)"; then
        printf 'failed to inspect %s before installation: %s\n' \
            "$APP_ID" "$package_list" >&2
        return 2
    fi
    grep -Fqx "package:$APP_ID" <<<"$(tr -d '\r' <<<"$package_list")"
}

if [[ "$(device_adb get-state 2>/dev/null || true)" != "device" ]]; then
    printf 'selected Android device is not authorized\n' >&2
    exit 4
fi

PREVIOUS_INSTALL="absent"
if package_is_installed; then
    UNINSTALL_OUTPUT="$(device_adb uninstall "$APP_ID" 2>&1)"
    if ! grep -Fqx 'Success' <<<"$(tr -d '\r' <<<"$UNINSTALL_OUTPUT")"; then
        printf 'clean smoke install stopped: uninstall did not report success\n' >&2
        exit 5
    fi
    if package_is_installed; then
        printf 'clean smoke install stopped: production package remains installed\n' >&2
        exit 5
    else
        package_status=$?
        if ((package_status != 1)); then
            exit 5
        fi
    fi
    PREVIOUS_INSTALL="removed"
else
    package_status=$?
    if ((package_status != 1)); then
        exit 5
    fi
fi

device_adb install "$APK"
device_adb shell am force-stop "$APP_ID"
device_adb shell am start -W -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null

INSTALLED_DUMP="$(device_adb shell dumpsys package "$APP_ID" | tr -d '\r')"
if ! grep -Fq "versionCode=$EXPECTED_VERSION_CODE " <<<"$INSTALLED_DUMP" ||
   ! grep -Fq "versionName=$EXPECTED_VERSION_NAME" <<<"$INSTALLED_DUMP"; then
    printf 'installed package version does not match release record\n' >&2
    exit 6
fi

printf 'install_mode=clean\n'
printf 'application_id=%s\n' "$APP_ID"
printf 'previous_install=%s\n' "$PREVIOUS_INSTALL"
printf 'app_data_deletion=%s\n' "$([[ "$PREVIOUS_INSTALL" == "removed" ]] && printf performed || printf not_needed)"
printf 'version_name=%s\n' "$EXPECTED_VERSION_NAME"
printf 'version_code=%s\n' "$EXPECTED_VERSION_CODE"
printf 'apk_sha256=%s\n' "$ACTUAL_APK_SHA256"
