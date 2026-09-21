#!/usr/bin/env bash
set -euo pipefail

readonly PUBLIC_SERVICE_INPUTS=(
    SUPABASE_URL
    SUPABASE_PUBLISHABLE_KEY
    FIREBASE_API_KEY
    FIREBASE_APPLICATION_ID
    FIREBASE_PROJECT_ID
    FIREBASE_GCM_SENDER_ID
)

for name in "${PUBLIC_SERVICE_INPUTS[@]}"; do
    value="${!name:-}"
    if [[ -z "$value" ]]; then
        printf 'public release input is empty: %s\n' "$name" >&2
        exit 1
    fi
    normalized="$(printf '%s' "$value" | LC_ALL=C tr '[:lower:]' '[:upper:]')"
    if [[ "$normalized" == PUBLIC_* ]] ||
       [[ "$normalized" == *PLACEHOLDER* ]] ||
       [[ "$normalized" == *CHANGEME* ]] ||
       [[ "$normalized" == 'HTTPS://PROJECT.SUPABASE.CO' ]] ||
       [[ "$value" == *示例* ]]; then
        printf 'public release input is still a placeholder: %s\n' "$name" >&2
        exit 1
    fi
done
