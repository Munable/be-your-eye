# Community model sources

These are the exact unchanged publisher weights in the signed Community candidate. First-party source licensing does not replace any third-party terms. No training or model export is performed. The two JSON reference sidecars are first-party runtime configuration; the vocabulary is a pinned PaddleOCR-derived token table.

The Community decoder requires approved commercial-use and redistribution flags, license review evidence hash, exact IO metadata, signature and device compatibility. No artificial grant expiry is added; real third-party deadlines would still be enforced.

## efficientdet_lite2_object_v1

Version `0.3.0-community.1`; runtime `object_detection_v1`.

- model_source: [TensorFlow EfficientDet-Lite2 COCO 2017, variation version 1](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

- weights_source: [sha256:6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

- code_source: [official-tensorflow-example](https://github.com/tensorflow/examples/tree/master/lite/examples/object_detection/android)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

License: `Apache-2.0`; review: `model-tools/v3/object-detection/efficientdet-lite2/LICENSE-REVIEW.md` (SHA-256 `7c93d024fff5a02abe803222e50f148128cb9a17072ffe6a5a21f1776d7fe668`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 7557887 | `6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-31/be-your-eye-v1/artifacts/efficientdet_lite2_object_v1/efficientdet_lite2.tflite) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## numeric_reader_ppocrv6_medium_v1

Version `0.3.0-community.1`; runtime `reading_pipeline_v1`.

- model_source: [det-2ba1506c0380b8f0b03dd142459aac66d4421f6c+rec-50c7eacafc52fa7bcf4194e8cd08e46f8558504b](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/tree/50c7eacafc52fa7bcf4194e8cd08e46f8558504b)

- weights_source: [2ba1506c0380b8f0b03dd142459aac66d4421f6c](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c)

- code_source: [2661c7c0ef5c613e8f93c6e93b2e052399f0f854](https://github.com/PaddlePaddle/PaddleOCR/tree/2661c7c0ef5c613e8f93c6e93b2e052399f0f854)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/tree/50c7eacafc52fa7bcf4194e8cd08e46f8558504b)

License: `Apache-2.0`; review: `model-tools/v3/reviews/ppocrv6-tiny-det-medium-rec-composite-0.1.0-internal.13-2026-08-24.md` (SHA-256 `3d27a895b1e045d264181a9f0e49ee92a4bd0e8a978cfc3db628871dc840cba1`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 76554979 | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-03/v3.1-combined-1/artifacts/numeric_reader_ppocrv6_medium_v1/primary.onnx) |
| locator | 1780590 | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-12/first-release-3/artifacts/numeric_reader_ppocrv6_medium_v1/locator.onnx) |
| vocabulary | 112437 | `684a00dd6ca9dd491468412c305aee6a450f69a2800108ddb23837c15fbcaf49` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-03/v3.1-combined-1/artifacts/numeric_reader_ppocrv6_medium_v1/vocabulary.json) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## similarity_mediapipe_mobilenet_v3_large_v1

Version `0.3.0-community.1`; runtime `similarity_match_v1`.

- model_source: [mobile-object-localizer-v1-plus-mobilenet-v3-large-v1](https://ai.google.dev/edge/api/mediapipe/python/mp/tasks/vision/ObjectDetector)

- weights_source: [mobile-object-localizer-v1-metadata-1](https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1)

- code_source: [bdddcbd09ea1588825d35fe7b715d1a14789a85a](https://github.com/google-ai-edge/mediapipe/blob/bdddcbd09ea1588825d35fe7b715d1a14789a85a/mediapipe/tasks/cc/vision/object_detector/object_detector_graph.cc)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1)

License: `Apache-2.0`; review: `model-tools/v3/reviews/reference-publisher-license-2026-09-18.md` (SHA-256 `70aaa38310376e42237a05b9f829e5d62e394230d678eaf03e750143e8815946`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 1867321 | `40593533fe47933022ec0dca71b8fa59f7df564d40d496faae44e4c1941e452e` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-10/first-release-5/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/mobile_object_localizer_v1.tflite) |
| object_crop | 215 | `632fb7f5a7bb7d9fb0ce5bbe6de3b702713fe7ce429e19a7d4b1c9fd65315fd1` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-14/first-release-1/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/prominent_object_candidates_v2.json) |
| embedder | 10889458 | `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-03/v3.1-combined-1/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/primary.tflite) |
| similarity_head | 78 | `b999d1d9bfb302494fecd6d3f856d621dbb298bd66839d7a82483a508a28ee1b` | [immutable artifact](https://models.beyoureye.com/internal-evaluation/2026-08-14/first-release-1/artifacts/similarity_mediapipe_mobilenet_v3_large_v1/l2_prototype_cosine_candidates_v2.json) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## Retrieval and independent packaging

`node tools/release/prepare-community-model-input.mjs /absolute/artifact-cache /absolute/build-input.json` fetches each exact public artifact, checks its size/hash, and produces input for `model-tools/catalog-validator/src/release-builder-cli.mjs`. Use your own signing keys and update the public key registries for an independent distribution. Never disable verification.

All eight artifacts (98,762,965 bytes) were independently downloaded from the public URLs and matched their signed size and SHA-256 on 2026-09-21. This verifies the development host’s route, not phone, unproxied mainland or overseas acceptance. Real device download/hash/native self-test is tracked separately in evidence/current/05-release.json.
