#!/usr/bin/env bash

# Creates confirmed disposable users without weakening production email confirmation.
# The service key stays in the macOS Keychain and is never written to a file or log.

hosted_test_account_create() {
    local supabase_url="$1"
    local email="$2"
    local password="$3"
    local keychain_service="${BEYOUREYES_SUPABASE_SECRET_KEYCHAIN_SERVICE:-be-your-eyes.supabase.secret}"
    local secret response body http_code message

    secret="$(security find-generic-password -s "$keychain_service" -w)" || {
        echo "missing Supabase secret key in macOS Keychain service $keychain_service" >&2
        return 1
    }
    response="$(curl --noproxy '*' --silent --show-error \
        --request POST \
        --header "apikey: $secret" \
        --header "Authorization: Bearer $secret" \
        --header 'Content-Type: application/json' \
        --data "$(jq -nc --arg email "$email" --arg password "$password" \
            '{email:$email,password:$password,email_confirm:true}')" \
        --write-out $'\n%{http_code}' \
        "$supabase_url/auth/v1/admin/users")"
    unset secret
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ "$http_code" != "200" ]]; then
        message="$(jq -r '.msg // .message // .error // "unknown response"' <<<"$body" 2>/dev/null)"
        echo "failed to create confirmed hosted test account (HTTP $http_code): $message" >&2
        return 1
    fi
    jq -er '.id' <<<"$body"
}

hosted_test_account_delete() {
    local supabase_url="$1"
    local user_id="$2"
    local keychain_service="${BEYOUREYES_SUPABASE_SECRET_KEYCHAIN_SERVICE:-be-your-eyes.supabase.secret}"
    local secret response body http_code message

    [[ -n "$user_id" ]] || return 0
    secret="$(security find-generic-password -s "$keychain_service" -w)" || return 1
    response="$(curl --noproxy '*' --silent --show-error \
        --request DELETE \
        --header "apikey: $secret" \
        --header "Authorization: Bearer $secret" \
        --write-out $'\n%{http_code}' \
        "$supabase_url/auth/v1/admin/users/$user_id")"
    unset secret
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ "$http_code" != "200" && "$http_code" != "404" ]]; then
        message="$(jq -r '.msg // .message // .error // "unknown response"' <<<"$body" 2>/dev/null)"
        echo "failed to delete hosted test account (HTTP $http_code): $message" >&2
        return 1
    fi
}

hosted_test_account_grant_product_access() {
    local supabase_url="$1"
    local user_id="$2"
    local keychain_service="${BEYOUREYES_SUPABASE_SECRET_KEYCHAIN_SERVICE:-be-your-eyes.supabase.secret}"
    local secret account_hash token_hash expires_at response body http_code message

    account_hash="$(printf 'hosted-acceptance-account:%s' "$user_id" | shasum -a 256 | awk '{print $1}')"
    token_hash="$(printf 'hosted-acceptance-token:%s' "$user_id" | shasum -a 256 | awk '{print $1}')"
    expires_at="$(date -u -v+1H '+%Y-%m-%dT%H:%M:%SZ')"
    secret="$(security find-generic-password -s "$keychain_service" -w)" || return 1
    response="$(curl --noproxy '*' --silent --show-error \
        --request POST \
        --header "apikey: $secret" \
        --header "Authorization: Bearer $secret" \
        --header 'Content-Type: application/json' \
        --header 'Prefer: return=minimal' \
        --data "$(jq -nc \
            --arg account_id "$user_id" \
            --arg account_hash "$account_hash" \
            --arg token_hash "$token_hash" \
            --arg expires_at "$expires_at" \
            '{account_id:$account_id,tier:"pro",source:"google_play",
              product_id:"be_your_eye_pro",obfuscated_account_id:$account_hash,
              purchase_token_sha256:$token_hash,subscription_state:"SUBSCRIPTION_STATE_ACTIVE",
              expires_at:$expires_at,verified_at:(now|todateiso8601)}')" \
        --write-out $'\n%{http_code}' \
        "$supabase_url/rest/v1/account_entitlements")"
    unset secret
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ "$http_code" != "201" ]]; then
        message="$(jq -r '.message // .msg // .error // "unknown response"' <<<"$body" 2>/dev/null)"
        echo "failed to grant hosted test product access (HTTP $http_code): $message" >&2
        return 1
    fi
}
