#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly NODE_VERSION="24.16.0"
readonly DENO_VERSION="2.9.5"
readonly JAVA17_HOME="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
readonly ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly ANDROID_SDK_ROOT="$ANDROID_HOME"
readonly CURRENT_RELEASE_DIR="$REPO_ROOT/.local/releases/internal-2026.09.15.1"
readonly FUNCTIONAL_FIXTURES="$REPO_ROOT/.local/functional-fixtures/2026.09.15.1"
readonly CURRENT_CATALOG_URL="https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/internal-evaluation/2026-09-15/be-your-eye-v1/catalog.json"
readonly EXPECTED_INTERNAL_VERSION_NAME="0.2.24-dev-internal"
readonly EXPECTED_INTERNAL_VERSION_CODE="25"
readonly ENV_FILE="${BEYOUREYES_ENV_FILE:-$REPO_ROOT/.env.local}"
export ANDROID_HOME ANDROID_SDK_ROOT

command -v mise >/dev/null 2>&1
command -v jq >/dev/null 2>&1
command -v shellcheck >/dev/null 2>&1
test -x "$JAVA17_HOME/bin/java"
test -d "$ANDROID_HOME/platforms/android-36"

run_node() {
    mise exec "node@$NODE_VERSION" -- "$@"
}

run_deno() {
    mise exec "deno@$DENO_VERSION" -- deno "$@"
}

step() {
    printf '\n== %s ==\n' "$1"
}

cd "$REPO_ROOT"

