#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
TMP_DIR="$(mktemp -d)"
readonly TMP_DIR
trap 'rm -rf "$TMP_DIR"' EXIT

readonly FAKE_KEYTOOL="$TMP_DIR/keytool"
cat > "$FAKE_KEYTOOL" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=" $* "
output=''
keystore=''
while (($#)); do
    case "$1" in
        -file) output="$2"; shift 2 ;;
        -keystore) keystore="$2"; shift 2 ;;
        *) shift ;;
    esac
done
if [[ "$args" == *' -genkeypair '* ]]; then
    printf '%s\n' 'fixture keystore' > "$keystore"
elif [[ "$args" == *' -exportcert '* ]]; then
    printf '%s\n' 'fixture certificate' > "$output"
elif [[ "$args" == *' -list '* ]]; then
    printf '%s\n' 'SHA256: AA:BB:CC'
else
    printf 'unexpected fake keytool invocation: %s\n' "$args" >&2
    exit 1
fi
EOF
chmod 700 "$FAKE_KEYTOOL"

readonly OUTPUT_DIR="$TMP_DIR/output"
KEYTOOL="$FAKE_KEYTOOL" bash "$SCRIPT_DIR/prepare-google-play-upload-key.sh" "$OUTPUT_DIR" \
    > "$TMP_DIR/result.txt"

readonly ENV_FILE="$OUTPUT_DIR/release.env"
test "$(stat -f '%Lp' "$ENV_FILE")" = '600'
test "$(stat -f '%Lp' "$OUTPUT_DIR/google-play-upload.jks")" = '600'
for name in \
    MODEL_CATALOG_URL \
    MODEL_RELEASE_URL \
    MODEL_ROLLBACK_CATALOG_URL \
    MODEL_ROLLBACK_RELEASE_URL; do
    grep -q "^export $name=''$" "$ENV_FILE"
done
grep -q '^certificate_sha256=aabbcc$' "$TMP_DIR/result.txt"

if KEYTOOL="$FAKE_KEYTOOL" bash "$SCRIPT_DIR/prepare-google-play-upload-key.sh" "$OUTPUT_DIR" \
    > "$TMP_DIR/overwrite.txt" 2>&1; then
    printf '%s\n' 'prepare script overwrote existing release material' >&2
    exit 1
fi
grep -q 'refusing to overwrite' "$TMP_DIR/overwrite.txt"

printf '%s\n' 'prepare Google Play upload key test passed'
