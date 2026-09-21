# Be Your Eye 架构

## 模块边界

仓库保留四个 Gradle 模块：

- `core:domain`：`MonitorKind`、`MonitorTarget`、配置提案、规则、Observation、Event 和任务状态的唯一产品模型来源。
- `core:data`：Room、私有图片、设置、任务编解码、账号会话、Supabase 同步和助手网关。
- `core:vision`：签名 Catalog/Manifest、预处理、通用 LiteRT/ONNX runtime family、适配器和模型包下载/哈希校验。
- `app`：Navigation Compose、Lifecycle ViewModel、CameraX 设置流程、前台服务 handoff 和用户页面。

`AppContainer` 是简单组装入口；不增加第二套依赖注入层、模型注册中心、独立应用服务器或模型专属 Android backend。DeepSeek、Qwen ASR、Stripe 和 Google Play 校验都经 Supabase Edge Function 访问，服务端凭据不进入 APK。

## Community 装配边界

`communityDebug` / `communityRelease` 复用四模块，以 `BuildChannel.COMMUNITY` 和独立 package 装配。`localUseAccessDecision` 仅决定本地功能；原 `ProductAccessState` 仍表示真实账号的在线权益。助手和 CloudBootstrap 使用原云资格，本地允许不等于云端获准。Community 的 Supabase/Firebase 配置编译为空，AI gateway 禁用；Billing/FCM 的 SDK 实现与依赖只进入 `src/connected` 变体。无操作的 Community 适配器不创建账号、租约或网络请求。

签名 Community 元数据可以随 APK 分发，仍由同一公钥、严格 decoder 和 Manifest/制品哈希校验。过期目录只可解码为 `installedCommunityOnly`，设置和目标选择必须在不可变模型库中找到对应精确身份并重新获取经哈希/许可检查的 runtime lease；没有该安装记录就不能复用。新安装始终检查目录新鲜度。原安装 gate 保留其真实验证时间、签名与许可依据，不重写时间、不延长授权、不构造新购买。已收到的撤回目录拒绝相应包，离线设备无法保证立即知道远端撤回。

## 领域与传输模型

`MonitorKind` 继续覆盖 `REFERENCE`、`READING` 和视觉目标／现象检测。对应目标保持严格 typed：参考图片、数字读数，以及由签名 Catalog 标识的视觉目标。视觉目标至少保存稳定 target ID、展示标签、operational capability key 和精确 package 选择。

- 本地 `target_definition.mode` 为 `reference_images`、`none` 或 `object_detection`。
- 视觉目标保存稳定 Catalog 身份和完整 ROI。用户原始描述只用于助手澄清和 Catalog 匹配；持久化任务与 runtime 只接收已验证的结构化目标和模型选择。
- Supabase 使用与本地一致的 `visual_target + target_definition.mode=object_detection` 结构化有限类别目标；签名 route/package 绑定随 TaskConfig 同步，不新增第二套任务类型或模型注册中心。

`core:domain` 在写入、恢复和启动前验证这些类型。未知字段、未知 target ID、非法 ROI、不支持的 mode，或 target/capability/package 不一致均 fail closed。

## 助手与配置提案

