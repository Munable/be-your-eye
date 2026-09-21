# EfficientDet-Lite2 object_detection_v1 package

Pinned official TensorFlow Lite COCO detector for Be Your Eye's signed common-object capability.
Professional targets require their own exact active package and never route to this general model.

- Model card: https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1
- Artifact: publisher file `1.tflite`, variation version `1`
- Artifact size: `7,557,887` bytes
- Artifact SHA-256: `6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52`
- License: Apache-2.0 (`model-tools/v3/licenses/Apache-2.0.txt`, SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`)
- Tensor contract: uint8 RGB `[1,448,448,3]`; float32 boxes/classes/scores/count with 25 detections
- Embedded COCO label map SHA-256: `f8803ef7900160c629d570848dfda4175e21667bf7b71f73f8ece4938c9f2bf2`
- Runtime score threshold: `0.5`; NMS IoU threshold: `0.5`

The binary stays in the external local artifact cache at
`.local/model-artifacts/efficientdet-lite2/efficientdet-lite2.tflite`; it is not tracked in Git.
Release packaging verifies exact bytes, then runs one focused load/inference and product-route
smoke on PJA110. Lite2 enters the current Catalog only if PJA110 p95 inference is at most 500 ms.
