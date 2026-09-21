# PP-OCRv6 Tiny Detection + Medium Recognition：内部复合包逐制品复核

- 包：`numeric_reader_ppocrv6_medium_v1@0.1.0-internal.13`
- 定位器：`PaddlePaddle/PP-OCRv6_tiny_det_onnx@2ba1506c0380b8f0b03dd142459aac66d4421f6c`
- 识别器：`PaddlePaddle/PP-OCRv6_medium_rec_onnx@50c7eacafc52fa7bcf4194e8cd08e46f8558504b`
- 定位 ONNX：`1,780,590` bytes；SHA-256 `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`
- 识别 ONNX：`76,554,979` bytes；SHA-256 `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba`
- 决定：**两项固定制品许可允许内部评估；发布仍需精确包的功能烟测和非占位 Commercial 质量门。**

## 许可与固定来源

两个模型仓库的发布者均为 `PaddlePaddle`，固定 revision 的模型卡均声明
`license=apache-2.0`。定位器 API 返回的精确 revision 为
`2ba1506c0380b8f0b03dd142459aac66d4421f6c`；其固定模型卡为 `16,064` bytes，
SHA-256 `f48628931a5dc994ce5c67e32c94d71b4a7e96b8c5c1b00663284999c3c566bc`。
识别器固定 API 返回同一发布者与 `license=apache-2.0`；固定模型卡为 `16,587`
bytes，SHA-256 `ebce8d28436623ecab4952e24935aed86b3f8ecaf8f8736b92d5544f60fae1e9`。

本包绑定 PaddleOCR 固定 revision
`2661c7c0ef5c613e8f93c6e93b2e052399f0f854` 的 Apache-2.0 全文；许可文本
SHA-256 为 `3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e`。
据此记录 `review_status=approved`、允许商业使用和再分发、且不要求披露 Be Your Eye
第一方源码。该结论只覆盖本文列出的发布者、revision、许可文本和精确制品字节；任一变化
都必须产生新包版本并重新 fail-closed 复核。

固定来源：

- 定位器 API：`https://huggingface.co/api/models/PaddlePaddle/PP-OCRv6_tiny_det_onnx/revision/2ba1506c0380b8f0b03dd142459aac66d4421f6c`
- 定位器模型卡：`https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/blob/2ba1506c0380b8f0b03dd142459aac66d4421f6c/README.md`
- 识别器：`https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/tree/50c7eacafc52fa7bcf4194e8cd08e46f8558504b`
- PaddleOCR：`https://github.com/PaddlePaddle/PaddleOCR/tree/2661c7c0ef5c613e8f93c6e93b2e052399f0f854`

## 运行合同

定位器接收全画面缩放后的 `BGR/NCHW/float32`，使用发布方 `inference.yml` 声明的
ImageNet mean/std 与 DB 参数：pixel threshold `0.2`、box threshold `0.4`、unclip
ratio `1.4`、最多 `3000` 个候选。定位结果只是通用读数行候选；同一包内的识别器以完整
词表执行 `structured_reading_ctc_v2` greedy CTC，并从原始文本提取时间、金额、百分比、
科学计数、带单位或普通十进制读数。无手动画框时每帧全画面定位并按固定坐标锚定，不用
自动框裁剪；只有用户直接拖动的归一化区域限制定位和识别。Android 不按包 ID 或厂商分支
执行，Manifest 的 artifact/input/output/preprocess/adapter 字段是唯一分派依据。模型字节
与许可事实未变，新包版本只绑定新的 Manifest、适配器和产品输入合同。

定位器与识别器均为发布方预构建 ONNX；精确导出工具版本未披露，Manifest 明确记录
`published-prebuilt-export-tool-not-disclosed`，不把 PaddleX 或 Paddle2ONNX 的其他版本
伪装为已验证导出链。

## 质量边界

此前 recognition-only 的来源隔离结果不得改标，也不能证明新增定位器。当前只要求精确包
完成一次加载／推理 sanity check，并在真实产品路线确认全画面发现、固定坐标锚定、可选
手动画框、目标消失后保持 `unavailable` 且不自动换框。Commercial Catalog 还必须给出
非占位的签名质量阈值，并由 Manifest 中的结果满足；这里不把长期参数扫描、独立大语料或
latency／power／thermal 三份专项报告设成无条件前置。本包当前仍是
`internal-evaluation`，`release_quality_ready=false`。
