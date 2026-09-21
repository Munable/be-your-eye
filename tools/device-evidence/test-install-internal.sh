#!/usr/bin/env bash
set -euo pipefail

TEST_ROOT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
readonly TEST_ROOT_DIR
# shellcheck source=tools/device-evidence/install-internal.sh
source "$TEST_ROOT_DIR/tools/device-evidence/install-internal.sh"

TMP_DIR="$(mktemp -d)"
readonly TMP_DIR
trap 'rm -rf "$TMP_DIR"' EXIT
LOG_FILE="$TMP_DIR/adb.log"
STATE_FILE="$TMP_DIR/package-state"
FAIL_UNINSTALL_FILE="$TMP_DIR/fail-uninstall"
FAIL_INSPECT_FILE="$TMP_DIR/fail-inspect"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

if [[ "$EXPECTED_CATALOG_URL" != \
    "https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json" ]]; then
    fail "install helper is not bound to the current 2026.09.15.1 Catalog"
fi

assert_output() {
    local output="$1"
    local expected="$2"
    grep -Fqx "$expected" <<<"$output" || fail "missing output: $expected"
}

assert_log_contains() {
    local expected="$1"
    grep -Fqx -- "$expected" "$LOG_FILE" || fail "missing adb call: $expected"
}

assert_log_excludes() {
    local unexpected="$1"
    if grep -Fqx -- "$unexpected" "$LOG_FILE"; then
        fail "unexpected adb call: $unexpected"
    fi
}

device_adb() {
    printf '%s\n' "$*" >>"$LOG_FILE"
    case "$*" in
        "shell pm list packages $APP_ID")
            if [[ -f "$FAIL_INSPECT_FILE" ]]; then
                echo "fixture package manager unavailable"
                return 1
            fi
            if [[ "$(cat "$STATE_FILE")" == "installed" ]]; then
                echo "package:$APP_ID"
            fi
            ;;
        "uninstall $APP_ID")
            if [[ -f "$FAIL_UNINSTALL_FILE" ]]; then
                echo "Failure [DELETE_FAILED_INTERNAL_ERROR]"
                return 1
            fi
            echo "absent" >"$STATE_FILE"
            echo "Success"
            ;;
        "install /fixture/app-internal.apk" | "install -r /fixture/app-internal.apk")
            echo "installed" >"$STATE_FILE"
            echo "Success"
            ;;
        "shell getprop ro.product.model") echo "Fixture Phone" ;;
        "shell getprop ro.build.version.sdk") echo "36" ;;
        *) ;;
    esac
}

if (BUILD_ONLY=false; CLEAN_INSTALL=false; SERIAL=""; parse_args --clean --build-only) \
    >/dev/null 2>&1; then
    fail "--clean and --build-only must be rejected"
fi

SERIAL="FIXTURE_SERIAL"

echo "installed" >"$STATE_FILE"
: >"$LOG_FILE"
CLEAN_INSTALL=true
clean_output="$(install_internal_apk /fixture/app-internal.apk)"
assert_log_contains "shell pm list packages $APP_ID"
assert_log_contains "uninstall $APP_ID"
assert_log_contains "install /fixture/app-internal.apk"
assert_log_excludes "install -r /fixture/app-internal.apk"
uninstall_line="$(grep -nFx -- "uninstall $APP_ID" "$LOG_FILE" | cut -d: -f1)"
install_line="$(grep -nFx -- "install /fixture/app-internal.apk" "$LOG_FILE" | cut -d: -f1)"
((uninstall_line < install_line)) || fail "clean install ran before uninstall"
assert_output "$clean_output" "install_mode=clean"
assert_output "$clean_output" "previous_install=removed"
assert_output "$clean_output" "app_data_deletion=performed"

echo "absent" >"$STATE_FILE"
: >"$LOG_FILE"
clean_absent_output="$(install_internal_apk /fixture/app-internal.apk)"
assert_log_excludes "uninstall $APP_ID"
assert_log_contains "install /fixture/app-internal.apk"
assert_output "$clean_absent_output" "install_mode=clean"
assert_output "$clean_absent_output" "previous_install=absent"
assert_output "$clean_absent_output" "app_data_deletion=not_needed"

echo "installed" >"$STATE_FILE"
: >"$LOG_FILE"
CLEAN_INSTALL=false
upgrade_output="$(install_internal_apk /fixture/app-internal.apk)"
assert_log_excludes "uninstall $APP_ID"
assert_log_contains "install -r /fixture/app-internal.apk"
assert_output "$upgrade_output" "install_mode=upgrade"
assert_output "$upgrade_output" "previous_install=preserved"
assert_output "$upgrade_output" "app_data_deletion=not_requested"

echo "installed" >"$STATE_FILE"
: >"$LOG_FILE"
touch "$FAIL_UNINSTALL_FILE"
CLEAN_INSTALL=true
if failed_output="$(install_internal_apk /fixture/app-internal.apk 2>&1)"; then
    fail "clean install must stop after an uninstall failure"
fi
assert_log_contains "uninstall $APP_ID"
assert_log_excludes "install /fixture/app-internal.apk"
grep -Fq "clean install stopped: uninstall failed" <<<"$failed_output" || \
    fail "missing uninstall failure diagnostic"

rm -f "$FAIL_UNINSTALL_FILE"
touch "$FAIL_INSPECT_FILE"
: >"$LOG_FILE"
if inspect_output="$(install_internal_apk /fixture/app-internal.apk 2>&1)"; then
    fail "clean install must stop when package inspection fails"
fi
assert_log_contains "shell pm list packages $APP_ID"
assert_log_excludes "uninstall $APP_ID"
assert_log_excludes "install /fixture/app-internal.apk"
grep -Fq "failed to inspect $APP_ID before installation" <<<"$inspect_output" || \
    fail "missing package inspection failure diagnostic"

echo "install-internal shell tests passed"
