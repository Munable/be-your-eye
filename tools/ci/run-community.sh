#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK with platform 36 and build-tools 36.0.0}"
export PATH="$JAVA_HOME/bin:$PATH"
node .github/scripts/check-repo-policy.mjs
node --test .github/scripts/check-repo-policy.test.mjs tools/ci/community-policy.test.mjs
npm ci --ignore-scripts --prefix model-tools/catalog-validator
npm ci --ignore-scripts --prefix supabase/tests
npm test --prefix model-tools/catalog-validator
npm test --prefix supabase/tests
bash tools/release/check-secret-hygiene.sh
./android/gradlew -p android --no-daemon -PtestBuildType=communityDebug \
    :core:domain:testDebugUnitTest :core:vision:testDebugUnitTest :core:data:testDebugUnitTest \
    :app:testCommunityDebugUnitTest :app:lintCommunityDebug :app:assembleCommunityDebug