- 助手是有界的多轮配置客户端：文字输入和有限对话上下文经鉴权的 Supabase Edge Function 发送到 Alibaba Cloud Model Studio 北京 workspace 的 `deepseek-v4-flash-0731`；相机帧、参考图片、事件附件和本地记录不进入助手请求。
- 服务端系统提示词要求回答简短、结构化，缺少关键信息时逐项澄清。唯一 tool 为 `propose_monitor_configuration`，输出必须按严格 schema 解码；未知字段、未知枚举和 Catalog 外身份均拒绝。
- tool 结果只导航到可确认的预填配置页，没有创建、保存、下载、启动或通知副作用。助手路线在对应手动草稿中预填开启本机通知，但只有用户继续原设置流程并最终确认后才会持久化；需要改动触发条件时返回对话重新描述，最终创建仍复用三条手动路线的素材、读数基准、Catalog 准入和配置确认。
- 配置提案中的“时间”只映射 1–60 整数触发持续秒数，缺省预填 1 秒。三种路线及全部签名目标一起交给模型理解；不以关键词过滤路线、不用正则拆解用户意图、不截断自然回复。协议严格校验唯一提案与 Catalog 身份；若 provider 同时附带说明，只向 App 返回校验后的提案，不转发附带的执行宣称。提案没有执行副作用。
- 点按语音控件只聚焦文字输入；长按时才请求 `RECORD_AUDIO`，在 App cache 录制最长 30 秒、最多 512 KiB 的 16 kHz/64 kbps AAC/MPEG-4。松开后经鉴权 Edge Function 发送到同一北京 workspace 的 `qwen3-asr-flash-2026-02-10`，转写结果立即作为本轮用户消息进入同一助手对话；录音在成功、失败或取消后删除。
- 助手和语音函数都先验证 Supabase 用户与有效 Pro 权益；无账号或无权益时 fail closed。Edge 日志只保留请求 ID、结果、音频时长／字节数和时延等无内容元数据，不记录对话、转写或音频正文。

## 模型与 Catalog

模型包只按签名 Manifest 的 `runtime_family`、`preprocess_id`、`adapter_contract`、制品角色和输入输出 tensor 合同分派：

- `similarity_match_v1` 只处理参考图片目标；
- `reading_pipeline_v1` 只处理结构化读数；
- `object_detection_v1` 处理 Catalog 声明兼容的结构化视觉目标。只有所选成熟模型确实需要目标文本 tensor 时，才增加一个 Manifest 驱动的通用 target-text contract/provider；不得按模型包增加专属 backend。

Catalog 是唯一模型清单，固定 target ID、别名／匹配模式、适用场景、限制、capability、精确 package、包状态、来源、许可、设备范围和 Manifest 身份。每条 operational capability 都必须绑定一个现有 runtime recipe、model card 和至少一个 active package；包内不保存计费档位、伪准确率或发布质量状态，本地资格和云资格在 Catalog 之外分别验证。未完成许可、runtime、target 或发布准入的模型不进入 Catalog。客户端再验证签名、SHA-256、大小、target/capability/package 关系和设备兼容性。没有精确 active 兼容模型时 fail closed，不得选择相近类别或任意通用包兜底。无帧、低质量、tensor、预处理或适配器异常统一输出 `unavailable`，不得解释为 absence 或正常读数。

## 相机与运行生命周期

