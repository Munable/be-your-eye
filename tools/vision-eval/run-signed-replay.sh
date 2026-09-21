#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly DEFAULT_EVAL_ROOT="${BEYOUREYES_VISION_EVAL_ROOT:-/Volumes/DevDisk/DeveloperData/be-your-eyes/external-eval-cache/external-replay-set-v1}"
readonly DEFAULT_FIXTURES_BASE="/Volumes/DevDisk/DeveloperData/be-your-eyes/repo-local/functional-fixtures"
readonly SUITE_DEFINITION="$REPO_ROOT/model-tools/v3/evaluation/external-replay-set-v1.json"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly APP_ID="app.beyoureyes.monitor.functional"
readonly TEST_ID="app.beyoureyes.monitor.functional.test"
readonly RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="app.beyoureyes.monitor.ExternalVisionReplayInstrumentedTest"
readonly SUMMARY_FILENAME="external-vision-replay-summary.json"

PACKAGE_ID=""
INPUT_MODE=""
SERIAL="${ANDROID_SERIAL:-}"
SOURCE_BUNDLE="$DEFAULT_EVAL_ROOT/bundle"
FIXTURES_ROOT="${BEYOUREYES_FUNCTIONAL_FIXTURES_ROOT:-}"
WORK_ROOT="$DEFAULT_EVAL_ROOT/signed-runtime-replay"
PREPARE_ONLY=false
DRY_RUN=false

usage() {
    cat >&2 <<'EOF'
usage: run-signed-replay.sh --package PACKAGE_ID [--serial SERIAL] [options]

Required for a device run:
  --package PACKAGE_ID       One of the three current signed package IDs.
  --serial SERIAL            Android serial; ANDROID_SERIAL is also accepted.

Options:
  --input-mode MODE          Filter Reader cases by expected.input_mode.
  --bundle DIR               Prepared External Replay Set bundle directory.
  --fixtures DIR             Functional fixture root; defaults to suite.catalog.catalog_version.
  --work-root DIR            Generated filters and runs (defaults to the external DevDisk).
  --prepare-only             Build or verify the package-filtered inputs, then stop.
  --dry-run                  Validate every input and print the device commands without writing.

The generated test APK contains only the selected package, its artifacts, and its cases.
EOF
}

while (($#)); do
    case "$1" in
        --package)
            PACKAGE_ID="${2:-}"
            shift 2
            ;;
        --serial)
            SERIAL="${2:-}"
            shift 2
            ;;
        --input-mode)
            INPUT_MODE="${2:-}"
            shift 2
            ;;
        --bundle)
            SOURCE_BUNDLE="${2:-}"
            shift 2
            ;;
        --fixtures)
            FIXTURES_ROOT="${2:-}"
            shift 2
            ;;
        --work-root)
            WORK_ROOT="${2:-}"
            shift 2
            ;;
        --prepare-only)
            PREPARE_ONLY=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

case "$PACKAGE_ID" in
    efficientdet_lite2_object_v1|numeric_reader_ppocrv6_medium_v1|similarity_mediapipe_mobilenet_v3_large_v1) ;;
    *)
        echo "--package must name one current signed package" >&2
        usage
        exit 2
        ;;
esac

if [[ -n "$INPUT_MODE" ]]; then
    if [[ "$PACKAGE_ID" != "numeric_reader_ppocrv6_medium_v1" ]]; then
        echo "--input-mode is only valid for numeric_reader_ppocrv6_medium_v1" >&2
        exit 2
    fi
    case "$INPUT_MODE" in
        full_frame|manual_roi_diagnostic) ;;
        *)
            echo "--input-mode must be full_frame or manual_roi_diagnostic" >&2
            exit 2
            ;;
    esac
fi

