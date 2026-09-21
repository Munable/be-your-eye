# Google Play Data safety 工作表

状态：`console-entry draft`（核对日期：2026-08-31）。下表已按当前代码、生产数据流和官方服务商条款收敛为 Play Console 可录入答案；尚未上传的精确 Commercial AAB 仍须做最后 SDK 比对和 Console 预览，不把本文档称为已提交声明。

## Console 总体答案

| Play 问题 | 录入答案 | 依据 |
|---|---|---|
| App 是否收集用户数据 | **Yes** | 账号、购买历史、设备标识、同步数据和服务请求会离开设备；AI 文字和语音只在用户主动使用时传输 |
| App 是否共享用户数据 | **No** | Supabase、Cloudflare 和 Alibaba Cloud 按 DPA 作为受指示的 processor/service provider；Firebase/Google Play 用于推送和付款／订阅服务。当前无广告、跨客户建档、出售或其他第三方用途，适用 Play 的 service-provider/payment 例外 |
| 是否全部传输中加密 | **Yes** | 对外请求仅使用 HTTPS/TLS；远程触发图另使用端到端 HPKE（X25519/HKDF-SHA256/AES-256-GCM） |
| 用户能否请求删除 | **Yes** | App 内可删除云端账号；`https://beyoureye.com/privacy/#delete-account` 提供无登录墙的邮件通道 |
| 是否通过独立安全审查 | **No** | 首发不申报未取得的 MASA 或其他独立认证 |

## 数据类型录入表

`共享`一列全部录入 **No**。“必需／可选”按 Play 的数据类型级别填写；例如 FCM token 本身可选，但同一类型中的 App 设备 ID 是产品必需，所以 `Device or other IDs` 整体仍填 **Required**。

| Play 数据类型 | 收集 | 是否临时处理 | 用途（Console 勾选） | 当前实现与保留 |
|---|---|---:|---|---|
| Personal info / Email address | **Required** | No | Account management | Supabase Auth 用于注册、登录、邮箱确认和密码恢复；删除账号时删除 |
| Personal info / User IDs | **Required** | No | App functionality; Account management; Fraud prevention, security, and compliance | 用于账号、权益绑定、设备撤销和同账号同步；删除账号时删除 |
| Financial info / Purchase history | **Required** | No | App functionality; Account management; Fraud prevention, security, and compliance | App 可读取订阅商品／状态，原始 purchase token 只瞬时提交校验；数据库仅存 token SHA-256、商品、状态和实际到期日。App 不访问卡号或付款资料 |
| Location / Approximate location | **Required** | No | App functionality; Fraud prevention, security, and compliance | App 不请求 Android 位置权限，但 Supabase/Cloudflare Edge 网络日志会处理 IP 及由此得到的国家／地区等粗略位置元数据；不用于广告、个性化或用户级位置功能 |
| Device or other IDs | **Required** | No | App functionality; Account management; Fraud prevention, security, and compliance | 包括 App 安装设备 ID、Firebase Installation ID 和用户开启同账号提醒后的 FCM token；退出／撤销会停止使用，删除账号时级联删除应用侧记录 |
| App activity / App interactions | **Required** | No | App functionality | 同步任务修订、结构化事件、获取／展示回执和送达状态；Event payload 从服务端收到起保留 30 天，账号删除时级联删除 |
| App activity / Other user-generated content | **Required** | No | App functionality | 已确认的任务标题、目标／现象描述、阈值与触发配置作为任务摘要同步；不包含参考图或触发图，账号删除时删除 |
| Messages / Other in-app messages | **Optional** | **Yes** | App functionality | 用户主动发给 AI 助手的文字和必要的有限对话上下文只用于当前请求；App／Edge 不建 transcript 表、不记录正文，Alibaba Model Studio 生产 workspace 必须保持 inference logs 关闭 |
| Audio files / Voice or sound recordings | **Optional** | **Yes** | App functionality | 长按才将一次最长 30 秒／512 KiB AAC 交给 Qwen ASR；成功、失败或取消后删除本机缓存，不建云端 audio/transcript 表，生产 inference logs 必须保持关闭 |
| App info and performance / Diagnostics | **Required** | No | Analytics; App functionality; Fraud prevention, security, and compliance | Edge 只记录无内容的请求结果、状态和时延；Alibaba Model Studio 默认 audit log 只保存 request ID、模型、token 用量、时延和状态，不含 Prompt/Response；保留时间按生产服务计划和安全需要限定 |

