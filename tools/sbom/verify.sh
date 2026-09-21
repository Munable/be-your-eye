#!/usr/bin/env bash
set -euo pipefail

readonly OUTPUT_DIR="${1:?usage: verify.sh OUTPUT_DIR}"
readonly ANDROID_AAB_INPUT="${2:-}"
readonly ANDROID_VARIANT="${BEYOUREYES_SBOM_ANDROID_VARIANT:-release}"
case "$ANDROID_VARIANT" in
    release) ANDROID_ARTIFACT_KIND=aab ;;
    website|communityRelease) ANDROID_ARTIFACT_KIND=apk ;;
    *) printf 'Unsupported Android SBOM variant: %s\n' "$ANDROID_VARIANT" >&2; exit 1 ;;
esac
readonly ANDROID_ARTIFACT_KIND
readonly ANDROID_CONFIGURATION="${ANDROID_VARIANT}RuntimeClasspath"
SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly ANDROID_SBOM="$OUTPUT_DIR/android-resolved-dependencies.cdx.json"
readonly SUPABASE_TEST_SBOM="$OUTPUT_DIR/supabase-test-lock-dependencies.cdx.json"
readonly CATALOG_SBOM="$OUTPUT_DIR/catalog-validator-lock-dependencies.cdx.json"
readonly SUPABASE_EDGE_SBOM="$OUTPUT_DIR/supabase-edge-lock-dependencies.cdx.json"
readonly WEBSITE_BILLING_SBOM="$OUTPUT_DIR/website-billing-lock-dependencies.cdx.json"

for file in "$ANDROID_SBOM" "$SUPABASE_TEST_SBOM" "$CATALOG_SBOM" "$SUPABASE_EDGE_SBOM" "$WEBSITE_BILLING_SBOM"; do
    test -s "$file"
    jq -e '.bomFormat == "CycloneDX" and (.components | type == "array")' "$file" >/dev/null
    test -s "$file.sha256"
    test -s "$file.provenance.json"
    test -s "$file.provenance.json.sha256"
    (
        cd "$(dirname -- "$file")"
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum --check "$(basename -- "$file").sha256"
            sha256sum --check "$(basename -- "$file").provenance.json.sha256"
        else
            shasum -a 256 --check "$(basename -- "$file").sha256"
            shasum -a 256 --check "$(basename -- "$file").provenance.json.sha256"
        fi
    ) >/dev/null
    jq -e --arg digest "$(awk '{print $1}' "$file.sha256")" \
        '.sha256 == $digest and
         (.generator.name | length > 0) and
         (.generator.version | length > 0) and
         (.primary_dependency_input.sha256 | test("^[0-9a-f]{64}$"))' \
        "$file.provenance.json" >/dev/null
done

jq -e --arg variant "$ANDROID_VARIANT" --arg configuration "$ANDROID_CONFIGURATION" '
    [.metadata.component.properties[]?] as $properties |
    ($properties | any(.[]; .name == "app.beyoureyes.android.variant" and .value == $variant)) and
    ($properties | any(.[]; .name == "app.beyoureyes.android.configuration" and .value == $configuration))
