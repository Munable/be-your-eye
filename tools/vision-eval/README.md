# External Replay Set v1 data preparation

The tracked suite definition is
`model-tools/v3/evaluation/external-replay-set-v1.json`. It binds the current three active package
identities to public sources, fixed selection rules, a frozen signed parameter policy, and a
zero-runner-error observation contract. It is not an accuracy result or release gate.

The preparer assembles one bounded slice for each active package:

- COCO 2017 val: one deterministic `largest_bbox_anchor` observational stratum plus two hash-ranked
  observational images for each of the Catalog's 80 targets. The anchor is not a manually reviewed
  clear image or recognition-accuracy gate;
- Mendeley energy-meter data: all 168 images with published digit annotations plus an explicitly
  fixed 24-case manual-ROI diagnostic, including EXIF cases 075 and 117. Its cohort depends only on
  published truth, never on an earlier model result. JPEG orientation is read fail-closed (currently
  orientations 1 and 3); published
  boxes are transformed into upright normalized coordinates before x sorting and ROI union. The ROI
  adds fixed normalized padding and reuses the original media. Scoring uses the upright x-sorted
  digit sequence because decimal-point truth is absent; these outputs are observations, not an
  acceptance threshold;
- Objectron: book, bottle, and cup, two identities each, with three references, two later positives,
  and one paired same-category hard negative per identity.

Camera reels remain a separate layer. The tool does not turn a prepared bundle into model or product
evidence.

Every reader case declares `expected.input_mode`. Full-frame and manual-ROI outcomes are both
reported as diagnostics rather than a commercial accuracy gate.
Object-model checks are wiring observations rather than supplier benchmarks. All 240 COCO cases
report stratified hit, miss, IoU, and target-label-presence observations. Exact input identity and
complete case results are validated, while recognition outcomes have no pass threshold. Image
eligibility only selects the cohort; after an image is selected, IoU
truth includes every valid non-crowd/non-depiction instance of the same source label, including
instances below the selection size threshold. EfficientDet-Lite2 uses the frozen 0.5 score threshold
from the official example configuration; it is not swept here and the replay remains wiring
observation rather than an independent accuracy claim.

```bash
python3 tools/vision-eval/prepare_external_replay_set.py dry-run
python3 tools/vision-eval/prepare_external_replay_set.py status
python3 tools/vision-eval/prepare_external_replay_set.py prepare --dataset all --jobs 8
python3 -m unittest discover -s tools/vision-eval -p 'test_*.py'
```

The default root is
`/Volumes/DevDisk/DeveloperData/be-your-eyes/external-eval-cache`. `prepare` rejects repository-local
and non-`/Volumes` roots so public media cannot silently consume the small system disk or enter Git.
The external bundle layout is:

```text
external-replay-set-v1/bundle/
  manifest.json
  media/<dataset>/<source image>
```

`dry-run` and `status` do not access the network. Public downloads use a direct connection first and
ignore stale ambient proxy variables. The generated bundle manifest binds every selected media file
by SHA-256 and byte size and records aggregate source-index hashes; model outputs belong in one
aggregate runner result rather than per-image evidence files.

## Signed runtime direct replay

The wrapper injects only one package and its filtered cases into the test APK:

```bash
tools/vision-eval/run-signed-replay.sh --package efficientdet_lite2_object_v1 --prepare-only
tools/vision-eval/run-signed-replay.sh --package efficientdet_lite2_object_v1 --serial SERIAL
tools/vision-eval/run-signed-replay.sh --package numeric_reader_ppocrv6_medium_v1 \
  --input-mode manual_roi_diagnostic --serial SERIAL
```

Each run pulls one aggregate summary under the external replay root. A runner failure exits 5 and
retains any summary produced; recognition misses and unavailable observations are reported without
being converted into product-accuracy claims. The host writes one compact
`external-vision-replay-observation.json` binding the suite, filtered bundle, Catalog, package
manifest, input mode, observation status, and wiring failures. Every run hashes the app and
package-filtered test APKs, reads both
installed APK hashes back from the device, and binds those identities plus the non-secret Android
manufacturer/model/API/ABI/build profile into the observation. Cohort,
source-index, source-tree, or binary drift is an infrastructure error. Signed metadata is evaluated
at the device's current wall-clock time rather than at a replayed issuance time.

Object-case `top_target_detections` contains only detections that survived the production
`TargetProfile.ObjectClass` filter. It is useful for target-score and box diagnosis, but it is not an
all-class misclassification list. Reference direct replay is single-frame runtime state
classification; only the separate camera/MonitoringSession layer may claim product Events.
The direct runner verifies signed Catalog/Manifest and artifact identities before creating the
production runtime, but injects a prevalidated Internal fixture context; it does not replace the
separate `ModelArtifactStore` download, license-text hash, product-entitlement, or channel-admission
tests.

## Deterministic camera reel

`reel.py` turns one external negative source and one external positive source into
the fixed product-rule sequence below. Both sources can be a still image or a
video; the source aspect ratio is preserved with black padding.

```text
0-5 absent | 5-12 present | 12-18 absent | 18-25 present
```

The source spec records `path`, `media_type`, public `source_url`, `license`, and
the exact source `sha256` under `sources.absent` and `sources.present`. It also
contains a non-empty `expectation` object such as the package and target ID. A
minimal example is:

```json
{
  "schema_version": "1.0",
  "reel_id": "coco-person-001",
  "expectation": {"mode": "object_detection", "target_id": "person"},
  "sources": {
    "absent": {
      "path": "/Volumes/DevDisk/DATASET/negative.jpg",
      "media_type": "image",
      "source_url": "https://DATASET/negative",
      "license": "DATASET LICENSE",
      "sha256": "64_LOWERCASE_HEX_CHARACTERS"
    },
    "present": {
      "path": "/Volumes/DevDisk/DATASET/positive.mp4",
      "media_type": "video",
      "start_seconds": 12.5,
      "source_url": "https://DATASET/positive",
      "license": "DATASET LICENSE",
      "sha256": "64_LOWERCASE_HEX_CHARACTERS"
    }
  }
}
```

Build and verify the H.264/yuv420p, 1280x720, 30 fps, no-audio reel:

```bash
python3 tools/vision-eval/reel.py build --spec /absolute/path/spec.json --dry-run
python3 tools/vision-eval/reel.py build --spec /absolute/path/spec.json
python3 tools/vision-eval/reel.py verify --reel /absolute/path/reel.mp4
```

Start an isolated API 36 emulator with the reel as the CameraX back-camera
source. A command after `--` runs after boot with `ANDROID_SERIAL` set:

```bash
tools/vision-eval/run-camera-replay.sh emulator --reel /absolute/path/reel.mp4 --dry-run
tools/vision-eval/run-camera-replay.sh emulator --reel /absolute/path/reel.mp4 -- \
  adb shell getprop ro.build.version.sdk
```

For PJA110, first put the app on its camera screen, then play the identical reel
full screen on the Mac without stretching:

```bash
tools/vision-eval/run-camera-replay.sh pja110 --reel /absolute/path/reel.mp4 --dry-run
tools/vision-eval/run-camera-replay.sh pja110 --reel /absolute/path/reel.mp4
```

Reel generation and emulator startup are automated; model scoring and event
readback remain separate. Screen replay is not natural-scene accuracy evidence.
