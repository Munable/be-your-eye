#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR

if (($# != 2)); then
    printf 'usage: %s AAB OUTPUT_DIR\n' "$0" >&2
    exit 2
fi
readonly AAB="$1"
readonly OUTPUT_DIR="$2"
test -s "$AAB"
mkdir -p "$OUTPUT_DIR"

for name in \
    BEYOUREYES_RELEASE_STORE_FILE \
    BEYOUREYES_RELEASE_STORE_PASSWORD \
    BEYOUREYES_RELEASE_KEY_ALIAS \
    BEYOUREYES_RELEASE_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        printf 'release signing input is empty: %s\n' "$name" >&2
        exit 2
    fi
done

BUNDLETOOL_JAR="$(bash "$SCRIPT_DIR/resolve-bundletool.sh")"
readonly BUNDLETOOL_JAR
java -jar "$BUNDLETOOL_JAR" validate --bundle="$AAB" > "$OUTPUT_DIR/bundletool-validate.txt"

PASSWORD_DIR="$(mktemp -d "$OUTPUT_DIR/.bundletool-passwords.XXXXXX")"
readonly PASSWORD_DIR
cleanup() {
    rm -rf "$PASSWORD_DIR"
}
trap cleanup EXIT
chmod 700 "$PASSWORD_DIR"
printf '%s\n' "$BEYOUREYES_RELEASE_STORE_PASSWORD" > "$PASSWORD_DIR/store"
printf '%s\n' "$BEYOUREYES_RELEASE_KEY_PASSWORD" > "$PASSWORD_DIR/key"
chmod 600 "$PASSWORD_DIR/store" "$PASSWORD_DIR/key"

readonly APKS="$OUTPUT_DIR/universal.apks"
readonly APK="$OUTPUT_DIR/universal.apk"
java -jar "$BUNDLETOOL_JAR" build-apks \
    --bundle="$AAB" \
    --output="$APKS" \
    --overwrite \
    --mode=universal \
    --ks="$BEYOUREYES_RELEASE_STORE_FILE" \
    --ks-key-alias="$BEYOUREYES_RELEASE_KEY_ALIAS" \
    --ks-pass="file:$PASSWORD_DIR/store" \
    --key-pass="file:$PASSWORD_DIR/key"
unzip -p "$APKS" universal.apk > "$APK"
test -s "$APK"

APKSIGNER="${APKSIGNER:-}"
if [[ -z "$APKSIGNER" ]]; then
    APKSIGNER="$(find /opt/homebrew/share/android-commandlinetools/build-tools \
        -name apksigner -type f -print 2>/dev/null | sort -V | tail -1)"
fi
if [[ ! -x "$APKSIGNER" ]]; then
    printf 'apksigner is not executable; set APKSIGNER\n' >&2
    exit 1
fi
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$OUTPUT_DIR/universal-apk-signature.txt"

printf 'bundletool=%s\n' "$BUNDLETOOL_JAR"
printf 'universal_apks=%s\n' "$APKS"
printf 'universal_apk=%s\n' "$APK"
printf 'universal_apk_sha256=%s\n' "$(shasum -a 256 "$APK" | awk '{print $1}')"
