# Community model sources

Weights are distributed in [this repository’s models-v1 release](https://github.com/Munable/be-your-eye/releases/tag/models-v1). Downloads require no project account, API key or maintainer server. These are unchanged publisher weights in the signed Community distribution. First-party source licensing does not replace any third-party terms. No training or model export is performed. The two JSON reference sidecars are first-party runtime configuration; the vocabulary is a pinned PaddleOCR-derived token table.

The Community decoder requires approved commercial-use and redistribution flags, license review evidence hash, exact IO metadata, signature and device compatibility. No artificial grant expiry is added; real third-party deadlines would still be enforced.

## efficientdet_lite2_object_v1

Version `0.3.1-community.1`; runtime `object_detection_v1`.

- model_source: [TensorFlow EfficientDet-Lite2 COCO 2017, variation version 1](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

- weights_source: [sha256:6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

- code_source: [official-tensorflow-example](https://github.com/tensorflow/examples/tree/master/lite/examples/object_detection/android)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1)

License: `Apache-2.0`; review: `model-tools/v3/object-detection/efficientdet-lite2/LICENSE-REVIEW.md` (SHA-256 `7c93d024fff5a02abe803222e50f148128cb9a17072ffe6a5a21f1776d7fe668`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 7557887 | `6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/efficientdet_lite2_object_v1.primary.tflite) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## numeric_reader_ppocrv6_medium_v1

Version `0.3.1-community.1`; runtime `reading_pipeline_v1`.

- model_source: [det-2ba1506c0380b8f0b03dd142459aac66d4421f6c+rec-50c7eacafc52fa7bcf4194e8cd08e46f8558504b](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/tree/50c7eacafc52fa7bcf4194e8cd08e46f8558504b)

- weights_source: [2ba1506c0380b8f0b03dd142459aac66d4421f6c](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c)

- code_source: [2661c7c0ef5c613e8f93c6e93b2e052399f0f854](https://github.com/PaddlePaddle/PaddleOCR/tree/2661c7c0ef5c613e8f93c6e93b2e052399f0f854)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/tree/50c7eacafc52fa7bcf4194e8cd08e46f8558504b)

License: `Apache-2.0`; review: `model-tools/v3/reviews/ppocrv6-tiny-det-medium-rec-composite-0.1.0-internal.13-2026-08-24.md` (SHA-256 `3d27a895b1e045d264181a9f0e49ee92a4bd0e8a978cfc3db628871dc840cba1`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 76554979 | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/numeric_reader_ppocrv6_medium_v1.primary.onnx) |
| locator | 1780590 | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/numeric_reader_ppocrv6_medium_v1.locator.onnx) |
| vocabulary | 112437 | `684a00dd6ca9dd491468412c305aee6a450f69a2800108ddb23837c15fbcaf49` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/numeric_reader_ppocrv6_medium_v1.vocabulary.json) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## similarity_mediapipe_mobilenet_v3_large_v1

Version `0.3.1-community.1`; runtime `similarity_match_v1`.

- model_source: [mobile-object-localizer-v1-plus-mobilenet-v3-large-v1](https://ai.google.dev/edge/api/mediapipe/python/mp/tasks/vision/ObjectDetector)

- weights_source: [mobile-object-localizer-v1-metadata-1](https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1)

- code_source: [bdddcbd09ea1588825d35fe7b715d1a14789a85a](https://github.com/google-ai-edge/mediapipe/blob/bdddcbd09ea1588825d35fe7b715d1a14789a85a/mediapipe/tasks/cc/vision/object_detector/object_detector_graph.cc)

- export_tool_source: [published-prebuilt-export-tool-not-disclosed](https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1)

License: `Apache-2.0`; review: `model-tools/v3/reviews/reference-publisher-license-2026-09-18.md` (SHA-256 `70aaa38310376e42237a05b9f829e5d62e394230d678eaf03e750143e8815946`).


| Role | Bytes | SHA-256 | Download |
|---|---:|---|---|
| primary | 1867321 | `40593533fe47933022ec0dca71b8fa59f7df564d40d496faae44e4c1941e452e` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/similarity_mediapipe_mobilenet_v3_large_v1.primary.tflite) |
| object_crop | 215 | `632fb7f5a7bb7d9fb0ce5bbe6de3b702713fe7ce429e19a7d4b1c9fd65315fd1` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/similarity_mediapipe_mobilenet_v3_large_v1.object_crop.json) |
| embedder | 10889458 | `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/similarity_mediapipe_mobilenet_v3_large_v1.embedder.tflite) |
| similarity_head | 78 | `b999d1d9bfb302494fecd6d3f856d621dbb298bd66839d7a82483a508a28ee1b` | [GitHub release asset](https://github.com/Munable/be-your-eye/releases/download/models-v1/similarity_mediapipe_mobilenet_v3_large_v1.similarity_head.json) |

Publisher-prebuilt weights are consumed byte-for-byte. Packaging signs metadata and copies verified artifacts; it does not reproduce training or claim an undisclosed export tool.

## Retrieval and independent packaging

`node tools/release/prepare-community-model-input.mjs /absolute/artifact-cache /absolute/build-input.json` fetches each exact public artifact, checks its size/hash, and produces input for `model-tools/catalog-validator/src/release-builder-cli.mjs`. Use your own signing keys and update the public key registries for an independent distribution. Never disable verification.

The eight artifacts total 98,762,965 bytes. Every release asset is bound by its byte count and SHA-256 in an Ed25519-signed Manifest. The signed Community Catalog does not expire after seven days; real license restrictions still apply. Model downloads from GitHub follow only the release-asset HTTPS hop and are verified before installation. A fork can mirror these exact files or issue its own reviewed, signed catalog.

## Training data and what we redistribute

| Component | Publisher-disclosed training source | What this project distributes |
| --- | --- | --- |
| TensorFlow EfficientDet-Lite2 | COCO 2017, identified by the [publisher model](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1) | Unmodified detector weights; the 80-class map and translations are signed metadata |
| MediaPipe MobileNetV3 Large embedder | ImageNet, per the [official model guide](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder) | Unmodified embedding weights, no ImageNet images |
| Google Mobile Object Localizer | A complete training-image inventory is not disclosed in the reviewed [publisher model card](https://www.kaggle.com/models/google/mobile-object-localizer-v1) | Unmodified Google Mobile Object Localizer weights; no source dataset |
| PaddleOCR PP-OCRv6 detector and recognizer | The pinned publisher cards do not establish a complete, reproducible training corpus for these exact ONNX exports | Unmodified ONNX files plus the pinned token vocabulary; no training images |

Be Your Eye does not train or fine-tune these models and does not claim ownership of their training data. A model’s weight license does not relicense its training datasets. Unknown training details remain unknown. The repository’s sample footage and test images are evaluation inputs, not a training corpus; see [demo sources](demos/SOURCES.md).

The release includes the applicable Apache-2.0 texts, attribution and exact license reviews. Those reviews assess redistribution of these specific files; their older product acceptance notes remain historical and do not establish real-world recognition quality.
