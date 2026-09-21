# Reference model publisher sign-off request

Status: **draft; not sent**. This request closes the only model-license gate that still blocks the
Be Your Eye Commercial Catalog. A generic MediaPipe or Apache-2.0 answer is not enough: the reply
must cover the exact publisher files below and their pretrained ImageNet provenance.

## Suggested issue title

Confirm commercial redistribution and ImageNet provenance for two exact Google AI Edge model files

## Suggested issue body

We plan to distribute the following unmodified model binaries as separately signed, downloadable
model-package artifacts for **Be Your Eye**, a closed-source commercial Android application:

1. Google `mobile_object_localizer_v1`, Kaggle variant `tfLite/metadata/1`  
   Source: <https://www.kaggle.com/models/google/mobile-object-localizer-v1/tfLite/metadata/1>  
   Size: `1,867,321` bytes  
   SHA-256: `40593533fe47933022ec0dca71b8fa59f7df564d40d496faae44e4c1941e452e`
2. MediaPipe MobileNetV3 Large Image Embedder, `float32/1`  
   Source: <https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite>  
   Size: `10,889,458` bytes  
   SHA-256: `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d`

The Google AI Edge Image Embedder documentation states that this MobileNetV3 family was trained on
ImageNet data: <https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder>.
The Kaggle page labels the localizer Apache 2.0, and MediaPipe maintainers have previously stated
that MediaPipe models are available for commercial use under Apache 2.0:
<https://github.com/google-ai-edge/mediapipe/issues/4906>.

Could an authorized Google AI Edge / MediaPipe maintainer please confirm in writing that:

1. Google is the publisher of both exact files identified above;
2. the Apache-2.0 grant applies to each file, including its pretrained-weight provenance;
3. commercial use and redistribution of those unmodified files inside a separately signed model
   package for a closed-source Android application are permitted; and
4. retaining Apache-2.0 text and applicable notices is sufficient, or identify any additional
   attribution, source-disclosure, trademark, or redistribution requirement.

We are asking specifically because the embedder documentation identifies ImageNet training data,
while the downloadable file does not carry a standalone provenance statement. We will not publish
the Commercial model package until this scope is explicit.

## Acceptance for the Be Your Eye release gate

- The response is attributable to Google AI Edge, MediaPipe, or the model publisher.
- It identifies both exact source URLs/versions or their SHA-256 values.
- It explicitly covers commercial redistribution and the pretrained ImageNet provenance.
- Any additional notice obligation is copied into the package review and third-party notices before
  Commercial signing.

Recommended public route: <https://github.com/google-ai-edge/mediapipe/issues/new/choose>.