- 三种设置页只共享 CameraX 所有权和 handoff 合同，不共享可变创建草稿。
- 无预览启动与设置预览使用同一物理方向映射。三条路线的设置预览、首次直接启动、详情重启和持续监控共享同一可见取景比例，并从实际窗口计算 CameraX 视口；避免预览显示的范围与分析裁剪不同，也避免重启后已保存的数字手动范围错位。不得以固定 1080×1920 替代当前窗口几何。
- 参考图片和文字描述目标／现象的设置预览，与持续监控共享签名 Manifest 驱动的 runtime、发布方默认参数、画面质量门、预处理、adapter 和 typed `Observation`。两种阶段只在 Observation 之上的执行策略与消费策略分离，不新增持久化 mode、模型副本、参数分支或 backend。
- `SetupCameraSession` 承载前台交互预览：CameraX 丢弃旧帧，runtime 使用签名范围内的最小处理间隔；`SEVERE` 时外层帧门在签名范围内降速，`CRITICAL` 及以上清除旧确认并释放 CameraX，回到 `MODERATE` 或更低后重开 CameraX 和新的检查 coordinator。首个有效 present Observation 立即成为候选反馈；累计至少 400 ms 且至少两次有效观察后才成为当前稳定结果。稳定后继续消费帧，absence 撤销当前命中显示，`unavailable` 暂停证据且不表示 absence。参考图片和文字目标的稳定结果只用于可选测试反馈，不决定创建或启动资格。
- 用户确认完整配置后，应用原子写入任务并启动现有前台服务；已打开测试相机时复用原有 handoff。写入失败不得消费草稿或继续启动。读数设置分别跟踪是否已保存和基准是否已确认；待确认任务的启动重试复用原任务，确认基准时更新任务及绑定的运行配置版本。
- 运行中的待确认任务从详情进入基准确认时，先请求停止服务。现有相机路由等到服务释放相机并报告 `STOPPED` 后才装配设置页，复用同一任务的读数确认与保存流程。
- 自动读数初次按数字行中心到可见帧中心的像素距离排序，在现有候选上限内由近到远识别，取首个有效值；其他数字不构成暂停条件。随后仍全画面定位并匹配已选锚点，缺失不切换邻近数字。设置页“重新找数”清除 runtime 锚点及旧基准，沿用现有识别会话；手动范围仍直接进入框内识别。
- 读数设置每次创建或重开识别器时都同步当前手动范围；已保存的框同时恢复到预览和识别器，不能只恢复框的显示。
- 校正小数格式时由现有读数预览协调器固定选中的稳定样本并暂停推理，CameraX 取景继续；确认沿用格式校验，取消恢复扫描。范围、任务或会话变化后，旧样本不能提交。
- `MainActivity.onStop`（非配置重建）和服务 `onTaskRemoved` 结束监控；停止先解除 CameraX，再收尾事件。离开 App 的停止原因保留到首页，返回不重新装配相机预览或重启服务。App 内导航和可见的黑屏页继续运行。
- `MonitoringService` 只管理 FGS、CameraX 生命周期和相机所有权；`MonitoringSession` 管帧、Observation、规则和 Event。持续监控使用绑定精确任务、模型包与设备的持久化处理间隔，并在签名范围内应用热策略；`CRITICAL` 及以上由 Session 先产生热暂停 `unavailable`，Service 随后安全停止本次监控、关闭 episode 并释放 CameraX，不增加系统强停后的自启。领域规则执行任务配置的触发持续秒数。“出现”保留 5 秒离开确认和 episode 去重，“持续可见／消失”记录独立事实并在相反状态恢复后重新等待。
- 同一时间只有一个活动监控。停止、进程死亡、权限拒绝、模型损坏和恢复路径都必须释放相机与运行时租约。

## 保留连接版的账号、权益与 App Link

- 根级 `ProductAccessState = Initializing | SignedOut | Locked(reason) | Granted(EntitlementLease)` 是连接版产品能力的入口门。导航、FGS、模型下载／运行、AI、语音和 cloud activation 都消费同一状态；FGS 每 5 秒复核，租约失效时关闭 session、episode、CameraX 和服务。Supabase Auth 管登录和一次性密码恢复；恢复只接受与 build 配置完全一致的 HTTPS `/auth/callback`，严格解析 `type=recovery` 的 fragment，并把 token 仅用于导入临时恢复 session 和更新密码。
- Android 通过 Google Play Billing 查询唯一订阅商品 `be_your_eye_pro` 的 `monthly-auto` 与 `annual-auto` base plan，年付默认选择；各自只接受精确 `trial-3d` P3D offer。价格、币种、offer eligibility 和购买 UI 来自 Play；购买绑定 SHA-256 处理后的 Supabase account ID。
- `play-entitlement` 使用 Android Publisher API 校验 package、product、账号绑定、订阅状态和到期时间，并在需要时服务端 acknowledge。数据库只保存 purchase token SHA-256、provider 状态和实际到期日，不保存原始 purchase token或第二个持久化访问时钟；响应动态签发最长 72 小时且不越过到期日的租约。Android 用 Keystore AES-GCM 缓存账号绑定租约，并在 `refreshAfter` 自动重新核验；该字段只安排软刷新，失败后限速重试，只有签名 `expiresAt` 或明确失效的 provider 状态终止离线租约。退出／删号立即清除。RTDN 经带 OIDC 的 Google Pub/Sub push 刷新 provider 状态。
- 公共数据策略与 security-definer 客户端 RPC 都核验同一账号当前 provider 状态和到期日。任务、事件、回执、sync cursor、Realtime 临时图片、FCM 注册和派发无权益时 fail closed；设备列表、设备撤销、push token 删除和账号删除保持开放。
- App Link、Hosted Auth redirect allowlist、SMTP、Play 商品／权限、RTDN 与真实购买恢复都属于 hosted/商店配置门，不能由本地 unit 或 build 代替。

