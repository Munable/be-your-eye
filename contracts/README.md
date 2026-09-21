# Be Your Eye v3 合同

这些 JSON Schema 是本地任务、Manifest、Catalog、Observation 和 Event 的唯一边界。旧 ModelRoute/ConfigurationDraft 和 WordPiece 合同已删除，不保留兼容层。当前 AI 配置助手使用一个新的、受限的 `propose_monitor_configuration` proposal 边界；它只打开预填配置页，不是模型路由，也不能创建任务或启动监控。

- `runtime_family` 只有 `object_detection_v1`、`similarity_match_v1`、`reading_pipeline_v1`。
- `object_detection_v1` 接受签名 Manifest 声明的 `object_class` 目标绑定，并按包内签名目标覆盖信息过滤输出。用户可以描述可见物体或现象，但 proposal 必须先把描述解析为同一签名 Catalog 中精确覆盖它的 active 包和 target ID；没有精确包时必须拒绝，不能用相似类别或通用模型兜底。
- `similarity_match_v1` 只接受 `reference_images`；`reading_pipeline_v1` 只接受 `none`。
- TaskConfig 的视觉目标使用 `target_definition.mode=object_detection` 与经过 Catalog 校验的目标标识；参考目标使用 `reference_images`，读数使用 `none`。
- Sync Event upsert 使用独立合同并额外要求 `monitoring_device_id`；通用 Event 合同不包含传输层来源设备字段。
- `class_map` 和目标覆盖表拒绝重复 raw ID、target ID、名称/别名冲突、空语言字段和越权通配。别名只用于 Catalog 声明的归一化匹配，不允许助手自行猜测等价目标。
- 所有模型制品由签名 Manifest 固定来源、字节数、SHA-256、许可、runtime、发布方默认参数、输入输出 tensor 和适用设备；未通过许可、完整性、设备兼容或一次加载／推理 sanity check 的包保持 open 或不 active。
- Commercial 准入不无条件要求独立大语料、长期 latency／power／thermal 证据或重复调参。只有当前风险明确要求时才附加并验证专项 evidence；Internal 的开放项必须保持明确，不能伪装为 verified evidence。
- `propose_monitor_configuration` 只允许三条现有路线、目标、触发规则、持续秒数和 Catalog 绑定。这里的时间沿用现有 `duration_seconds`，不增加每日或每周排班；Manifest 默认参数不能由助手覆盖。
- 助手的文字和语音使用同一对话合同。点按只聚焦文字输入；长按才申请麦克风权限并录制最长 30 秒、最多 512 KiB 的 AAC/MPEG-4，经登录和 Pro gated 的 Qwen ASR 转写后立即删除缓存。

校验器只证明合同、签名字段、字节绑定和准入材料满足约束；一次加载／推理 sanity check 不代替真实相机体验、专项风险验证、许可法律判断或完整 Commercial 验收。

## 官网支付合同

`website-billing` 接收鉴权 JSON 命令：`status`、`trial`、`checkout`（客户端 UUID v4 `request_id`）、`sync`（Stripe `session_id`）。不能传入金额、币种、期限或账号来决定发放；账号来自 Supabase 用户令牌。Checkout 只由服务端固定的一次性 Price 创建，订单 ID 与请求幂等键绑定。`stripe-webhook` 只接受已验签的 Stripe 原始请求并通过实时 provider 查询复核。

`product-entitlement` 接收 `{ "action": "status" }`，返回与 Android 现有接口相同的五个字段 `product_id`、`active`、`state`、`expires_at`、`refresh_after`；官网新增有效状态 `WEBSITE_PASS_ACTIVE` 和 `WEBSITE_TRIAL_ACTIVE`，不得把 Stripe 付款写成 Google Play token。`expires_at` 是短期设备租约截止；官网账号页另显示真实 provider 权益期限。
