# Catalog validator

Node 24 validator for v3 signed Catalogs and Manifest package gates. It has no third-party runtime dependencies.

```sh
npm test
node src/cli.mjs capability-catalog ../../test-vectors/valid/capability-catalog.json
```

The three runtime families are `object_detection_v1`, `similarity_match_v1` and `reading_pipeline_v1`. Object detection requires `prompt_modes=[object_class]` and exact targets from the package's signed bilingual `class_map`; a user's description is resolved against the signed Catalog before it reaches this runtime boundary. Reference and reading packages retain their existing contracts. Every operational row is executable and binds one signed recipe, model card, and at least one active package. Package entries do not carry billing tiers. Community local use is independent of account/cloud entitlement. The distinct `community` channel requires approved redistribution and the same exact public metadata checks as commercial admission. Models that are not ready are absent from the Catalog rather than represented by placeholder rows. The validator checks schema, DAG, tensor shapes, class-map conflicts, exact artifact/hash/license metadata, device compatibility and channel policy. Runtime package choice is made once by the production Catalog/Manifest preparation path and uses the signed publisher defaults; there is no second benchmark selector. The validator does not claim real-device quality or Commercial readiness.