if [[ -z "$FIXTURES_ROOT" ]]; then
    if [[ ! -f "$SUITE_DEFINITION" ]]; then
        echo "missing tracked replay suite definition: $SUITE_DEFINITION" >&2
        exit 2
    fi
    CATALOG_VERSION="$(python3 - "$SUITE_DEFINITION" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["catalog"]["catalog_version"])
PY
)"
    FIXTURES_ROOT="$DEFAULT_FIXTURES_BASE/$CATALOG_VERSION"
fi

if [[ "$PREPARE_ONLY" != true && -z "$SERIAL" ]]; then
    echo "--serial or ANDROID_SERIAL is required for a device run" >&2
    exit 2
fi

readonly PACKAGE_ID INPUT_MODE SERIAL SOURCE_BUNDLE FIXTURES_ROOT WORK_ROOT PREPARE_ONLY DRY_RUN
readonly PREPARED_VARIANT="$PACKAGE_ID${INPUT_MODE:+--$INPUT_MODE}"
readonly PREPARED_ROOT="$WORK_ROOT/prepared/$PREPARED_VARIANT"
readonly FILTERED_BUNDLE="$PREPARED_ROOT/bundle"
readonly FILTERED_PACKAGES="$PREPARED_ROOT/signed-packages"

prepare_inputs() {
    python3 - "$SOURCE_BUNDLE" "$FIXTURES_ROOT" "$PREPARED_ROOT" "$PACKAGE_ID" "$INPUT_MODE" "$DRY_RUN" <<'PY'
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys

source_bundle = Path(sys.argv[1]).resolve()
fixtures_root = Path(sys.argv[2]).resolve()
prepared_root = Path(sys.argv[3])
package_id = sys.argv[4]
input_mode = sys.argv[5] or None
dry_run = sys.argv[6] == "true"
preparer_version = 1


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def safe_relative(value: str) -> Path:
    pure = PurePosixPath(value)
    if pure.is_absolute() or not pure.parts or any(part in ("", ".", "..") for part in pure.parts):
        raise SystemExit(f"unsafe replay asset path: {value!r}")
    return Path(*pure.parts)


source_manifest_path = source_bundle / "manifest.json"
catalog_path = fixtures_root / "release" / "catalog.json"
signed_manifest_path = fixtures_root / "release" / "manifests" / f"{package_id}.json"
for required in (source_manifest_path, catalog_path, signed_manifest_path):
    if not required.is_file():
        raise SystemExit(f"missing required input: {required}")

source_manifest_bytes = source_manifest_path.read_bytes()
catalog_bytes = catalog_path.read_bytes()
signed_manifest_bytes = signed_manifest_path.read_bytes()
source_manifest = json.loads(source_manifest_bytes)
catalog = json.loads(catalog_bytes)
signed_manifest = json.loads(signed_manifest_bytes)

if source_manifest.get("schema_version") != "beyoureye.external-replay-bundle.v1":
    raise SystemExit("unsupported external replay bundle schema")
if source_manifest.get("catalog_version") != catalog.get("catalog_version"):
    raise SystemExit("bundle and signed Catalog versions differ")
if signed_manifest.get("package_id") != package_id:
    raise SystemExit("signed manifest package_id differs from --package")

catalog_entries = [item for item in catalog.get("packages", []) if item.get("package_id") == package_id]
if len(catalog_entries) != 1 or catalog_entries[0].get("status") != "active":
    raise SystemExit(f"{package_id} is not exactly one active signed Catalog entry")
manifest_sha256 = hashlib.sha256(signed_manifest_bytes).hexdigest()
if catalog_entries[0].get("manifest_sha256") != manifest_sha256:
    raise SystemExit("signed manifest SHA-256 does not match the signed Catalog entry")

cases = [case for case in source_manifest.get("cases", []) if case.get("package_id") == package_id]
if input_mode is not None:
    cases = [case for case in cases if case.get("expected", {}).get("input_mode") == input_mode]
if not cases:
    suffix = f" and expected.input_mode={input_mode}" if input_mode is not None else ""
    raise SystemExit(f"prepared bundle has no cases for {package_id}{suffix}")

asset_identities: dict[str, tuple[str, int]] = {}
for case in cases:
    asset = case.get("asset")
    asset_sha = case.get("asset_sha256")
    asset_size = case.get("asset_size_bytes")
    if not isinstance(asset, str) or not isinstance(asset_sha, str) or not isinstance(asset_size, int):
        raise SystemExit(f"{case.get('id')}: missing primary asset identity")
    primary_identity = (asset_sha, asset_size)
    previous = asset_identities.setdefault(asset, primary_identity)
    if previous != primary_identity:
        raise SystemExit(f"conflicting asset identity for {asset}")
    reference_identities = {
        item.get("asset"): (item.get("sha256"), item.get("size_bytes"))
        for item in case.get("reference_asset_identities", [])
    }
    for reference in case.get("references", []):
        identity = reference_identities.get(reference)
        if identity is None or not isinstance(identity[0], str) or not isinstance(identity[1], int):
            raise SystemExit(f"{case.get('id')}: missing reference asset identity for {reference}")
        previous = asset_identities.setdefault(reference, identity)
        if previous != identity:
            raise SystemExit(f"conflicting asset identity for {reference}")

source_assets: dict[str, Path] = {}
for relative, (expected_sha, expected_size) in sorted(asset_identities.items()):
    source_asset = source_bundle / safe_relative(relative)
    if not source_asset.is_file():
        raise SystemExit(f"missing replay asset: {source_asset}")
    if source_asset.stat().st_size != expected_size or digest(source_asset) != expected_sha:
        raise SystemExit(f"replay asset identity mismatch: {source_asset}")
    source_assets[relative] = source_asset.resolve()

artifact_root = fixtures_root / "artifacts" / package_id
artifact_candidates = [path for path in artifact_root.rglob("*") if path.is_file()]
artifact_sources: dict[str, Path] = {}
artifact_identities: dict[str, dict[str, object]] = {}
for artifact in signed_manifest.get("artifacts", []):
    role = artifact.get("role")
    expected_sha = artifact.get("sha256")
    expected_size = artifact.get("size_bytes")
    if not isinstance(role, str) or not isinstance(expected_sha, str) or not isinstance(expected_size, int):
        raise SystemExit("signed manifest has an invalid artifact declaration")
    matches = [
        path for path in artifact_candidates
        if path.stat().st_size == expected_size and digest(path) == expected_sha
    ]
    if len(matches) != 1:
        raise SystemExit(f"{package_id}/{role}: expected one SHA-matching fixture artifact, found {len(matches)}")
    artifact_sources[role] = matches[0].resolve()
    artifact_identities[role] = {"sha256": expected_sha, "size_bytes": expected_size}

filtered_manifest = copy.deepcopy(source_manifest)
filtered_manifest["suite_id"] = f"{source_manifest['suite_id']}--{package_id}"
if input_mode is not None:
    filtered_manifest["suite_id"] += f"--{input_mode}"
filtered_manifest["source_bundle_manifest_sha256"] = hashlib.sha256(source_manifest_bytes).hexdigest()
filtered_manifest["package_filter"] = package_id
filtered_manifest["input_mode_filter"] = input_mode
filtered_manifest["cases"] = cases
dataset_ids = {case["dataset_id"] for case in cases}
if isinstance(filtered_manifest.get("source_index_sha256"), dict):
    filtered_manifest["source_index_sha256"] = {
        key: value for key, value in filtered_manifest["source_index_sha256"].items()
        if key in dataset_ids
    }
filtered_manifest_bytes = (json.dumps(filtered_manifest, indent=2, ensure_ascii=False) + "\n").encode()

marker = {
    "preparer_version": preparer_version,
    "package_id": package_id,
    "input_mode": input_mode,
    "source_bundle_manifest_sha256": hashlib.sha256(source_manifest_bytes).hexdigest(),
    "filtered_manifest_sha256": hashlib.sha256(filtered_manifest_bytes).hexdigest(),
    "catalog_sha256": hashlib.sha256(catalog_bytes).hexdigest(),
    "signed_manifest_sha256": manifest_sha256,
    "case_count": len(cases),
    "asset_count": len(source_assets),
    "artifacts": artifact_identities,
}
marker_bytes = (json.dumps(marker, indent=2, sort_keys=True) + "\n").encode()

print(
    f"{'PLAN' if dry_run else 'PREPARE'}: package={package_id} "
    f"input_mode={input_mode or 'all'} cases={len(cases)} "
    f"assets={len(source_assets)} artifacts={len(artifact_sources)}"
)
if dry_run:
    raise SystemExit(0)

marker_path = prepared_root / "prepared.json"
if marker_path.is_file() and marker_path.read_bytes() == marker_bytes:
    prepared_bundle_manifest = prepared_root / "bundle" / "manifest.json"
    prepared_catalog = prepared_root / "signed-packages" / "catalog.json"
    prepared_manifest = (
        prepared_root / "signed-packages" / "packages" / package_id / "manifest.json"
    )
    cache_valid = (
        prepared_bundle_manifest.is_file()
        and prepared_bundle_manifest.read_bytes() == filtered_manifest_bytes
        and prepared_catalog.is_file()
        and prepared_catalog.read_bytes() == catalog_bytes
        and prepared_manifest.is_file()
        and prepared_manifest.read_bytes() == signed_manifest_bytes
    )
    for relative, expected in asset_identities.items():
        cached = prepared_root / "bundle" / safe_relative(relative)
        cache_valid = cache_valid and (
            cached.is_file()
            and cached.stat().st_size == expected[1]
            and digest(cached) == expected[0]
        )
    for role, identity in artifact_identities.items():
        cached = prepared_root / "signed-packages" / "packages" / package_id / "artifacts" / role
        cache_valid = cache_valid and (
            cached.is_file()
            and cached.stat().st_size == identity["size_bytes"]
            and digest(cached) == identity["sha256"]
        )
    if cache_valid:
        print(f"REUSE: {prepared_root}")
        raise SystemExit(0)
    print(f"REBUILD: cached prepared inputs failed identity verification: {prepared_root}")

prepared_root.parent.mkdir(parents=True, exist_ok=True)
temporary = prepared_root.with_name(f".{prepared_root.name}.tmp-{os.getpid()}")
shutil.rmtree(temporary, ignore_errors=True)
try:
    bundle_output = temporary / "bundle"
    package_output = temporary / "signed-packages" / "packages" / package_id
    (package_output / "artifacts").mkdir(parents=True)
    bundle_output.mkdir(parents=True)
    (bundle_output / "manifest.json").write_bytes(filtered_manifest_bytes)
    shutil.copy2(catalog_path, temporary / "signed-packages" / "catalog.json")
    shutil.copy2(signed_manifest_path, package_output / "manifest.json")
    for role, source in artifact_sources.items():
        (package_output / "artifacts" / role).symlink_to(source)
    for relative, source in source_assets.items():
        destination = bundle_output / safe_relative(relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.symlink_to(source)
    (temporary / "prepared.json").write_bytes(marker_bytes)
    if prepared_root.exists() or prepared_root.is_symlink():
        if prepared_root.is_dir() and not prepared_root.is_symlink():
            shutil.rmtree(prepared_root)
        else:
            prepared_root.unlink()
    temporary.rename(prepared_root)
finally:
    shutil.rmtree(temporary, ignore_errors=True)
print(f"READY: {prepared_root}")
PY
}

print_command() {
    printf '%q ' "$@"
    printf '\n'
}

prepare_inputs

if [[ "$DRY_RUN" == true ]]; then
    if [[ "$PREPARE_ONLY" == true ]]; then
        exit 0
    fi
    GRADLE_COMMAND=(
        "$REPO_ROOT/android/gradlew" --no-daemon --stacktrace
        "-PexternalVisionReplayBundleDir=$FILTERED_BUNDLE"
        "-PexternalVisionReplayPackagesDir=$FILTERED_PACKAGES"
        :app:assembleFunctionalTest :app:assembleFunctionalTestAndroidTest
    )
    printf 'JAVA_HOME=%q ' "$JAVA17_HOME"
    print_command "${GRADLE_COMMAND[@]}"
    if [[ "$PREPARE_ONLY" != true ]]; then
        print_command adb -s "$SERIAL" install -r -t "$REPO_ROOT/android/app/build/outputs/apk/functionalTest/app-functionalTest.apk"
        print_command adb -s "$SERIAL" install -r -t "$REPO_ROOT/android/app/build/outputs/apk/androidTest/functionalTest/app-functionalTest-androidTest.apk"
        print_command adb -s "$SERIAL" shell am instrument -w -r \
            -e class "$TEST_CLASS" \
            -e runExternalVisionReplay true \
            -e requireAllReplayPackages false \
            "$TEST_ID/$RUNNER"
        echo "HOST_OBSERVATION: suite=$SUITE_DEFINITION"
    fi
    exit 0
fi

if [[ "$PREPARE_ONLY" == true ]]; then
    echo "PASS: package-filtered replay inputs ready at $PREPARED_ROOT"
    exit 0
fi

command -v adb >/dev/null
test -x "$JAVA17_HOME/bin/java"
if [[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" != "device" ]]; then
    echo "Android device is not ready: $SERIAL" >&2
    exit 3
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$PACKAGE_ID"
readonly RUN_ID
readonly RUN_DIR="$WORK_ROOT/runs/$RUN_ID"
mkdir -p "$RUN_DIR"

IMPLEMENTATION_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
readonly IMPLEMENTATION_COMMIT
if [[ -z "$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
    GIT_STATUS_CLEAN=true
else
    GIT_STATUS_CLEAN=false
fi
readonly GIT_STATUS_CLEAN
repository_identity_unchanged() {
    local current_status_clean
    if [[ -z "$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
        current_status_clean=true
    else
        current_status_clean=false
    fi
    [[ "$(git -C "$REPO_ROOT" rev-parse HEAD)" == "$IMPLEMENTATION_COMMIT" &&
        "$current_status_clean" == "$GIT_STATUS_CLEAN" ]]
}

require_repository_identity_unchanged() {
    if ! repository_identity_unchanged; then
        echo "repository identity changed during the replay run" >&2
        exit 4
    fi
}

set +e
(
    cd "$REPO_ROOT/android"
    env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
        ./gradlew --no-daemon --stacktrace \
            "-PexternalVisionReplayBundleDir=$FILTERED_BUNDLE" \
            "-PexternalVisionReplayPackagesDir=$FILTERED_PACKAGES" \
            :app:assembleFunctionalTest \
            :app:assembleFunctionalTestAndroidTest
) >"$RUN_DIR/gradle.log" 2>&1
GRADLE_STATUS=$?
set -e
if ((GRADLE_STATUS != 0)); then
    tail -80 "$RUN_DIR/gradle.log" >&2
    echo "Gradle build failed; log=$RUN_DIR/gradle.log" >&2
    exit "$GRADLE_STATUS"
fi

readonly APP_APK="$REPO_ROOT/android/app/build/outputs/apk/functionalTest/app-functionalTest.apk"
readonly TEST_APK="$REPO_ROOT/android/app/build/outputs/apk/androidTest/functionalTest/app-functionalTest-androidTest.apk"
test -s "$APP_APK"
test -s "$TEST_APK"
python3 - "$TEST_APK" "$PACKAGE_ID" <<'PY'
import json
import sys
import zipfile

apk, package_id = sys.argv[1:]
root = "assets/external-vision-replay/"
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    manifest = json.loads(archive.read(root + "bundle/manifest.json"))
    packages = {
        name.split("/")[4]
        for name in names
        if name.startswith(root + "packages/packages/") and len(name.split("/")) > 4
    }
if packages != {package_id}:
    raise SystemExit(f"test APK package isolation failed: {sorted(packages)}")
if not manifest.get("cases") or {case.get("package_id") for case in manifest["cases"]} != {package_id}:
    raise SystemExit("test APK case isolation failed")
print(f"APK: package={package_id} cases={len(manifest['cases'])} bytes={__import__('os').path.getsize(apk)}")
PY

adb -s "$SERIAL" install -r -t "$APP_APK" >"$RUN_DIR/install-app.log"
grep -q '^Success' "$RUN_DIR/install-app.log"
adb -s "$SERIAL" install -r -t "$TEST_APK" >"$RUN_DIR/install-test.log"
grep -q '^Success' "$RUN_DIR/install-test.log"

installed_apk_sha256() {
    local package_id="$1"
    local installed_path
    installed_path="$(adb -s "$SERIAL" shell pm path "$package_id" | tr -d '\r')"
    if [[ "$installed_path" != package:* || "$installed_path" == *$'\n'* ]]; then
        echo "expected one installed base APK for $package_id; got: $installed_path" >&2
        return 1
    fi
    installed_path="${installed_path#package:}"
    local digest
    digest="$(adb -s "$SERIAL" shell sha256sum "$installed_path" | tr -d '\r' | awk '{print $1}')"
    if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
        echo "could not read back installed APK hash for $package_id" >&2
        return 1
    fi
    printf '%s\n' "$digest"
}

INSTALLED_APP_SHA256="$(installed_apk_sha256 "$APP_ID")"
readonly INSTALLED_APP_SHA256
INSTALLED_TEST_SHA256="$(installed_apk_sha256 "$TEST_ID")"
readonly INSTALLED_TEST_SHA256
DEVICE_MANUFACTURER="$(adb -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')"
readonly DEVICE_MANUFACTURER
DEVICE_MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
readonly DEVICE_MODEL
DEVICE_API_LEVEL="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly DEVICE_API_LEVEL
DEVICE_PRIMARY_ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
readonly DEVICE_PRIMARY_ABI
DEVICE_BUILD_FINGERPRINT="$(adb -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r')"
readonly DEVICE_BUILD_FINGERPRINT
if [[ -z "$DEVICE_MANUFACTURER" || -z "$DEVICE_MODEL" ||
    ! "$DEVICE_API_LEVEL" =~ ^[0-9]+$ || -z "$DEVICE_PRIMARY_ABI" ||
    -z "$DEVICE_BUILD_FINGERPRINT"
]]; then
    echo "could not read the non-secret Android device profile" >&2
    exit 4
fi
require_repository_identity_unchanged

readonly RUN_METADATA_PATH="$RUN_DIR/host-run-metadata.json"
python3 - \
    "$RUN_METADATA_PATH" "$PACKAGE_ID" "${INPUT_MODE:-all}" \
    "$IMPLEMENTATION_COMMIT" "$GIT_STATUS_CLEAN" \
    "$APP_APK" "$INSTALLED_APP_SHA256" "$TEST_APK" "$INSTALLED_TEST_SHA256" \
    "$DEVICE_MANUFACTURER" "$DEVICE_MODEL" "$DEVICE_API_LEVEL" \
    "$DEVICE_PRIMARY_ABI" "$DEVICE_BUILD_FINGERPRINT" <<'PY'
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

(
    output,
    package_id,
    input_mode,
    implementation_commit,
    git_status_clean,
    app_apk,
    installed_app_sha256,
    test_apk,
    installed_test_sha256,
    device_manufacturer,
    device_model,
    device_api_level,
    device_primary_abi,
    device_build_fingerprint,
) = sys.argv[1:]


def identity(path_value: str, installed_sha256: str) -> dict[str, object]:
    path = Path(path_value)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "sha256": digest.hexdigest(),
        "size_bytes": path.stat().st_size,
        "installed_sha256": installed_sha256,
    }


metadata = {
    "schema_version": "beyoureye.external-replay-host-run.v1",
    "recorded_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "package_id": package_id,
    "input_mode_filter": input_mode,
    "implementation_commit": implementation_commit,
    "git_status_clean": git_status_clean == "true",
    "device": {
        "manufacturer": device_manufacturer,
        "model": device_model,
        "api_level": int(device_api_level),
        "primary_abi": device_primary_abi,
        "build_fingerprint": device_build_fingerprint,
    },
    "app_apk": identity(app_apk, installed_app_sha256),
    "test_apk": identity(test_apk, installed_test_sha256),
}
Path(output).write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

readonly DEVICE_SUMMARY="/sdcard/Android/data/$APP_ID/files/$SUMMARY_FILENAME"
adb -s "$SERIAL" shell rm -f "$DEVICE_SUMMARY"
set +e
adb -s "$SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e runExternalVisionReplay true \
    -e requireAllReplayPackages false \
    "$TEST_ID/$RUNNER" 2>&1 | tee "$RUN_DIR/instrumentation.log"
INSTRUMENT_STATUS=${PIPESTATUS[0]}
set -e

SUMMARY_PATH="$RUN_DIR/$SUMMARY_FILENAME"
readonly SUMMARY_PATH
adb -s "$SERIAL" exec-out cat "$DEVICE_SUMMARY" >"$SUMMARY_PATH.tmp" 2>/dev/null || true
if [[ -s "$SUMMARY_PATH.tmp" ]]; then
    mv "$SUMMARY_PATH.tmp" "$SUMMARY_PATH"
else
    rm -f "$SUMMARY_PATH.tmp"
    echo "instrumentation produced no summary: $DEVICE_SUMMARY" >&2
    exit 4
fi

readonly SCORE_PATH="$RUN_DIR/external-vision-replay-observation.json"
SCORE_COMMAND=(
    python3 "$SCRIPT_DIR/score_signed_replay.py"
    --summary "$SUMMARY_PATH"
    --package "$PACKAGE_ID"
    --suite "$SUITE_DEFINITION"
    --bundle-manifest "$FILTERED_BUNDLE/manifest.json"
    --catalog "$FILTERED_PACKAGES/catalog.json"
    --package-manifest "$FILTERED_PACKAGES/packages/$PACKAGE_ID/manifest.json"
    --run-metadata "$RUN_METADATA_PATH"
    --app-apk "$APP_APK"
    --test-apk "$TEST_APK"
    --output "$SCORE_PATH"
)
require_repository_identity_unchanged
set +e
"${SCORE_COMMAND[@]}"
SCORE_STATUS=$?
set -e
require_repository_identity_unchanged
if ((INSTRUMENT_STATUS != 0)) || ! grep -q '^OK (1 test)' "$RUN_DIR/instrumentation.log"; then
    echo "Instrumentation failed; summary=$SUMMARY_PATH score=$SCORE_PATH " \
        "log=$RUN_DIR/instrumentation.log" >&2
    exit 5
fi
if ((SCORE_STATUS != 0)); then
    if ((SCORE_STATUS == 6)); then
        echo "Wiring observation incomplete; summary=$SUMMARY_PATH observation=$SCORE_PATH" >&2
        exit 6
    fi
    echo "Host summary scoring failed; summary=$SUMMARY_PATH score=$SCORE_PATH" >&2
    exit 4
fi

echo "DONE: signed runtime replay completed; summary=$SUMMARY_PATH score=$SCORE_PATH"