' "$ANDROID_SBOM" >/dev/null
jq -e '
    .metadata.component as $app |
    ($app.type == "application") and
    (($app."bom-ref" // "") | test("[?&]project_path=%3Aapp(?:&|$)")) and
    (($app.purl // "") | test("[?&]project_path=%3Aapp(?:&|$)")) and
    ([.components[]? | select((."bom-ref" // "") == $app."bom-ref")] | length == 0) and
    ([.dependencies[]? | select(.ref == $app."bom-ref")] | length == 1) and
    ([.dependencies[]? | select(.ref == $app."bom-ref") | .dependsOn[]?] as $direct_dependencies |
        [
            "%3Acore%3Adata",
            "%3Acore%3Adomain",
            "%3Acore%3Avision"
        ] as $required_projects |
        all($required_projects[];
            . as $project_path |
            $direct_dependencies |
            any(.[]; contains("project_path=" + $project_path))
        )
    )
' "$ANDROID_SBOM" >/dev/null
jq -e --arg configuration "$ANDROID_CONFIGURATION" '
    .dependency_configuration == $configuration
' "$ANDROID_SBOM.provenance.json" >/dev/null

if [[ -n "$ANDROID_AAB_INPUT" ]]; then
    test -s "$ANDROID_AAB_INPUT"
    if [[ -z "${BEYOUREYES_VERSION_NAME:-}" ]]; then
        printf 'BEYOUREYES_VERSION_NAME is required when verifying an Android artifact SBOM\n' >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        AAB_SHA256="$(sha256sum "$ANDROID_AAB_INPUT" | awk '{print $1}')"
    else
        AAB_SHA256="$(shasum -a 256 "$ANDROID_AAB_INPUT" | awk '{print $1}')"
    fi
    readonly AAB_SHA256
    AAB_SIZE="$(stat -f '%z' "$ANDROID_AAB_INPUT" 2>/dev/null || stat -c '%s' "$ANDROID_AAB_INPUT")"
    readonly AAB_SIZE
    jq -e \
        --arg sha256 "$AAB_SHA256" \
        --arg file "$(basename -- "$ANDROID_AAB_INPUT")" \
        --arg version "$BEYOUREYES_VERSION_NAME" \
        --arg artifact_kind "$ANDROID_ARTIFACT_KIND" \
        --argjson size "$AAB_SIZE" '
        .metadata.component as $app |
        ($app.type == "application") and
        ($app.version == $version) and
        ($app.purl == $app."bom-ref") and
        ($app.purl | contains("/app@" + $version + "?")) and
        ([.dependencies[]? | .ref, .dependsOn[]? |
            select(test("[?&]project_path=%3Aapp(?:&|$)"))] |
            all(.[]; . == $app."bom-ref")) and
        ([$app.hashes[]?] | any(.[]; .alg == "SHA-256" and .content == $sha256)) and
        ([$app.properties[]?] | any(.[]; .name == "app.beyoureyes.android.version_name" and .value == $version)) and
        ([$app.properties[]?] | any(.[]; .name == ("app.beyoureyes.android." + $artifact_kind + "_file") and .value == $file)) and
        ([$app.properties[]?] | any(.[]; .name == ("app.beyoureyes.android." + $artifact_kind + "_size_bytes") and .value == ($size | tostring)))
    ' "$ANDROID_SBOM" >/dev/null
    jq -e \
        --arg sha256 "$AAB_SHA256" \
        --arg file "$(basename -- "$ANDROID_AAB_INPUT")" \
        --argjson size "$AAB_SIZE" '
        .release_artifact == {file: $file, sha256: $sha256, size_bytes: $size}
    ' "$ANDROID_SBOM.provenance.json" >/dev/null
else
    jq -e '.release_artifact == null' "$ANDROID_SBOM.provenance.json" >/dev/null
fi

jq -e --arg pattern 'androidx[.:/]camera[/:]camera-core|androidx.camera:camera-core' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
jq -e --arg pattern 'androidx[.:/]room[/:]room-runtime|androidx.room:room-runtime' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
jq -e --arg pattern 'com.google.ai.edge.litert[/:]litert|litert:litert' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
jq -e --arg pattern 'com.microsoft.onnxruntime[/:]onnxruntime-android|onnxruntime:onnxruntime-android' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
if [[ "$ANDROID_VARIANT" == communityRelease ]]; then
    jq -e '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
        all(.[]; test("com.google.firebase|com.android.billingclient|com.google.android.gms") | not)' \
        "$ANDROID_SBOM" >/dev/null
else
jq -e --arg pattern 'com.google.firebase[/:]firebase-messaging|firebase:firebase-messaging' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
fi
jq -e --arg pattern 'io.github.jan-tennert.supabase[/:]auth-kt|supabase:auth-kt' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
jq -e --arg pattern 'com.google.crypto.tink[/:]tink-android|tink:tink-android' \
    '[.components[]? | [(.group // ""), (.name // ""), (.purl // "")] | join(":")] |
     any(.[]; test($pattern))' \
    "$ANDROID_SBOM" >/dev/null
jq -e '(.components | length) >= 10' "$ANDROID_SBOM" >/dev/null
jq -e '
    [.metadata.tools.components[]?] |
    any(.[]; .name == "cyclonedx-gradle-plugin" and .version == "3.3.0")
' "$ANDROID_SBOM" >/dev/null

jq -e '
    [.components[]? | (.purl // "")] as $purls |
    ($purls | any(.[]; startswith("pkg:npm/%40be-your-eyes/catalog-validator@"))) and
    ($purls | any(.[]; startswith("pkg:npm/tsx@"))) and
    ($purls | any(.[]; startswith("pkg:npm/typescript@"))) and
    (.components | length) >= 3
' "$SUPABASE_TEST_SBOM" >/dev/null

jq -e '
    .metadata.component.group == "@be-your-eyes" and
    .metadata.component.name == "catalog-validator" and
    .metadata.component.version == "0.1.0"
' "$CATALOG_SBOM" >/dev/null

for npm_sbom in "$SUPABASE_TEST_SBOM" "$CATALOG_SBOM"; do
    jq -e '
        [.metadata.tools.components[]?] |
        any(.[]; .group == "@cyclonedx" and .name == "cyclonedx-npm" and .version == "4.0.3")
    ' "$npm_sbom" >/dev/null
done

assert_direct_dependencies() {
    local project_dir="$1"
    local sbom_file="$2"
    local dependency
    while IFS= read -r dependency; do
        test -n "$dependency"
        jq -e --arg dependency "$dependency" '
            [.components[]?] |
            any(.[];
                (.name == $dependency) or
                (((.group // "") + "/" + (.name // "")) == $dependency)
            )
        ' "$sbom_file" >/dev/null
    done < <(jq -r '
        ((.dependencies // {}) + (.devDependencies // {})) |
        keys[]
    ' "$REPO_ROOT/$project_dir/package.json")
}

assert_direct_dependencies "supabase/tests" "$SUPABASE_TEST_SBOM"
assert_direct_dependencies "model-tools/catalog-validator" "$CATALOG_SBOM"

jq -e '[.components[]?] | any(.[]; .name == "stripe" and .version == "22.6.2")' "$WEBSITE_BILLING_SBOM" >/dev/null


printf 'android_components=%s\n' "$(jq '.components | length' "$ANDROID_SBOM")"
printf 'supabase_test_components=%s\n' "$(jq '.components | length' "$SUPABASE_TEST_SBOM")"
printf 'supabase_edge_components=%s\n' "$(jq '.components | length' "$SUPABASE_EDGE_SBOM")"
printf 'website_billing_components=%s\n' "$(jq '.components | length' "$WEBSITE_BILLING_SBOM")"
printf 'catalog_validator_component_records=%s\n' "$(jq '1 + (.components | length)' "$CATALOG_SBOM")"
