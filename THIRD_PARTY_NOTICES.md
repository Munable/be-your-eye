# 第三方组件与声明

当前范围：独立开源 Android 应用。发布时从实际 Gradle 与 npm 依赖生成 SBOM，模型遵循各自许可。

## 代码与服务

| 组件 | 用途 | 许可/来源 |
| --- | --- | --- |
| AndroidX / Compose / Navigation / Lifecycle / CameraX / Room / DataStore / WorkManager | 界面、相机、本地数据及有界发送重试 | Apache-2.0，版本固定在 Gradle |
| Google LiteRT / Microsoft ONNX Runtime Android | 本机视觉推理 | Apache-2.0 / MIT，具体组件见 SBOM |
| JSON Canonicalization / Tink / Gson | RFC 8785、Ed25519 与 JSON | Apache-2.0，保留原组件声明 |
| Kotlin / Coroutines / Serialization | Android 运行与数据解析 | Apache-2.0 |
| [ZXing Core 3.5.3](https://github.com/zxing/zxing/tree/zxing-3.5.3) | 生成及读取配对二维码 | Apache-2.0 |
| GitHub Releases | 模型文件分发 | 独立托管服务；服务条款不替代文件许可 |
| [ntfy](https://docs.ntfy.sh/) | 可选匿名加密文字中转，地址可更换 | 使用 HTTPS 协议；本应用未嵌入 ntfy 客户端或服务器代码。公共服务有其额度和条款 |

项目不运营服务器，不提供云 AI、语音、账号、支付或订阅服务。

## 模型包（Community 精确身份见 docs/community/MODELS.md）

| 包 | 用途 | 当前状态 |
|---|---|---|
| MediaPipe MobileNet-V3 Large Image Embedder | 参考图片 similarity | Community 候选复用已审核的精确 Apache-2.0 制品；许可复核见 reference-publisher-license-2026-09-18.md，实物识别仍独立验收 |
| PaddleOCR PP-OCRv6 Tiny Detection + Medium Recognition ONNX + vocabulary | 固定数字读数 | Community 候选复用发布者预构建制品、Apache-2.0 与精确字节已绑定，通用 OCR 真值表现不作为首发声明 |
| TensorFlow EfficientDet-Lite2 COCO detector | 签名 Catalog 中精确绑定的 80 类通用物体目标 | TensorFlow 发布的预训练 TFLite 制品，Apache-2.0；Catalog 绑定发布页、精确字节、SHA-256、运行时和 class map，不覆盖 Catalog 外或专业目标 |

实际包身份和哈希以 `evidence/releases/` 的对应 Catalog 摘要为准。列入本文件不等于获得商业发布资格。

## UI and test assets

- Launcher vectors and geometric/numeric test fixtures are project-authored. The two home illustrations (`home_reference_scene.webp`, `home_reading_scene.webp`) were generated for this project; their original design record identifies the generation and extraction on 2026-08-15. They are decorative, not camera evidence, and are distributed with the first-party source under Apache-2.0 to the extent rights subsist.
- UI symbols use Google Material Icons through the Apache-2.0 AndroidX Compose dependency.
- The former object illustration and apple camera photo have been removed from the public source because their provenance was not recorded. The optional object replay accepts a tester-supplied image.
- PaddleOCR evaluation images retain their exact Wikimedia CC0 sources and transformations in `model-tools/v3/ppocr_official/android-test-assets/temporal-replay-reference.json`. They are test inputs, not training data.
