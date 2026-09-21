# Be Your Eye current three-package Internal candidate

This directory contains the reproducible unsigned inputs for the current Internal candidate.
Private signing keys and generated release bytes stay outside the repository. Catalog and Manifest
contracts are schema `4.0`; model packages contain no billing tier or product-accuracy evidence.

Run `tools/ci/build-current-internal-candidate.sh` to:

1. validate the current Catalog, three Manifests, exact source/license review hashes, and Internal status;
2. sign the Catalog, release envelope, and all three Manifests with the configured release keys;
3. write the configured ignored release output without overwriting it;
4. build the matching Internal APK.

The packages are reference `similarity_mediapipe_mobilenet_v3_large_v1`, reader
`numeric_reader_ppocrv6_medium_v1`, and common-object detector
`efficientdet_lite2_object_v1`. Lite2 reuses `object_detection_v1`, keeps the publisher model's
448×448 uint8 tensor contract, and uses the fixed score threshold `0.5`.

The ignored Lite2 verification copy is
`.local/model-artifacts/efficientdet-lite2/efficientdet-lite2.tflite`. The builder recomputes every
Manifest hash from signed bytes and requires the exact three active Catalog packages to match the
three build inputs. These historical Internal inputs do not constitute a Commercial release.
The exact reference weights now have a superseding publisher-license review at
`model-tools/v3/reviews/reference-publisher-license-2026-09-18.md`; a new Commercial
Manifest must bind that review. Internal signing and device smoke do not establish public readiness.

The Catalog is valid for seven days after `issued_at`, matching the Android decoder. Refresh an
expired Catalog with a new version and distribution URL before testing or sharing a new install.
An unchanged package keeps its original signed Manifest and artifact URLs; a Catalog refresh does
not require republishing model weights. `tools/ci/check-current-model-quality.mjs` checks the same
expiry boundary before device preparation and reports the deadline when it rejects a stale input.
