#!/usr/bin/env bash
set -euo pipefail

readonly OUTPUT_DIR="${1:-$HOME/.config/be-your-eyes/android-upload}"
readonly KEYSTORE="$OUTPUT_DIR/google-play-upload.jks"
readonly CERTIFICATE="$OUTPUT_DIR/google-play-upload-certificate.pem"
readonly ENV_FILE="$OUTPUT_DIR/release.env"
readonly KEY_ALIAS="be-your-eyes-upload"
readonly KEYTOOL="${KEYTOOL:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool}"

if [[ ! -x "$KEYTOOL" ]]; then
    printf 'keytool is not executable: %s\n' "$KEYTOOL" >&2
    exit 1
fi
if [[ -e "$KEYSTORE" || -e "$ENV_FILE" ]]; then
    printf 'refusing to overwrite existing Google Play upload material in %s\n' "$OUTPUT_DIR" >&2
    exit 1
fi

umask 077
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"

random_secret() {
    openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n'
}

STORE_PASSWORD="$(random_secret)"
readonly STORE_PASSWORD
KEY_PASSWORD="$(random_secret)"
readonly KEY_PASSWORD

"$KEYTOOL" -genkeypair -noprompt \
    -keystore "$KEYSTORE" \
    -storetype JKS \
    -storepass "$STORE_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASSWORD" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 9125 \
    -dname "CN=Be Your Eye Google Play Upload, OU=Release"

"$KEYTOOL" -exportcert -rfc \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -file "$CERTIFICATE"

cat > "$ENV_FILE" <<EOF
# Repository-external Google Play release inputs. Keep mode 0600 and never commit.
export BEYOUREYES_RELEASE_STORE_FILE='$KEYSTORE'
export BEYOUREYES_RELEASE_STORE_PASSWORD='$STORE_PASSWORD'
export BEYOUREYES_RELEASE_KEY_ALIAS='$KEY_ALIAS'
export BEYOUREYES_RELEASE_KEY_PASSWORD='$KEY_PASSWORD'

# Fill only after the exact current and rollback commercial releases and the
# public policy URL are frozen. The bundle builder requires all four model
# metadata URLs and fails before Gradle when any value is absent.
export BEYOUREYES_VERSION_CODE=''
export BEYOUREYES_VERSION_NAME=''
export MODEL_CATALOG_URL=''
export MODEL_RELEASE_URL=''
export MODEL_ROLLBACK_CATALOG_URL=''
export MODEL_ROLLBACK_RELEASE_URL=''
export PRIVACY_POLICY_URL=''
export SUPABASE_URL=''
export SUPABASE_PUBLISHABLE_KEY=''
export FIREBASE_API_KEY=''
export FIREBASE_APPLICATION_ID=''
export FIREBASE_PROJECT_ID=''
export FIREBASE_GCM_SENDER_ID=''
EOF

chmod 600 "$KEYSTORE" "$CERTIFICATE" "$ENV_FILE"

CERT_SHA256="$(
    "$KEYTOOL" -list -v \
        -keystore "$KEYSTORE" \
        -storepass "$STORE_PASSWORD" \
        -alias "$KEY_ALIAS" |
        awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}'
)"
readonly CERT_SHA256

printf 'upload_keystore=%s\n' "$KEYSTORE"
printf 'upload_certificate=%s\n' "$CERTIFICATE"
printf 'release_env=%s\n' "$ENV_FILE"
printf 'certificate_sha256=%s\n' "$CERT_SHA256"
