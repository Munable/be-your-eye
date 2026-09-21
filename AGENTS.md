# Be Your Eye 仓库工作约定

本文件适用于整个单仓；更近的 `AGENTS.md` 只能补充目录内构建细节。

## 当前权威与产品范围

- 权威只有 `docs/PRODUCT.md`、`docs/ARCHITECTURE.md`、`docs/DEVELOPMENT.md`、`docs/RELEASE.md`。
- Production 英文品牌为 `Be Your Eye`，中文展示名为 `帮你盯`；Android
  `applicationId=app.beyoureyes.monitor` 与现有 package 名是技术身份，不随展示名改动。
- Community 无账号、无订阅即可使用本地能力；连接版按现有权益解锁。当前产品保留三个同级手动入口：参考图片、数字读数、文字描述可见目标／现象。文字描述表达要在相机画面中寻找什么；目标、别名、专业现象和兼容模型只能来自同一份签名 Catalog。Catalog 只列出已绑定 active package、可实际配置和运行的模型；未进入 Catalog 的目标在查询时明确没有匹配项，不得用相近类别或通用模型兜底。配置完整即可创建并启动监控；现场确认是可选的「测试识别」，从配置页与详情页随时可进，不构成创建门槛。未手动画框时每个处理帧都从全画面寻找数字行，初次从取景画面中心向外选择最近的可读数字行，自动框只反馈当前锚点；多个数字不阻塞自动读取，用户可以重新找数或直接画框指定，手动框才是严格识别范围。手动框用左上角和右下角两个拖点调整，空白处拖动可重画。没有点击候选、自动框裁剪、单位输入或模型准备页。
- DeepSeek 多轮配置助手属于当前产品：对话必须简短、结构化，信息不足时逐项澄清；信息完整后只能调用 `propose_monitor_configuration` 打开可确认的预填配置页。该 tool 不创建、不保存、不启动监控，也不得绕过用户确认、Catalog 准入或相机设置流程。文字和长按说话都可用；语音只在长按时申请麦克风权限并录制有界 AAC，经鉴权的 Qwen ASR 转写后立即删除缓存。Community 不启用助手、语音或云服务；本地功能与在线资格分离，连接版保持原根级产品权益。
- 参考图片 3–20 张，导入时统一转正、缩放和保存，只拒绝解码失败或重复；界面只显示素材充分度，所有可见图片都参与原型。完整参考配置在首次运行前显示“已就绪／开始监控”，取得真实相机首帧后再进入“曾运行”语义；读数主路径是先取得稳定基准值再设条件，读不到时允许先创建为“待确认基准”，首次稳定读数推送确认后条件才激活。
- 固定机位、持续供电、纵向后摄、同一时间一个活动监控；监控只在 App 可见时运行，切出或锁屏停止并释放相机，返回不自启。App 内导航与黑屏监控可继续；黑屏仅减少屏幕显示耗电。Community 为 API 26+、arm64，当前模型要求 8 GB RAM，不依赖 GMS；实测设备与工程边界分开记录。
- 保留连接版：官网 `website` 渠道通过 Stripe 一次性购买 30 天使用权、不自动续费，确认邮箱后可主动领取一次 3 天试用；Play 渠道保留原订阅。官网来源使用独立账本，并与 Play 共用根级权益。首次打开、退出账号或无有效权益时进入不可关闭的订阅墙；账号、恢复购买、管理订阅、删除账号、法律页面和只读本机历史始终可访问。试用、active、grace 以及取消但尚未到期属于 `Granted`；其他 Play 状态 fail closed。配置中的“时间”只表示现有触发持续秒数，不引入排班、日历或后台定时系统。

## 架构边界

- 保留四个 Gradle 模块；`:app` 使用 Navigation Compose、Lifecycle ViewModel 和简单 `AppContainer`。
- `core:domain` 是唯一产品模型来源；`core:data` 管 Room、私有图片、设置和 Supabase；`core:vision` 管通用 runtime family。
- `MonitoringService` 只管 FGS/CameraX 生命周期与相机所有权；`MonitoringSession` 管帧、Observation、规则和 Event。
- 云端只使用 Supabase、Supabase Edge Functions、R2、FCM、静态隐私页，以及明确的 Alibaba Cloud Model Studio、Stripe 官网付款和 Google Play 订阅接口；仓库不引入独立应用服务器。DeepSeek/Qwen、Stripe 和 Play 服务凭据只存在服务端；助手只接收用户主动提交的文字、必要对话上下文和 Catalog 能力摘要，Qwen 只接收用户本次长按录制的 AAC，两者都不接收相机帧。视觉目标任务保存签名 Catalog 中的稳定 target/capability/package 身份，不新增第二个模型注册中心。同账号设备之间的触发图按需读取复用 Supabase 私有 Realtime Broadcast，不新增媒体存储。
- 不保留旧数据兼容、旧壳、旧导航或 SharedPreferences migration；测试安装可清数据。

## 模型、许可与隐私

- Community 为本地开源工具；第一方源码采用 Apache-2.0，2026-09-21 用户已授权首次开源发布。保留第三方权利、来源和签名，不将第三方模型自动改为第一方许可。
- 每个模型包独立审核；商业 Catalog 对许可字段、授权期限、来源、签名、SHA-256、设备与证据 fail-closed。
- 当前模型策略只接收发布方提供的成熟预训练权重；仓库不保留第一方训练、微调、训练数据集或模型导出入口。开发与独立测试素材只用于候选比较，不更新权重。
- 模型只能按签名 Manifest 的 runtime/preprocess/adapter family 分派；兼容模型不得新增 Android 模型专属 backend。Catalog 的每条 operational capability 都必须绑定精确 recipe、model card 和至少一个 active package；模型包不携带计费档位，Community 下载和运行无需账号；新安装保留元数据新鲜度门，已验证本地制品按真实许可期限、哈希和兼容性运行。连接版保留原权益门。未就绪模型不进入 Catalog，也不保留占位行。
- 无帧、低质量、tensor 或推理异常都输出 `unavailable`，不得解释为 absence 或正常读数。
- 普通相机帧只在内存；参考图片与参考目标首次触发时的一张 JPEG 只在 App 私有目录。触发图是本机 Event 的独立附件，不进入 Event payload、Supabase 数据库、Storage 或 FCM；同账号其他手机只可在线按需请求一张不超过 720 px／120 KiB 的端侧加密临时副本，Supabase Realtime 仅中继请求元数据和密文且不持久化。查看端解密副本只进 App 私有临时缓存，登出、删账号或对应远端 Event 消失时清除；诊断日志不得记录名称、图片或读数。
- 不提交签名私钥、Firebase service account、Supabase service role、令牌、账号数据、设备 serial 或原始相机帧。

## 工作与完成标准

1. 保留无关 dirty worktree，只修改当前任务文件；删除仅限用户明确授权的可重建目录。
2. 先完成一条真实端到端流程，再增加测试或抽象；优先复用现有成熟依赖。
3. 区分 unit/build、模拟器、物理设备、hosted cloud 和人工验收，后一层不能由前一层代替。
4. 证据只更新 `evidence/current/01`～`05` 或一个发布版本摘要；不保存重复 raw runner。
5. 变更必须同步实现、合同、测试、当前文档和相称证据；开放门必须继续写 `open`。
