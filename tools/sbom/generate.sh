#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly OUTPUT_DIR_INPUT="${1:-$REPO_ROOT/sbom/generated}"
readonly ANDROID_AAB_INPUT="${2:-}"
readonly ANDROID_CYCLONEDX_VERSION="3.3.0"
readonly NPM_CYCLONEDX_VERSION="4.0.3"
readonly ANDROID_VARIANT="${BEYOUREYES_SBOM_ANDROID_VARIANT:-communityRelease}"
case "$ANDROID_VARIANT" in
    release|communityRelease) ANDROID_ARTIFACT_KIND=apk ;;
    *) printf 'Unsupported Android SBOM variant: %s\n' "$ANDROID_VARIANT" >&2; exit 1 ;;
esac
readonly ANDROID_ARTIFACT_KIND
readonly ANDROID_CONFIGURATION="${ANDROID_VARIANT}RuntimeClasspath"

sha256_value() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

write_digest() {
    local file="$1"
    local digest
    digest="$(sha256_value "$file")"
    printf '%s  %s\n' "$digest" "$(basename -- "$file")" > "$file.sha256"
}

write_provenance() {
    local sbom_file="$1"
    local generator_name="$2"
    local generator_version="$3"
    local source_path="$4"
    local dependency_configuration="${5:-}"
    local release_artifact="${6:-}"
    local sbom_digest source_digest
    sbom_digest="$(sha256_value "$sbom_file")"
    source_digest="$(sha256_value "$REPO_ROOT/$source_path")"
    local release_artifact_file='' release_artifact_sha256='' release_artifact_size='0'
    if [[ -n "$release_artifact" ]]; then
        release_artifact_file="$(basename -- "$release_artifact")"
        release_artifact_sha256="$(sha256_value "$release_artifact")"
        release_artifact_size="$(stat -f '%z' "$release_artifact" 2>/dev/null || stat -c '%s' "$release_artifact")"
    fi
    jq -n \
        --arg sbom_file "$(basename -- "$sbom_file")" \
        --arg sha256 "$sbom_digest" \
        --arg generator_name "$generator_name" \
        --arg generator_version "$generator_version" \
        --arg source_path "$source_path" \
        --arg source_sha256 "$source_digest" \
        --arg dependency_configuration "$dependency_configuration" \
        --arg release_artifact_file "$release_artifact_file" \
        --arg release_artifact_sha256 "$release_artifact_sha256" \
        --argjson release_artifact_size "$release_artifact_size" \
        '{
            sbom_file: $sbom_file,
            sha256: $sha256,
            generator: {name: $generator_name, version: $generator_version},
            primary_dependency_input: {path: $source_path, sha256: $source_sha256},
            dependency_configuration: (
                if $dependency_configuration == "" then null else $dependency_configuration end
            ),
            release_artifact: (
                if $release_artifact_file == "" then null else {
                    file: $release_artifact_file,
                    sha256: $release_artifact_sha256,
                    size_bytes: $release_artifact_size
                } end
            )
        }' > "$sbom_file.provenance.json"
    write_digest "$sbom_file"
    write_digest "$sbom_file.provenance.json"
}

generate_npm_sbom() {
    local project_dir="$1"
    local output_name="$2"
    (
        cd "$REPO_ROOT/$project_dir"
        test -f package-lock.json
        npm exec --yes --package="@cyclonedx/cyclonedx-npm@$NPM_CYCLONEDX_VERSION" -- \
            cyclonedx-npm \
            --spec-version 1.6 \
            --output-reproducible \
            --output-format JSON \
            --output-file "$OUTPUT_DIR/$output_name"
    )
    write_provenance \
        "$OUTPUT_DIR/$output_name" \
        "@cyclonedx/cyclonedx-npm" \
        "$NPM_CYCLONEDX_VERSION" \
        "$project_dir/package-lock.json"
}

command -v jq >/dev/null 2>&1
command -v npm >/dev/null 2>&1
command -v java >/dev/null 2>&1
if [[ -n "$ANDROID_AAB_INPUT" ]]; then
    test -s "$ANDROID_AAB_INPUT"
    if [[ -z "${BEYOUREYES_VERSION_NAME:-}" ]]; then
        printf 'BEYOUREYES_VERSION_NAME is required when binding an Android artifact SBOM\n' >&2
        exit 1
    fi
fi
mkdir -p "$OUTPUT_DIR_INPUT"
OUTPUT_DIR="$(CDPATH='' cd -- "$OUTPUT_DIR_INPUT" && pwd)"
readonly OUTPUT_DIR
export BEYOUREYES_SBOM_ANDROID_CONFIGURATION="$ANDROID_CONFIGURATION"

(
    cd "$REPO_ROOT/android"
    ./gradlew --no-daemon --stacktrace \
        --init-script "$SCRIPT_DIR/android-cyclonedx.init.gradle" \
        cyclonedxBom
)

