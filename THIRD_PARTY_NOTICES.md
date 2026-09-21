# 第三方组件与声明

状态：Community 本地候选与保留的连接版依赖清单；正式商业发布必须从实际 Gradle、Deno、npm 锁文件和活动 Model Catalog 重新生成 SBOM 与最终许可文本。

## 代码与服务

| 组件/服务 | 用途 | 当前边界 |
|---|---|---|
| AndroidX / Compose / Navigation / Lifecycle / CameraX / Room / DataStore / WorkManager | Android 产品与本地数据 | 版本由 Gradle catalog 锁定，进入 Android SBOM |
| Google LiteRT / Microsoft ONNX Runtime Android | 通用 runtime family | 仅按签名 Manifest 执行，不含模型专属 backend |
| Java JSON Canonicalization / Tink / Gson | RFC 8785、Ed25519 与严格 JSON | 进入 Android SBOM |
| Supabase-kt / Ktor | 可选 Auth、密码恢复、PostgREST/RPC、RLS 同步与 Edge 调用 | Android 只持公开客户端配置与用户 session |
| Supabase Edge Runtime / supabase-js / google-auth-library | 账号删除、DeepSeek/Qwen 代理、Play entitlement/RTDN 与 FCM push-dispatch | 版本由 Deno lock 固定；服务密钥只在 Function Secrets/Vault |
| Firebase Messaging | 可选 data-only 跨设备提醒 | 消息只含 event_id/cursor_hint；失败不影响本地功能 |
| Cloudflare R2 / Pages | 签名模型包与静态隐私页 | 外部服务条款不替代模型许可 |
| Alibaba Cloud Model Studio / DashScope | `deepseek-v4-flash-0731` 配置助手与 `qwen3-asr-flash-2026-02-10` 语音转写 | 只经鉴权且 Pro gated 的 Edge Function 调用；普通相机帧、参考图和触发图不发送；服务端凭据不进入 APK |
| Google Play Billing 9.1.0 / Android Publisher API / Pub/Sub RTDN | `be_your_eye_pro` 订阅购买、服务端校验与状态刷新 | Play 处理付款；原始 purchase token 只瞬时校验，数据库只存 SHA-256 与最小权益状态 |

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
