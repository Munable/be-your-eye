# Dependency inventories

`tools/sbom/generate.sh OUTPUT_DIR [ANDROID_ARTIFACT]` creates CycloneDX inventories for the Android Community runtime and Catalog Validator. It uses the resolved Gradle dependencies and the validator lockfile. The retired Supabase, billing and voice services are no longer part of the shipped dependency graph.

The default Android variant is `communityRelease`. Pass the exact APK to bind its name, size and SHA-256 to the inventory and provenance. `tools/sbom/verify.sh OUTPUT_DIR [ANDROID_ARTIFACT]` verifies the inventories, checks the same artifact and rejects retired cloud/payment SDKs.

Release evidence keeps the source commit, APK hash, inventory hashes, tool versions and model release identity. Model origins, licenses and training-data disclosures are in [MODELS](../docs/community/MODELS.md) and the signed release metadata.

Generated inventories belong in the chosen external output directory, not in Git. A generated SBOM is a dependency inventory; it does not establish vulnerability freedom or device readiness. GitHub Actions runs the public Community checks on pushes and pull requests.
