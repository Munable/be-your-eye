#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
MODE="${1:---unsigned}"
[[ "$MODE" == --unsigned || "$MODE" == --signed ]] || { echo 'Use --unsigned or --signed' >&2; exit 64; }
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${COMMUNITY_OUTPUT_DIR:?Choose a release output directory outside the source tree}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
node tools/ci/check-i18n.mjs
node --test tools/ci/check-i18n.test.mjs
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo 'Commit verified source changes before freezing a candidate' >&2; exit 1
fi
python3 - "$ROOT" "$COMMUNITY_OUTPUT_DIR" <<'PYCHECK'
from pathlib import Path
import sys
root,out=map(lambda p:Path(p).resolve(),sys.argv[1:])
assert out != root and not out.is_relative_to(root), 'Release output must be outside the source tree'
PYCHECK
COMMIT="$(git rev-parse HEAD)"
DEST="$COMMUNITY_OUTPUT_DIR/$COMMIT/${MODE#--}"
[[ ! -e "$DEST" ]] || { echo 'Candidate already exists; refusing to overwrite' >&2; exit 1; }
SIGNED=false
if [[ "$MODE" == --signed ]]; then
    : "${BEYOUREYES_COMMUNITY_SIGNING_ENV:?Supply the signing environment outside the repository}"
    python3 - "$ROOT" "$BEYOUREYES_COMMUNITY_SIGNING_ENV" <<'PY'
from pathlib import Path
import sys,stat
root,key=map(Path,sys.argv[1:]);key=key.resolve()
assert key.is_file() and not key.is_relative_to(root.resolve()), 'Signing environment must be outside the source tree'
assert stat.S_IMODE(key.stat().st_mode)==0o600, 'Signing environment must have mode 600'
PY
    set -a
    # shellcheck disable=SC1090
    source "$BEYOUREYES_COMMUNITY_SIGNING_ENV"
    set +a
    for name in BEYOUREYES_RELEASE_STORE_FILE BEYOUREYES_RELEASE_STORE_PASSWORD BEYOUREYES_RELEASE_KEY_ALIAS BEYOUREYES_RELEASE_KEY_PASSWORD; do
        [[ -n "${!name:-}" ]] || { printf 'Missing signing input: %s\n' "$name" >&2; exit 1; }
    done
    python3 - "$ROOT" "$BEYOUREYES_RELEASE_STORE_FILE" <<'PYKEY'
from pathlib import Path
import sys
root,key=map(lambda p:Path(p).resolve(),sys.argv[1:])
assert key.is_file() and not key.is_relative_to(root), 'Signing key must be outside the source tree'
PYKEY
    SIGNED=true
fi
./android/gradlew -p android --no-daemon -PCOMMUNITY_SIGNED="$SIGNED" \
    -PBEYOUREYES_VERSION_CODE=26 -PBEYOUREYES_VERSION_NAME=0.3.0 \
    :app:lintCommunityRelease :app:assembleCommunityRelease
mkdir -p "$DEST"
if [[ "$SIGNED" == true ]]; then NAME=app-communityRelease.apk; else NAME=app-communityRelease-unsigned.apk; fi
cp "$ROOT/android/app/build/outputs/apk/communityRelease/$NAME" "$DEST/be-your-eye-community.apk"
if [[ "$SIGNED" == true ]]; then
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$DEST/be-your-eye-community.apk" > "$DEST/signature.txt"
fi
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$DEST/be-your-eye-community.apk" > "$DEST/manifest.txt"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$ROOT/LICENSE" "$ROOT/NOTICE" "$DEST/"
python3 - "$DEST" "$COMMIT" "$SIGNED" <<'PY'
from pathlib import Path
import sys,json,hashlib,datetime
out=Path(sys.argv[1]);apk=out/'be-your-eye-community.apk';digest=hashlib.sha256(apk.read_bytes()).hexdigest()
(out/'SHA256SUMS').write_text(digest+'  '+apk.name+'\n')
(out/'candidate.json').write_text(json.dumps({'source_commit':sys.argv[2],'application_id':'app.beyoureyes.monitor.community',
'version_name':'0.3.0-community-preview','version_code':26,'apk_sha256':digest,'apk_bytes':apk.stat().st_size,
'signed':sys.argv[3]=='true','built_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
'release_quality_ready':False,'public_release_authorized':False},indent=2)+'\n')
PY
[[ "$(git rev-parse HEAD)" == "$COMMIT" ]] && git diff --quiet && git diff --cached --quiet
printf 'Community candidate: %s\n' "$DEST"