## 本地数据与云端边界

- 普通相机帧只在内存；参考素材和触发原图只在来源设备 App 私有目录。
- 云端设备 ID 在“本机安装 + 账号”范围内稳定，同一安装切换账号时不得复用设备 ID；返回原账号继续使用该账号原有 ID，删除账号时同时删除本机映射。这样设备、任务、FCM 和临时图片始终保持同账号外键边界。
- 云端边界为 Supabase（Auth、数据库、Realtime 与 Edge Functions）、Cloudflare R2/Pages、FCM、Alibaba Cloud Model Studio，Stripe Checkout/Webhooks，以及订阅所需的 Google Play Developer API／Pub/Sub。Supabase 持久化账号、任务摘要、事件事实、投递状态和最小权益状态，不保存媒体；助手和语音不新增云端 transcript／audio 数据表。
- 同账号触发图预览复用 Supabase 私有 Realtime Broadcast：请求元数据和端侧密文可中继但不持久化；查看端只写 App 私有临时缓存，并在登出、删除账号或远端 Event 消失时清除。
- FCM 只携带事件游标信息；诊断日志不得记录名称、图片、原始类别输入或读数正文。

## 排除项

当前运行时不包含有副作用的 Agent tool、独立模型路由服务、第二模型注册中心、排班系统或训练／微调／模型导出入口。配置助手只有严格的 `propose_monitor_configuration` 提案边界；专业目标能力必须逐项取得同一签名 Catalog 的 active 模型准入，不能借用其他目标的结论。

当前候选、设备、hosted cloud 和发布状态只看 `evidence/current/`；本文件不记录一次性产物或实验结论。

## 官网支付实现

`website-billing` 仅允许规范官网 Origin，逐请求校验 Supabase 用户及邮箱确认。服务端配置固定 Stripe 商户、live/test 模式和 Price；Price 必须为单次付款且携带 `product=be_your_eye_pro`、`access_days=30`。客户端不能指定金额、期限或收款商户。每账号每小时最多创建 5 个结账订单；同一请求 ID 与 Stripe 幂等键重试同一个 Checkout。

`WEBSITE_BILLING_ENABLED=false` 暂停新订单和领取试用；已登录用户仍能读取根级权益，响应 `billing_enabled=false`，且该路径不依赖 Stripe 可用性。既有付款的 `sync` 和签名 webhook 继续核对。网页按此状态关闭购买入口，不将暂停销售误报成网络故障。

`stripe-webhook` 在原始有界请求体上验证专属签名与 300 秒时间容差，再向 Stripe 读取当前 Checkout/PaymentIntent/Charge。`website_orders` 是 service-role-only 支付记录，包含最小 Stripe 对象 ID、价格、金额、币种及使用区间，独立于 Google Play token 表。数据库事务串行化同账号发放；paid 重放无副作用，revoked 终态阻止旧通知重新发放。延迟到账、全额退款和争议均通过 webhook 处理。返回页可以鉴权后补充核验，但不得认领其他账号订单。删账号后订单去除账号关联，用于后续退款和财务核对；试用记录级联删除。

`product-entitlement` 将有效官网订单、一次性试用或既有 Play 权益转为同一客户端租约响应。RLS、AI、语音与 FCM 共用 `beyoureyes_account_has_active_entitlement`，不会只解锁界面而漏掉服务端。官网租约最长 1 小时、每 5 分钟刷新；旧 Play 接口与 72 小时策略不变。网页会话仅保存于 sessionStorage，不将登录令牌带入 Stripe URL。网站只接收公开 Supabase 地址与 publishable key，Stripe 私钥只在 Edge Secrets。

网页和 Android 共用规范 `/auth/callback`。浏览器回落先清除 URL fragment，再向 Auth 核验用户；确认邮件可继续网页账号，恢复邮件显示新密码表单。恢复会话只保留于页面内存，完成改密后登出；缺失、已用、过期或无法核验的链接不进入改密界面。跨账号／退出登录清除旧 Checkout 请求标识。