ci_shell_scripts=()
for path in tools/ci/*.sh; do
    [[ -f "$path" ]] && ci_shell_scripts+=("$path")
done
readonly ci_shell_scripts

if [[ ! -r "$ENV_FILE" ]]; then
    echo "missing readable local product configuration: $ENV_FILE" >&2
    exit 3
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
if [[ "${MODEL_CATALOG_URL:-}" != "$CURRENT_CATALOG_URL" ]]; then
    echo "MODEL_CATALOG_URL must be the frozen three-package internal Catalog" >&2
    exit 4
fi
for path in catalog.json release.json checksums.json \
    manifests/similarity_mediapipe_mobilenet_v3_large_v1.json \
    manifests/numeric_reader_ppocrv6_medium_v1.json \
    manifests/efficientdet_lite2_object_v1.json; do
    if [[ ! -s "$CURRENT_RELEASE_DIR/$path" ]]; then
        echo "missing current frozen candidate: $CURRENT_RELEASE_DIR/$path" >&2
        echo "run tools/ci/build-current-internal-candidate.sh first" >&2
        exit 4
    fi
done
run_node node tools/ci/check-current-internal-built-manifests.mjs "$CURRENT_RELEASE_DIR"
for name in SUPABASE_URL SUPABASE_PUBLISHABLE_KEY FIREBASE_API_KEY \
    FIREBASE_APPLICATION_ID FIREBASE_PROJECT_ID FIREBASE_GCM_SENDER_ID \
    PRIVACY_POLICY_URL AUTH_REDIRECT_URL; do
    if [[ -z "${!name:-}" ]]; then
        echo "missing local product configuration: $name" >&2
        exit 4
    fi
done
run_node node -e '
  const raw = process.argv[1];
  let parsed;
  try { parsed = new URL(raw); } catch { process.exit(1); }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password ||
      parsed.port || parsed.pathname !== "/auth/callback" || parsed.search || parsed.hash ||
      parsed.toString() !== raw) process.exit(1);
' "$AUTH_REDIRECT_URL" || {
    echo "AUTH_REDIRECT_URL must be a canonical HTTPS /auth/callback URL without credentials, port, query, or fragment" >&2
    exit 4
}

step "repository policy and shell checks"
run_node node .github/scripts/check-repo-policy.mjs
run_node node --test \
    .github/scripts/check-repo-policy.test.mjs \
    tools/ci/check-current-model-quality.test.mjs \
    tools/ci/check-current-evidence.test.mjs
bash tools/release/test-clean-git-checkout-gate.sh
bash tools/device-evidence/test-install-internal.sh
bash tools/release/test-prepare-google-play-upload-key.sh
bash tools/release/test-public-service-inputs.sh
bash tools/release/test-check-secret-hygiene.sh
bash tools/release/test-scan-sbom-vulnerabilities.sh
bash tools/release/test-smoke-install-google-play-apk.sh
run_node node --test tools/sbom/test-deno-lock-to-cyclonedx.mjs
bash -n "${ci_shell_scripts[@]}" \
    tools/device-evidence/install-internal.sh tools/device-evidence/test-install-internal.sh \
    tools/release/build-google-play-bundle.sh tools/release/build-universal-apk-from-aab.sh \
    tools/release/check-secret-hygiene.sh tools/release/test-check-secret-hygiene.sh \
    tools/release/prepare-google-play-upload-key.sh \
    tools/release/prepare-website-signing-key.sh tools/release/build-website-apk.sh \
    tools/release/require-clean-git-checkout.sh tools/release/resolve-bundletool.sh \
    tools/release/resolve-osv-scanner.sh tools/release/scan-sbom-vulnerabilities.sh \
    tools/release/smoke-install-google-play-apk.sh tools/release/validate-public-service-inputs.sh \
    tools/release/test-clean-git-checkout-gate.sh \
    tools/release/test-prepare-google-play-upload-key.sh \
    tools/release/test-public-service-inputs.sh \
    tools/release/test-scan-sbom-vulnerabilities.sh \
    tools/release/test-smoke-install-google-play-apk.sh \
    tools/sbom/generate.sh tools/sbom/verify.sh
shellcheck "${ci_shell_scripts[@]}" \
    tools/device-evidence/install-internal.sh tools/device-evidence/test-install-internal.sh \
    tools/release/build-google-play-bundle.sh tools/release/build-universal-apk-from-aab.sh \
    tools/release/check-secret-hygiene.sh tools/release/test-check-secret-hygiene.sh \
    tools/release/prepare-google-play-upload-key.sh \
    tools/release/prepare-website-signing-key.sh tools/release/build-website-apk.sh \
    tools/release/require-clean-git-checkout.sh tools/release/resolve-bundletool.sh \
    tools/release/resolve-osv-scanner.sh tools/release/scan-sbom-vulnerabilities.sh \
    tools/release/smoke-install-google-play-apk.sh tools/release/validate-public-service-inputs.sh \
    tools/release/test-clean-git-checkout-gate.sh \
    tools/release/test-prepare-google-play-upload-key.sh \
    tools/release/test-public-service-inputs.sh \
    tools/release/test-scan-sbom-vulnerabilities.sh \
    tools/release/test-smoke-install-google-play-apk.sh \
    tools/sbom/generate.sh tools/sbom/verify.sh
run_node node tools/ci/check-current-evidence.mjs

step "Supabase schema and Edge Functions"
run_node npm ci --prefix supabase/tests
run_node npm run check --prefix supabase/tests
run_node npm test --prefix supabase/tests
run_deno fmt --check supabase/functions
run_deno check --lock=supabase/functions/push-dispatch/deno.lock \
    supabase/functions/push-dispatch/index.ts \
    supabase/functions/delete-account/index.ts
run_deno test --lock=supabase/functions/push-dispatch/deno.lock \
    supabase/functions/push-dispatch/core_test.ts
run_deno check --lock=supabase/functions/monitor-assistant/deno.lock \
    supabase/functions/monitor-assistant/index.ts
run_deno test --lock=supabase/functions/monitor-assistant/deno.lock \
    supabase/functions/monitor-assistant/core_test.ts
run_deno check --lock=supabase/functions/voice-transcription/deno.lock \
    supabase/functions/voice-transcription/index.ts
run_deno test --lock=supabase/functions/voice-transcription/deno.lock \
    supabase/functions/voice-transcription/core_test.ts
run_deno check --lock=supabase/functions/play-entitlement/deno.lock \
    supabase/functions/play-entitlement/index.ts
run_deno test --lock=supabase/functions/play-entitlement/deno.lock \
    supabase/functions/play-entitlement/core_test.ts \
    supabase/functions/play-entitlement/google_play_test.ts
run_deno check --lock=supabase/functions/play-rtdn/deno.lock \
    supabase/functions/play-rtdn/index.ts
run_deno test --lock=supabase/functions/play-rtdn/deno.lock \
    supabase/functions/play-rtdn/core_test.ts \
    supabase/functions/play-rtdn/google_oidc_test.ts

run_deno check --lock=supabase/functions/website-billing/deno.lock \
    supabase/functions/website-billing/index.ts \
    supabase/functions/stripe-webhook/index.ts \
    supabase/functions/product-entitlement/index.ts
run_deno test --allow-env=STRIPE_LIVE_MODE --lock=supabase/functions/website-billing/deno.lock \
    supabase/functions/website-billing/core_test.ts

step "Catalog and model package contracts"
run_node npm ci --prefix model-tools/catalog-validator
run_node npm test --prefix model-tools/catalog-validator
run_node node tools/ci/check-current-model-quality.mjs --channel internal-evaluation

step "signed three-package functional fixtures"
bash tools/ci/prepare-functional-fixtures.sh "$FUNCTIONAL_FIXTURES"

step "Android tests and internal artifacts on JDK 17"
(
    cd android
    env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
      ./gradlew --no-daemon --stacktrace \
        -PMODEL_CATALOG_URL="$CURRENT_CATALOG_URL" \
        -PinternalReferenceReleaseDir="$FUNCTIONAL_FIXTURES/release" \
        -PinternalReferencePrimaryArtifact="$FUNCTIONAL_FIXTURES/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/mobile_object_localizer_v1.tflite" \
        -PinternalReferenceObjectCropArtifact="$FUNCTIONAL_FIXTURES/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/prominent_object_candidates_v2.json" \
        -PinternalReferenceEmbedderArtifact="$FUNCTIONAL_FIXTURES/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/primary.tflite" \
        -PinternalReferenceSimilarityHead="$FUNCTIONAL_FIXTURES/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/l2_prototype_cosine_candidates_v2.json" \
        -PinternalReadingReleaseDir="$FUNCTIONAL_FIXTURES/release" \
        -PinternalReadingPrimaryArtifact="$FUNCTIONAL_FIXTURES/artifacts/numeric_reader_ppocrv6_medium_v1/primary.onnx" \
        -PinternalReadingLocatorArtifact="$FUNCTIONAL_FIXTURES/artifacts/numeric_reader_ppocrv6_medium_v1/locator.onnx" \
        -PinternalReadingVocabularyArtifact="$FUNCTIONAL_FIXTURES/artifacts/numeric_reader_ppocrv6_medium_v1/vocabulary.json" \
        -PinternalObjectReleaseDir="$FUNCTIONAL_FIXTURES/release" \
        -PinternalObjectPrimaryArtifact="$FUNCTIONAL_FIXTURES/artifacts/efficientdet_lite2_object_v1/efficientdet_lite2.tflite" \
        :core:domain:testDebugUnitTest \
        :core:vision:testDebugUnitTest \
        :core:vision:compileDebugAndroidTestKotlin \
        :core:data:testDebugUnitTest \
        :core:data:compileDebugAndroidTestKotlin \
        :app:testFunctionalTestUnitTest \
        :app:compileFunctionalTestAndroidTestKotlin \
        :app:assembleDebug \
        :app:assembleFunctionalTest \
        :app:assembleInternal
)

INTERNAL_BUILD_CONFIG="$(find android/app/build/generated/source/buildConfig/internal \
    -name BuildConfig.java -print -quit)"
if [[ -z "$INTERNAL_BUILD_CONFIG" ]] ||
   ! grep -Fq 'BUILD_CHANNEL = "internal-evaluation"' "$INTERNAL_BUILD_CONFIG" ||
   ! grep -Fq "MODEL_CATALOG_URL = \"$CURRENT_CATALOG_URL\"" "$INTERNAL_BUILD_CONFIG" ||
   ! grep -Fq "VERSION_CODE = $EXPECTED_INTERNAL_VERSION_CODE" "$INTERNAL_BUILD_CONFIG" ||
   ! grep -Fq "VERSION_NAME = \"$EXPECTED_INTERNAL_VERSION_NAME\"" "$INTERNAL_BUILD_CONFIG" ||
   grep -Eq '(SUPABASE_URL|SUPABASE_PUBLISHABLE_KEY|FIREBASE_API_KEY|FIREBASE_APPLICATION_ID|FIREBASE_PROJECT_ID|FIREBASE_GCM_SENDER_ID|PRIVACY_POLICY_URL|AUTH_REDIRECT_URL) = ""' "$INTERNAL_BUILD_CONFIG"; then
    echo "internal APK is missing its local product configuration" >&2
    exit 5
fi

step "Android lint on isolated JDK 25"
JAVA25_HOME="$(mise where 'java@temurin-25')"
(
    cd android
    env JAVA_HOME="$JAVA25_HOME" PATH="$JAVA25_HOME/bin:$PATH" \
      ./gradlew --no-daemon --stacktrace \
        :app:lintDebug \
        :core:domain:lintDebug \
        :core:vision:lintDebug \
        :core:data:lintDebug
)

step "resolved dependency SBOMs"
rm -rf "$REPO_ROOT/.local/local-ci-sbom"
mkdir -p "$REPO_ROOT/.local/local-ci-sbom"
env JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
    bash tools/sbom/generate.sh "$REPO_ROOT/.local/local-ci-sbom"

step "working-tree integrity"
run_node node tools/ci/check-current-evidence.mjs
git diff --check
git diff --cached --check
printf '\nLocal CI passed. GitHub Actions were not used.\n'