## 明确不勾选的数据

| Play 数据类型 | Console 答案 | 原因 |
|---|---|---|
| Photos and videos / Photos | **Not collected; Not shared** | 参考图和触发原图只在本机，适用 Play 的 on-device 例外；同账号远程查看使用临时收件人公钥端到端 HPKE，Supabase 只看到不持久化的请求元数据和密文，适用 E2EE 例外 |
| Photos and videos / Videos | **Not collected; Not shared** | 相机帧只在设备内存处理，不持久化、不上传 |
| App info and performance / Crash logs | **Not collected; Not shared** | 精确依赖不含 Firebase Crashlytics；App 私有本地有界日志不上传 |
| App info and performance / Other app performance data | **Not collected; Not shared** | 不含 Firebase Performance Monitoring，不上传帧率、功耗、热量或模型 benchmark |
| Location / Precise location; Contacts; Files and docs; Health; Calendar; Web browsing; Installed apps | **Not collected; Not shared** | 产品不请求、推断或上传这些数据；不请求 Android 精确或粗略位置权限 |

Firebase 精确生产依赖仅为 Cloud Messaging 及其传递依赖 Firebase Installations；不包含 Analytics、Crashlytics 或 Performance，代码也未开启 `setDeliveryMetricsExportToBigQuery(true)`。FCM 会自动处理 App 版本和 Firebase user agent，Installations 会生成 FID；已在 `Device or other IDs` 中合并申报。

账号密码和 Supabase session 不作为业务数据同步；session 以 Android Keystore 保护的 AEAD 密文保存在 `noBackupFilesDir`。密码与恢复 token 只经 TLS 交给 Supabase Auth，不写入 Room、DataStore 或 App 日志。

## 官方决策依据

- [Google Play Data safety 定义与 on-device、E2EE、service-provider、payment 例外](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Firebase Android SDK Data safety 披露](https://firebase.google.com/docs/android/play-data-disclosure)
- [Supabase Data Processing Addendum](https://supabase.com/legal/customer-resources/data-processing-addendum)、[Supabase Logs](https://supabase.com/docs/guides/monitoring-and-debugging/logs) 与 [Cloudflare Customer DPA](https://www.cloudflare.com/cloudflare-customer-dpa/)：Edge 请求元数据包含 IP 和 Cloudflare 粗略地域字段，因此申报 Approximate location
- [Alibaba Cloud DPA](https://www.alibabacloud.com/help/en/legal/latest/fe2cxg) 与 [Model Studio 隐私声明](https://www.alibabacloud.com/help/en/model-studio/privacy-notice)
- [Alibaba Cloud Model Studio monitoring](https://www.alibabacloud.com/help/en/model-studio/model-telemetry/)：默认 audit logs 不含 Prompt/Response；inference logs 需手动开启，生产必须保持关闭

## Play Console 其他声明

1. **Ads**：No。当前无广告 SDK、广告位或跨客户建档。
2. **App access**：产品功能要求登录和有效试用／订阅。上架时填入由 license tester 购买并绑定 Pro 的专用审核账号，不提供管理员后门。
3. **Target audience**：首发不面向儿童，不使用儿童导向素材。
4. **Account deletion URL**：`https://beyoureye.com/privacy/#delete-account`；公开 HTTPS、无登录墙，且可直接联系 `support@beyoureye.com`。
5. **Permissions**：Camera 用于用户主动开始的本地监控；Microphone 只用于长按的本次 AAC；Notifications 用于运行状态和用户开启的事件提醒；Internet 用于账号、权益、模型、同步、AI、语音和恢复。

## 上传前精确检查

- [ ] 从待上传 Commercial AAB 导出依赖，确认只有上表 SDK，且没有 Analytics、Crashlytics、Performance 或其他新数据收集器。
- [ ] 在 Alibaba Model Studio 生产 workspace 确认 **Inference Logs = Off**；若开启，AI 文字和音频不得勾选临时处理，并须同步修改隐私政策。
- [ ] 对精确候选抓包／检查配置，确认相机帧、参考图、触发原图和 Event payload 均不上传媒体，远程图片中继只有 HPKE 密文。
- [ ] `PRIVACY_POLICY_URL` 和 Account deletion URL 在移动网络下公开可达，Console Data safety 预览与隐私政策逐项一致。
- [ ] 若变更云服务商、账号地区、服务条款或 SDK，重做共享例外和数据类型复核；不沿用本草稿。