readonly GRADLE_SBOM="$REPO_ROOT/android/build/reports/cyclonedx/bom.json"
test -s "$GRADLE_SBOM"
ANDROID_AAB_SHA256=''
ANDROID_AAB_SIZE='0'
ANDROID_AAB_FILE=''
if [[ -n "$ANDROID_AAB_INPUT" ]]; then
    ANDROID_AAB_SHA256="$(sha256_value "$ANDROID_AAB_INPUT")"
    ANDROID_AAB_SIZE="$(stat -f '%z' "$ANDROID_AAB_INPUT" 2>/dev/null || stat -c '%s' "$ANDROID_AAB_INPUT")"
    ANDROID_AAB_FILE="$(basename -- "$ANDROID_AAB_INPUT")"
fi
readonly ANDROID_AAB_SHA256 ANDROID_AAB_SIZE ANDROID_AAB_FILE

# CycloneDX Gradle emits the multi-project root as metadata.component and the
# real :app project as a regular component. Promote that exact :app component
# (including its bom-ref) so the release artifact is attached to the node that
# already owns the application dependency graph, rather than to a disconnected
# synthetic root.
jq -S \
    --arg variant "$ANDROID_VARIANT" \
    --arg artifact_kind "$ANDROID_ARTIFACT_KIND" \
    --arg configuration "$ANDROID_CONFIGURATION" \
    --arg aab_file "$ANDROID_AAB_FILE" \
    --arg aab_sha256 "$ANDROID_AAB_SHA256" \
    --argjson aab_size "$ANDROID_AAB_SIZE" \
    --arg version "${BEYOUREYES_VERSION_NAME:-}" '
    del(.metadata.timestamp, .serialNumber) |
    (.components // []) as $all_components |
    ([
        $all_components[] |
        select((."bom-ref" // "") | test("[?&]project_path=%3Aapp(?:&|$)"))
    ]) as $app_components |
    if ($app_components | length) != 1 then
        error("expected exactly one CycloneDX project_path=:app component")
    else
        $app_components[0] as $app |
        $app."bom-ref" as $original_app_ref |
        ($app.purl // "") as $original_app_purl |
        if ($aab_file != "" and
            ((($original_app_ref | test("@[^?]+[?]")) and
              ($original_app_purl | test("@[^?]+[?]"))) | not)) then
            error("release :app component does not have normalizable purl identity")
        else
        (if $aab_file == "" then
            $original_app_ref
        else
            ($original_app_ref | sub("@[^?]+[?]"; "@" + $version + "?"))
        end) as $release_app_ref |
        (if $aab_file == "" then
            $original_app_purl
        else
            ($original_app_purl | sub("@[^?]+[?]"; "@" + $version + "?"))
        end) as $release_app_purl |
        .metadata.component = (
            $app |
            .type = "application" |
            if $aab_file == "" then . else
                .version = $version |
                .purl = $release_app_purl |
                ."bom-ref" = $release_app_ref
            end |
            .properties = (
                ((.properties // []) |
                    map(select(.name != "app.beyoureyes.android.variant" and
                               .name != "app.beyoureyes.android.configuration" and
                               .name != "app.beyoureyes.android.version_name" and
                               .name != "app.beyoureyes.android.aab_file" and
                               .name != "app.beyoureyes.android.aab_size_bytes" and
                               .name != "app.beyoureyes.android.apk_file" and
                               .name != "app.beyoureyes.android.apk_size_bytes"))) +
                [
                    {name: "app.beyoureyes.android.variant", value: $variant},
                    {name: "app.beyoureyes.android.configuration", value: $configuration}
                ] +
                (if $aab_file == "" then [] else [
                    {name: "app.beyoureyes.android.version_name", value: $version},
                    {name: ("app.beyoureyes.android." + $artifact_kind + "_file"), value: $aab_file},
                    {name: ("app.beyoureyes.android." + $artifact_kind + "_size_bytes"), value: ($aab_size | tostring)}
                ] end)
            ) |
            if $aab_file == "" then
                del(.hashes)
            else
                .hashes = [{alg: "SHA-256", content: $aab_sha256}]
            end
        ) |
        .components = (
            $all_components |
            map(select((."bom-ref" // "") != $app."bom-ref")) |
            sort_by(."bom-ref" // .purl // ((.group // "") + ":" + (.name // "")))
        ) |
        .dependencies = (
            (.dependencies // []) |
            map(
                .ref = (
                    if .ref == $original_app_ref then $release_app_ref else .ref end
                ) |
                .dependsOn = (
                    (.dependsOn // []) |
                    map(
                        if . == $original_app_ref then $release_app_ref else . end
                    ) |
                    sort
                )
            ) |
            sort_by(.ref)
        )
        end
    end
' "$GRADLE_SBOM" > "$OUTPUT_DIR/android-resolved-dependencies.cdx.json"
write_provenance \
    "$OUTPUT_DIR/android-resolved-dependencies.cdx.json" \
    "org.cyclonedx.bom" \
    "$ANDROID_CYCLONEDX_VERSION" \
    "android/gradle/libs.versions.toml" \
    "$ANDROID_CONFIGURATION" \
    "$ANDROID_AAB_INPUT"

generate_npm_sbom \
    "model-tools/catalog-validator" \
    "catalog-validator-lock-dependencies.cdx.json"

bash "$SCRIPT_DIR/verify.sh" "$OUTPUT_DIR" "$ANDROID_AAB_INPUT"
