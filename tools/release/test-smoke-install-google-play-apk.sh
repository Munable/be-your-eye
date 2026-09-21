#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/be-your-eyes-release-smoke-test.XXXXXX")"
readonly TMP_ROOT
cleanup() {
    find "$TMP_ROOT" -depth -delete
}
trap cleanup EXIT

readonly RELEASE_DIR="$TMP_ROOT/release"
readonly BIN_DIR="$TMP_ROOT/bin"
readonly STATE_FILE="$TMP_ROOT/package-state"
readonly LOG_FILE="$TMP_ROOT/adb.log"
readonly FAIL_INSPECT_FILE="$TMP_ROOT/fail-inspect"
mkdir -p "$RELEASE_DIR/aab-smoke" "$BIN_DIR"
printf 'fixture-apk\n' > "$RELEASE_DIR/aab-smoke/universal.apk"
APK_SHA256="$(shasum -a 256 "$RELEASE_DIR/aab-smoke/universal.apk" | awk '{print $1}')"
readonly APK_SHA256
jq -n --arg sha "$APK_SHA256" '{
    status: "local_release_candidate",
    application_id: "app.beyoureyes.monitor",
    version_name: "1.0.0",
    version_code: 42,
    source: {dirty_worktree: false},
    universal_apk: {sha256: $sha}
}' > "$RELEASE_DIR/release-record.json"

cat > "$BIN_DIR/aapt" <<'EOF'
#!/usr/bin/env bash
printf "package: name='app.beyoureyes.monitor' versionCode='42' versionName='1.0.0'\n"
EOF

cat > "$BIN_DIR/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "devices" ]]; then
    printf 'List of devices attached\nfixture-physical\tdevice\n'
    exit 0
fi
if [[ "$1" != "-s" ]]; then
    exit 90
fi
shift 2
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"
case "$1" in
    get-state)
        printf 'device\n'
        ;;
    uninstall)
        printf 'absent\n' > "$FAKE_PACKAGE_STATE"
        printf 'Success\n'
        ;;
    install)
        printf 'installed\n' > "$FAKE_PACKAGE_STATE"
        printf 'Performing Streamed Install\nSuccess\n'
        ;;
    shell)
        shift
        if [[ "$*" == "pm list packages app.beyoureyes.monitor" ]]; then
            if [[ -f "$FAKE_FAIL_INSPECT_FILE" ]]; then
                printf 'fixture package manager unavailable\n' >&2
                exit 1
            fi
            if [[ "$(cat "$FAKE_PACKAGE_STATE")" == "installed" ]]; then
                printf 'package:app.beyoureyes.monitor\n'
            fi
        elif [[ "$*" == "pm path app.beyoureyes.monitor" ]]; then
            if [[ "$(cat "$FAKE_PACKAGE_STATE")" == "installed" ]]; then
                printf 'package:/data/app/fixture/base.apk\n'
            else
                exit 1
            fi
        else
            case "$1 $2" in
            'am force-stop'|'am start')
                ;;
            'dumpsys package')
                printf 'versionCode=42 minSdk=26 targetSdk=36\nversionName=1.0.0\n'
                ;;
            *) exit 91 ;;
            esac
        fi
        ;;
    *) exit 92 ;;
esac
EOF
chmod +x "$BIN_DIR/aapt" "$BIN_DIR/adb"

printf 'installed\n' > "$STATE_FILE"
FAKE_ADB_LOG="$LOG_FILE" FAKE_PACKAGE_STATE="$STATE_FILE" \
    FAKE_FAIL_INSPECT_FILE="$FAIL_INSPECT_FILE" \
    ADB_BIN="$BIN_DIR/adb" AAPT_BIN="$BIN_DIR/aapt" \
    bash "$SCRIPT_DIR/smoke-install-google-play-apk.sh" "$RELEASE_DIR" \
    > "$TMP_ROOT/result.txt"
grep -Fqx 'install_mode=clean' "$TMP_ROOT/result.txt"
grep -Fqx 'previous_install=removed' "$TMP_ROOT/result.txt"
grep -Fqx 'app_data_deletion=performed' "$TMP_ROOT/result.txt"
grep -Fqx 'uninstall app.beyoureyes.monitor' "$LOG_FILE"
grep -Fqx 'shell pm list packages app.beyoureyes.monitor' "$LOG_FILE"
if grep -Fq 'shell pm path app.beyoureyes.monitor' "$LOG_FILE"; then
    printf 'smoke install used ambiguous pm path inspection\n' >&2
    exit 1
