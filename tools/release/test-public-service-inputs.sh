#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
readonly VALID_VALUES=(
    "SUPABASE_URL=https://prod-ref.supabase.co"
    "SUPABASE_PUBLISHABLE_KEY=sb_publishable_live_0123456789abcdef"
    "FIREBASE_API_KEY=AIzaSy0123456789abcdefghijklmnopqrstuvwxyz"
    "FIREBASE_APPLICATION_ID=1:1234567890:android:abcdef0123456789"
    "FIREBASE_PROJECT_ID=be-your-eyes-production"
    "FIREBASE_GCM_SENDER_ID=1234567890"
)
readonly NAMES=(
    SUPABASE_URL
    SUPABASE_PUBLISHABLE_KEY
    FIREBASE_API_KEY
    FIREBASE_APPLICATION_ID
    FIREBASE_PROJECT_ID
    FIREBASE_GCM_SENDER_ID
)

run_validator() {
    env "${VALID_VALUES[@]}" "$@" bash "$SCRIPT_DIR/validate-public-service-inputs.sh"
}

run_validator
passed=1
for name in "${NAMES[@]}"; do
    for placeholder in PUBLIC_VALUE PLACEHOLDER CHANGEME '示例值'; do
        if run_validator "$name=$placeholder" >/dev/null 2>&1; then
            printf 'placeholder unexpectedly passed: %s=%s\n' "$name" "$placeholder" >&2
            exit 1
        fi
        passed=$((passed + 1))
    done
done
if run_validator 'SUPABASE_URL=https://PROJECT.supabase.co' >/dev/null 2>&1; then
    printf 'repository Supabase example URL unexpectedly passed\n' >&2
    exit 1
fi
passed=$((passed + 1))

printf 'public release placeholder gate: %s/26 passed\n' "$passed"
