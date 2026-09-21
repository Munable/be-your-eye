# Reference weights: publisher license review

Decision (2026-09-18): **approved for commercial use and redistribution of the exact
publisher weights below under Apache-2.0**. This is a license assessment, not product
accuracy acceptance or a new license granted by Be Your Eye.

| Publisher artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Google Mobile Object Localizer, TFLite metadata/1 | 1,867,321 | `40593533fe47933022ec0dca71b8fa59f7df564d40d496faae44e4c1941e452e` |
| MediaPipe MobileNetV3 Large image embedder, float32/1 | 10,889,458 | `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d` |

The local files were hashed and their embedded `TFLITE_METADATA` FlatBuffer was
decoded using the publisher's [ModelMetadata schema](https://github.com/tensorflow/tflite-support/blob/master/tensorflow_lite_support/metadata/metadata_schema.fbs).
Both `ModelMetadata.license` fields declare Apache License Version 2.0. Their
`author` fields are respectively `TensorFlow` and `MediaPipe`. The embedder metadata
name is `MobileNetV3 image classifier`; the exact hash and official image-embedder
download identify the artifact used here, not this generic metadata name alone.

Evidence checked on 2026-09-18:

- Google's [Mobile Object Localizer model card](https://www.kaggle.com/models/google/mobile-object-localizer-v1)
  explicitly applies Apache-2.0 to the model and requests the attribution
  `Google Mobile Object Localizer` in production. The exact source variation is
  [metadata/1](https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1).
- The [MediaPipe image-embedding guide](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder)
  identifies the pretrained MobileNetV3 family and links the
  [exact float32/1 weight](https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite).
  A fresh streaming download matched the byte count and SHA-256 above; no second
  persistent weight copy was retained.
- A [MediaPipe collaborator's model-license clarification](https://github.com/google-ai-edge/mediapipe/issues/4906#issuecomment-1778649604)
  states that MediaPipe models can be used commercially under Apache-2.0. This
  supports the exact weight's own embedded grant; source-code headers or the
  documentation footer alone are not being treated as a weight license.
- A [Keras collaborator's clarification](https://github.com/keras-team/keras/issues/13362#issuecomment-535268214)
  distinguishes a pretrained checkpoint's license from the dataset's license.
  This is contextual guidance, not an additional grant for these particular files.

The guide discloses ImageNet pretraining. Be Your Eye consumes the released
pretrained weights; it does not obtain or redistribute ImageNet images, retrain,
fine-tune or export replacement weights. The dataset's access terms therefore do
not, on their own, establish a noncommercial restriction on these separately
licensed publisher artifacts. The earlier internal review's unconditional demand
for an individually signed ImageNet-provenance letter was stricter than the
publisher evidence supports and is superseded for these exact bytes. No private
publisher signoff was requested or received, and none is claimed.

Redistribution must include the pinned Apache-2.0 text and applicable attribution
and notices. `Google Mobile Object Localizer` is credited as requested. The weights
remain unmodified. This grants no trademark endorsement and requires no disclosure
of Be Your Eye first-party source. License-text SHA-256:
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.

The existing localized crop and cosine-head configuration remains first-party
code/data. Its exact bytes and runtime contract must still be bound by each new
signed Manifest. The earlier internal packages and evidence remain immutable.
New Commercial Catalog/current/rollback signatures, fresh-device installation,
reference true/near-negative camera behavior and actual paid-use acceptance are
separate requirements. In particular, this review does not resolve the recorded
same-book recognition failure or allow it to be marked passed.
