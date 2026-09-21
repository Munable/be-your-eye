#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT

usage() {
    cat >&2 <<'EOF'
usage: check-secret-hygiene.sh [--path FILE_OR_DIRECTORY ...]

With no --path, scan tracked repository text. Each --path adds a release
artifact or log tree to scan. Matches are reported as paths only; secret
values are never printed.
EOF
}

declare -a SCAN_PATHS=()
while (($# > 0)); do
    case "$1" in
        --path)
            (($# >= 2)) || { usage; exit 64; }
            SCAN_PATHS+=("$2")
            shift 2
            ;;
        -h|--help)
            usage >&1
            exit 0
            ;;
        *)
            usage
            exit 64
            ;;
    esac
done

# Keep this expression compatible with both POSIX ERE (git grep -E) and
# ripgrep's default regex engine. Public Firebase/Supabase configuration is
# intentionally not treated as secret.
readonly SECRET_PATTERN='sk-[A-Za-z0-9_-]{20,}|([rs]k_(live|test)|whsec)_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
readonly RG_BIN="${RG_BIN:-rg}"

report_matches() {
    local label="$1"
    local source="$2"
    local match_file
    local scan_status
    match_file="$(mktemp)"

    if [[ -d "$source" ]]; then
        # Avoid model/media binaries; the caller can pass their extracted
        # strings file when binary inspection is required at release time.
        if "$RG_BIN" --hidden --no-messages --files-with-matches \
            --glob '!.git/**' \
            --glob '!*.apk' --glob '!*.aab' --glob '!*.aar' --glob '!*.jar' \
            --glob '!*.onnx' --glob '!*.tflite' --glob '!*.bin' --glob '!*.zip' \
            --glob '!*.png' --glob '!*.jpg' --glob '!*.jpeg' --glob '!*.webp' \
            -e "$SECRET_PATTERN" "$source" >"$match_file"; then
            scan_status=0
        else
            scan_status=$?
        fi
    elif [[ -f "$source" ]]; then
        if "$RG_BIN" --no-messages --files-with-matches \
            -e "$SECRET_PATTERN" "$source" >"$match_file"; then
            scan_status=0
        else
            scan_status=$?
        fi
    else
        rm -f "$match_file"
        printf 'secret hygiene path does not exist: %s\n' "$source" >&2
        return 2
    fi

    case "$scan_status" in
        0)
            printf '%s: secret-like material found in %s\n' "$label" "$source" >&2
            sort -u "$match_file" | sed 's/^/  /' >&2
            rm -f "$match_file"
            return 1
            ;;
        1)
            rm -f "$match_file"
            return 0
            ;;
        *)
            rm -f "$match_file"
            printf '%s: scanner failed for %s (status %d)\n' \
                "$label" "$source" "$scan_status" >&2
            return 2
            ;;
    esac
}

scan_tracked_repository() {
    local match_file
    local scan_status
    match_file="$(mktemp)"

    if git -C "$REPO_ROOT" grep -I -l -E -e "$SECRET_PATTERN" -- . >"$match_file"; then
        scan_status=0
    else
        scan_status=$?
    fi

    case "$scan_status" in
        0)
            printf 'tracked repository: secret-like material found\n' >&2
            sort -u "$match_file" | sed 's/^/  /' >&2
            rm -f "$match_file"
            return 1
            ;;
        1)
            rm -f "$match_file"
            return 0
            ;;
        *)
            rm -f "$match_file"
            printf 'tracked repository: scanner failed (git grep status %d)\n' \
                "$scan_status" >&2
            return 2
            ;;
    esac
}

failures=0
if ((${#SCAN_PATHS[@]} == 0)); then
    if scan_tracked_repository; then
        :
    else
        failures=$((failures + 1))
    fi
else
    for source in "${SCAN_PATHS[@]}"; do
        if report_matches "artifact scan" "$source"; then
            :
        else
            failures=$((failures + 1))
        fi
    done
fi

if ((failures > 0)); then
    exit 2
fi
printf 'secret hygiene passed: no provider keys or private-key material found\n'
