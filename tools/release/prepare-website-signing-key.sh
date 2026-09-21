#!/usr/bin/env bash
set -euo pipefail

# This is the actual APK signing identity, not the separate Google Play upload key.
readonly SIGNING_DIR="$HOME/.config/be-your-eyes/android-app-signing"
readonly BACKUP_DIR="/Volumes/DevDisk/DeveloperData/be-your-eyes/repo-local/signing-backup/website"
readonly KEYTOOL="${KEYTOOL:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool}"
readonly KEYSTORE="$SIGNING_DIR/be-your-eye.jks"
readonly CERTIFICATE="$SIGNING_DIR/be-your-eye-certificate.pem"
readonly ENV_FILE="$SIGNING_DIR/signing.env"
test -x "$KEYTOOL"
test -d /Volumes/DevDisk/DeveloperData/be-your-eyes
if [[ -e "$SIGNING_DIR" || -e "$BACKUP_DIR" ]]; then
    printf 'Signing material already exists; reuse it, never regenerate an update identity.\n' >&2
    exit 1
fi
umask 077
mkdir -p "$SIGNING_DIR" "$BACKUP_DIR"
chmod 700 "$SIGNING_DIR" "$BACKUP_DIR"
WEBSITE_STORE_PASSWORD="$(openssl rand -hex 32)"
WEBSITE_KEY_PASSWORD="$(openssl rand -hex 32)"
export WEBSITE_STORE_PASSWORD WEBSITE_KEY_PASSWORD
"$KEYTOOL" -genkeypair -noprompt -keystore "$KEYSTORE" -storetype JKS \
    -storepass:env WEBSITE_STORE_PASSWORD -keypass:env WEBSITE_KEY_PASSWORD \
    -alias be-your-eye -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
    -dname 'CN=Be Your Eye, O=Marine Mystique Solutions Limited'
"$KEYTOOL" -exportcert -rfc -keystore "$KEYSTORE" -storepass:env WEBSITE_STORE_PASSWORD \
    -alias be-your-eye -file "$CERTIFICATE"
{
    printf '# Actual app signing key. Private, repository-external; do not regenerate.\n'
    printf 'export BEYOUREYES_RELEASE_STORE_FILE=%q\n' "$KEYSTORE"
    printf 'export BEYOUREYES_RELEASE_STORE_PASSWORD=%q\n' "$WEBSITE_STORE_PASSWORD"
    printf 'export BEYOUREYES_RELEASE_KEY_ALIAS=be-your-eye\n'
    printf 'export BEYOUREYES_RELEASE_KEY_PASSWORD=%q\n' "$WEBSITE_KEY_PASSWORD"
} > "$ENV_FILE"
unset WEBSITE_STORE_PASSWORD WEBSITE_KEY_PASSWORD
chmod 600 "$KEYSTORE" "$CERTIFICATE" "$ENV_FILE"
cp -p "$KEYSTORE" "$CERTIFICATE" "$ENV_FILE" "$BACKUP_DIR/"
for name in be-your-eye.jks be-your-eye-certificate.pem signing.env; do
    cmp "$SIGNING_DIR/$name" "$BACKUP_DIR/$name"
done
printf 'App signing identity created; private local recovery copy verified.\n'
printf 'signing_env=%s\nbackup=%s\n' "$ENV_FILE" "$BACKUP_DIR"
openssl x509 -in "$CERTIFICATE" -noout -fingerprint -sha256 -enddate
