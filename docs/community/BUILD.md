# Build Community

[中文](BUILD.zh-CN.md) · [Project home](../../README.md) · [User guide](../../USER_MANUAL.md)

**Developer preview: no public APK is available yet.** Build the debug app below to try it yourself. [Releases](https://github.com/Munable/be-your-eye/releases/tag/models-v1) contains model assets, not an app installer. Monitoring requires Android 8+, arm64 and 8 GB RAM; [physical-device acceptance remains open](DEVICE_SUPPORT.md).

## Build and install for local use

Use JDK 25 (tested Temurin 25.0.4), Android SDK platform 36/build-tools 36.0.0, and Node 24.16 or newer 24.x. Gradle 9.5 is pinned by the wrapper. No backend deployment, service account or Deno runtime is needed.

Set standard `JAVA_HOME` and `ANDROID_HOME`, install the SDK components and platform-tools, then clone the source (or open your existing checkout) and run the checks from its root:

```sh
git clone https://github.com/Munable/be-your-eye.git
cd be-your-eye
bash tools/ci/run-community.sh
# Debug is a runnable arm64 local app, package app.beyoureyes.monitor.community.debug
# android/app/build/outputs/apk/communityDebug/app-communityDebug.apk
```

Connect one physical phone by USB, enable USB debugging and approve this computer on the phone. With `adb` from Android SDK platform-tools on your `PATH`, install the locally built debug APK:

```sh
adb -d install -r android/app/build/outputs/apk/communityDebug/app-communityDebug.apk
```

Open Be Your Eye on the phone, allow the camera when asked, and follow the [user guide](../../USER_MANUAL.md). Keep the app visible while monitoring. Confirm the first model download, then check a condition, a local event and a notification on a scene you can try safely. Optional [paired alerts](PAIRING.md) need internet on both phones.

If installation is refused, check the phone's authorization prompt and developer installation settings. Do not uninstall an existing app or erase its data to bypass a refusal. An incompatible existing signing certificate needs a deliberate data-preservation decision; `-r` does not bypass signature checks.

No `.env.local`, Supabase project, Firebase registration, purchase, API key or private signing material is needed for those steps. The initial dependency and model downloads need internet. This is an independent build path, not a claim of byte-identical reproducibility. On this development Mac, existing build/cache links stay on DevDisk; a clean checkout uses standard Gradle paths or the builder's explicit cache configuration.

## Release candidates and signing

Freeze only committed source. Pick an external directory with sufficient space:

```sh
COMMUNITY_OUTPUT_DIR=/your/release/directory bash tools/release/build-community.sh --unsigned
```

The unsigned release cannot be installed until signed. Official candidates use `--signed` and `BEYOUREYES_COMMUNITY_SIGNING_ENV` pointing to a mode-600 file outside the repository, exporting `BEYOUREYES_RELEASE_STORE_FILE`, `BEYOUREYES_RELEASE_STORE_PASSWORD`, `BEYOUREYES_RELEASE_KEY_ALIAS`, `BEYOUREYES_RELEASE_KEY_PASSWORD`. Keep the stable key and its recovery copy outside Git. Never distribute debug signatures as official releases. Release package is `app.beyoureyes.monitor.community`; it does not replace older packages with different application IDs. Test upgrades with the same certificate; no legacy-data migration is automatic.

## Models and independent distributors

The APK includes public signed metadata; model weights are hosted in [this
repository’s models-v1 release](https://github.com/Munable/be-your-eye/releases/tag/models-v1).
The app verifies signatures, exact hashes, sizes, licenses, runtime contracts and
device compatibility. Models download only after confirmation. The Community
Catalog has no renewable expiry and installed models work offline. Actual
license deadlines remain enforced. [MODELS](MODELS.md) records the exact origins,
training-data disclosures and licenses.

An independent distributor can create their own Ed25519 keys, update the public registries in `StrictSignedJson.kt` and `release-public-keys.mjs`, use the same manifest/catalog schemas, and sign with `release-builder-cli.mjs`. Supply exact artifacts at the input paths in `model-tools/v3/releases/community/templates/release-build-input.json` (or generate an input file with your paths), retaining licenses and review evidence. Replace hosting URLs with your verified public locations before signing; do not alter signed bytes afterward. `COMMUNITY_MODEL_CATALOG_URL` selects a separate HTTPS Catalog instead of the bundled candidate. No private maintainer key or disabled signature check is required.

For instrumented Community checks use `-PtestBuildType=communityDebug`; camera-only fixture checks can explicitly select `functionalTest`. Natural-object, long-running, network-region and real participant checks are separate release gates.

To fetch and hash-check all exact public artifacts for repackaging, use:

```sh
node tools/release/prepare-community-model-input.mjs /absolute/external/cache /absolute/external/input.json
node model-tools/catalog-validator/src/release-builder-cli.mjs /absolute/external/input.json /absolute/external/release CATALOG_KEY_ID /private/catalog.pem MANIFEST_KEY_ID /private/manifest.pem
```

The fetch step is independent of maintainer caches and credentials. The builder verifies the referenced license/review files; use your own public key registries and reviewed HTTPS hosting configuration when creating an independent distribution.

After freezing a signed APK, generate and verify its dependency inventory:

```sh
BEYOUREYES_SBOM_ANDROID_VARIANT=communityRelease BEYOUREYES_VERSION_NAME=0.3.1 bash tools/sbom/generate.sh /absolute/release/sbom /absolute/release/be-your-eye-community.apk
```

The SBOM records its exact APK hash. This does not replace a dependency vulnerability scan or physical-device acceptance.

The optional object-camera replay requires `BEYOUREYES_OBJECT_CAMERA_IMAGE` pointing to a PNG you have permission to use, showing a centered apple. The repository does not distribute a test photo of uncertain provenance. This replay is separate from Community CI and is not natural-scene acceptance.