fi
grep -Eq '^install [^-]' "$LOG_FILE"
if grep -Fq 'install -r' "$LOG_FILE"; then
    printf 'smoke install unexpectedly preserved app data\n' >&2
    exit 1
fi

# Android's legacy `pm path` can return rc=1 for an absent package. The smoke
# route must use exact package-list matching instead and treat absence normally.
printf 'absent\n' > "$STATE_FILE"
: > "$LOG_FILE"
set +e
FAKE_ADB_LOG="$LOG_FILE" FAKE_PACKAGE_STATE="$STATE_FILE" \
    FAKE_FAIL_INSPECT_FILE="$FAIL_INSPECT_FILE" \
    "$BIN_DIR/adb" -s fixture-physical shell pm path app.beyoureyes.monitor \
    >/dev/null 2>&1
legacy_absent_status=$?
set -e
if ((legacy_absent_status != 1)); then
    printf 'fixture must reproduce absent pm path rc=1\n' >&2
    exit 1
fi
FAKE_ADB_LOG="$LOG_FILE" FAKE_PACKAGE_STATE="$STATE_FILE" \
    FAKE_FAIL_INSPECT_FILE="$FAIL_INSPECT_FILE" \
    ADB_BIN="$BIN_DIR/adb" AAPT_BIN="$BIN_DIR/aapt" \
    bash "$SCRIPT_DIR/smoke-install-google-play-apk.sh" "$RELEASE_DIR" \
    > "$TMP_ROOT/absent-result.txt"
grep -Fqx 'previous_install=absent' "$TMP_ROOT/absent-result.txt"
grep -Fqx 'app_data_deletion=not_needed' "$TMP_ROOT/absent-result.txt"
grep -Fqx 'shell pm list packages app.beyoureyes.monitor' "$LOG_FILE"
if grep -Fqx 'uninstall app.beyoureyes.monitor' "$LOG_FILE"; then
    printf 'smoke install tried to uninstall an absent package\n' >&2
    exit 1
fi

printf 'absent\n' > "$STATE_FILE"
: > "$LOG_FILE"
touch "$FAIL_INSPECT_FILE"
set +e
FAKE_ADB_LOG="$LOG_FILE" FAKE_PACKAGE_STATE="$STATE_FILE" \
    FAKE_FAIL_INSPECT_FILE="$FAIL_INSPECT_FILE" \
    ADB_BIN="$BIN_DIR/adb" AAPT_BIN="$BIN_DIR/aapt" \
    bash "$SCRIPT_DIR/smoke-install-google-play-apk.sh" "$RELEASE_DIR" \
    > "$TMP_ROOT/inspect-failure.txt" 2>&1
inspect_status=$?
set -e
rm -f "$FAIL_INSPECT_FILE"
if ((inspect_status != 5)); then
    printf 'expected package inspection failure exit 5, got %s\n' "$inspect_status" >&2
    exit 1
fi
grep -Fq 'failed to inspect app.beyoureyes.monitor before installation' \
    "$TMP_ROOT/inspect-failure.txt"
if grep -Eq '^(uninstall|install) ' "$LOG_FILE"; then
    printf 'smoke install mutated the device after package inspection failure\n' >&2
    exit 1
fi

jq '.universal_apk.sha256 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"' \
    "$RELEASE_DIR/release-record.json" > "$TMP_ROOT/bad-record.json"
mv "$TMP_ROOT/bad-record.json" "$RELEASE_DIR/release-record.json"
set +e
FAKE_ADB_LOG="$LOG_FILE" FAKE_PACKAGE_STATE="$STATE_FILE" \
    FAKE_FAIL_INSPECT_FILE="$FAIL_INSPECT_FILE" \
    ADB_BIN="$BIN_DIR/adb" AAPT_BIN="$BIN_DIR/aapt" \
    bash "$SCRIPT_DIR/smoke-install-google-play-apk.sh" "$RELEASE_DIR" \
    > "$TMP_ROOT/bad-result.txt" 2>&1
status=$?
set -e
if ((status != 3)); then
    printf 'expected SHA mismatch exit 3, got %s\n' "$status" >&2
    exit 1
fi
grep -Fq 'does not match release record' "$TMP_ROOT/bad-result.txt"

printf 'Google Play universal APK clean-install smoke test passed\n'
