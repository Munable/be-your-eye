# Be Your Eye 开发约定

## 权威与工作树

产品、架构、开发和发布范围分别以 `docs/PRODUCT.md`、`docs/ARCHITECTURE.md`、本文件和 `docs/RELEASE.md` 为准。辅助文档和历史 evidence 不得覆盖这四份权威。

- 修改前确认当前分支、工作树、受影响模块和真实用户路径；保留无关 dirty 文件、设备状态和 `.local` 资料。
- 不 reset、clean、批量整理或顺手纳入无关改动。过时实现直接删除，不添加兼容层、迁移或 fallback。
- 先完成一条最小真实端到端路径，再增加测试或抽象；优先复用仓库现有依赖和工具。

当前主任务是形成无需账号、订阅、维护者配置的 Community 本地工具与可审核公开快照。保留四模块、签名模型供应链、前台相机边界及未完成验收门。首次源码开源已获授权；线上部署、发帖和二进制发行仍按各自范围与验收门执行。当前推进以三条监控路线的创建、启动、读取／识别、记录、返回、停止、删除和失败后重试为先。账号和订阅保留现有实现，本轮收尾不扩展这些系统，也不把商店购买或邮件配置当作核心监控修复的前置条件。先修实际问题并做相称验证，不为“完整”增加兼容层、兜底路径或重复测试工程。

## 实现边界

- `core:domain` 先定义产品模型与 fail-closed 语义；`core:data`、`core:vision` 和 `app` 只能消费这些合同。
- 三种手动创建入口分别持有自己的草稿和页面状态，只复用相机 handoff、持续运行和记录基础设施。助手产生的严格配置提案只能预填并打开对应页面，不能复制第四套创建、保存或启动实现。
- 手持设置预览和固定持续监控必须复用同一个签名 runtime、阈值、画面质量门、预处理和 adapter。预览策略只负责最小处理间隔、`SEVERE` 降速、`CRITICAL` 相机释放与安全恢复、候选即时反馈和 400 ms／两次有效观察的稳定反馈；持续监控策略只负责持久化处理间隔、热调节、`CRITICAL` 安全停止、任务配置的触发持续秒数，以及“出现”条件的 5 秒离开确认和 episode 去重。测试识别不决定配置完整任务的创建资格，不得把这两层实现为两套视觉 pipeline 或持久化产品 mode。
- 模型只能使用发布方提供的成熟预训练权重。仓库不保留第一方训练、微调、训练数据集或模型导出入口。
- 新包必须复用 Manifest 驱动的通用 runtime/preprocess/adapter family；不得按包名、厂商或制品文件名增加运行时分支。
- DeepSeek 多轮配置助手属于当前范围：回复保持简短、结构化；唯一 `propose_monitor_configuration` tool 必须严格解码且无副作用，只打开可确认的预填配置页。助手提案进入三条原设置路线时统一预填开启本机通知，用户确认前仍可关闭；点按语音控件保持文字输入，长按才录制有界 AAC，并经同一鉴权/Pro 边界调用 Qwen ASR，转写后立即删除缓存音频。
- 助手将三路线及完整签名能力清单交给模型，按整段自然对话选择；不得用关键词先排除参考图片或用正则强制补齐默认参数。未说时长时预填 1 秒，接受 1–60 整数秒，保留最新修改；协议/Catalog 校验与用户确认仍是持久化前边界。真实 hosted 回归必须包含特定人物外观、泛猫类别、数字阈值、2 秒及用户纠正旧对话，不能用 FakeModel 的预设结果证明模型理解。
- 视觉描述只通过同一签名 Catalog 的结构化目标、别名／匹配模式和精确 active 包落地。每条 operational capability 必须绑定 recipe、model card 和至少一个 active package；模型包不携带计费字段，Community 下载与运行服从本地资格，连接版及云服务继续服从根级 `ProductAccessState`。尚未完成准入的模型不写入 Catalog；查询没有精确匹配时 fail closed，不加相近类别或通用模型 fallback。配置中的“时间”只复用现有触发持续秒数，不预建排班系统。
- 密码恢复只接受 build 中配置的规范 HTTPS `/auth/callback` App Link；fragment token 不写 Room、日志或持久配置。Play purchase token 只瞬时提交给 Edge 校验，客户端和服务端日志都不得记录原值。
- 保留的连接版首次启动使用统一硬付费门：只有 `ProductAccessState.Granted` 可进入任何产品能力。月付／年付价格和 P3D 试用文案只来自 Play；租约最长 72 小时且不得越过 provider 到期日，客户端到 `refreshAfter` 必须自动重验。`refreshAfter` 是限速重验点，不是第二个到期时间；网络重验失败时按固定间隔重试并继续遵守签名 `expiresAt`，到 `expiresAt` 和 provider 明确失效时 fail closed。FGS 每 5 秒复核硬边界。账号／设备管理、恢复购买、法律页面与本机只读历史不能被付款墙遮断。
- `internal` 的 application ID 带 `.internal`，侧载时不查询或发起正式 Play 购买，也不显示 Play 订阅管理入口、不得把 Play 不可用误报为网络故障。它仍要求登录账号和服务端测试 entitlement；新注册账号只证明 Auth 路线，必须显式加入测试后才能进入产品。测试 entitlement 不能写入 APK、自动发给任意注册用户或冒充 Play 购买。
- 登录弹窗内提交失败时，错误必须显示在弹窗内且允许直接重试；不能只在被遮挡的账号页显示状态。提交时收起键盘，保留失败时的输入。
- 直接启动与相机测试复用同一下载状态界面；`AwaitingDownload` 必须显示可点击的确认和稍后入口，`Downloading` 显示实际字节进度。无模型缓存的真实安装流程与已有缓存路径分别验证。

## 本地验证

Community 的无秘密检查入口为：

```bash
bash tools/ci/run-community.sh
bash tools/release/build-community.sh --unsigned
```

运行环境与独立签名见 `docs/community/BUILD.md`。Community Debug 可运行真实模型；普通 Debug 仍只供 UI 测试。连接版的完整本地入口为：

```bash
bash tools/ci/run-local.sh
```

公共 CI 只运行无秘密的 Community 政策、合同、单测、lint 和编译；只读权限、不使用高权限 PR 事件、不发布。模型/真机/自然场景、签名与云验收分别记录。先运行受影响模块的快速测试，再在候选冻结前运行完整本地 CI。
模型清单在签发后 7 天停止新下载准入；Community 已合法安装的精确制品不因目录过期停止运行。本地模型检查必须在构建和设备测试前报出过期时间。过期时重新签发有新版本与新地址的 Catalog，并更新测试入口和客户端配置；模型及 Manifest 未变化时复用原有精确地址和字节。不得修改旧冻结发布物、延长客户端校验期限或用模拟时间冒充当前可安装性。
已进入 Supabase 远端 history 的 migration 文件保持不可变；合同变化必须使用新的前向 migration，并以 hosted 函数定义、权限和真实客户端读回确认部署。

Hosted FCM 与临时触发图验收分别使用 `tools/ci/run-hosted-cloud.sh` 和
`tools/ci/run-hosted-snapshot-relay.sh`。脚本从 macOS Keychain 的
`be-your-eyes.supabase.secret` 读取 service key，创建已确认的临时账号并写入一条最长一小时的测试 entitlement，结束时删除账号及级联数据；不得为测试关闭生产邮箱确认。该 entitlement 只验证权益后的 hosted 数据面，不代表真实 Play 购买已通过。

验证结果必须按层级分别记录：

1. 静态政策、Schema、签名合同、unit、build、lint 与 SBOM；
2. 精确模型包的合同、激活和最小推理 smoke；
3. 当前 active 包在固定公开外部切片上的签名 runtime 直喂；
4. 代表性外部短片在当前 PJA110 目标物理设备上经过真实 CameraX、签名 runtime、稳定门、事件规则、Room 与 UI 的屏幕回放；
5. API 36 其余真实渲染与 CameraX 功能路径；
6. 当前物理设备上的创建、自然画面手持预览候选、稳定确认、移动丢失与重新获取、固定监控首帧与事件、返回、恢复、停止、删除和异常路径；
7. hosted assistant/cloud、双物理设备，以及 Commercial/商店人工验收。

固定手机对屏幕测试时，使用同一机位回放静态正负画面、目标移动、短暂消失、确认离开后重现，以及数字越界与恢复。先核对相机中的方向和完整范围，再以实际记录数验收；读数手动框必须覆盖停止后重启，确认取景比例和范围保持一致。通知允许与拒绝、返回首页、黑屏、历史图片和删除通过真实界面操作检查。只删除本轮创建的测试任务和素材，保留既有数据。

动效复用 Compose 的有限动画，不增加动效依赖。相机相关导航不叠加旧、新相机页面；不动画相机视口尺寸、读数或格式编辑分支。按压、状态与消息过渡遵从系统 animator duration scale；真机录屏核对实际帧、点击取消、返回、输入焦点以及关闭动画后的可操作性。

数字自动选择需要覆盖中心附近的小数字与外围较大数字、多行同时可读、中心非数字文字、重新找数与手动画框后清除；确认无需画框即可读数，多候选不暂停。

数字设置页先展示实时读数与确认／条件操作，画框教学和黑屏选项排在主操作之后；带单位的读数独占一行，避免把状态说明挤成窄列。真机确认首次读到数字时，下一步按钮无需先滚过教学卡片。

MainActivity 使用 `adjustResize` 提供 IME inset；数字表单按键盘高度扩展覆盖面板，保留原相机尺寸。输入时检查标题、字段及错误说明可见，收起键盘后检查识别框位置未变化。

体验审查先取当前真机截图，再分别从首次理解与返回查看的用户任务检查页面；代理先根据可见界面给出判断，再核对实现和实际操作，不以文档替界面解释。用户类型写成待验证需求假设。界面文案或图标调整完成后，在相同设备与页面状态复查，既有自动化选择器同步修改；代理审查与屏幕回放不记为真人访谈或自然场景验收。

当前监控的记录入口复用已有记录页，先按精确 monitorId 筛选再组合事件；不存在的任务不能退回全部记录。真机检查运行中打开、返回与详情入口，确认监控持续、名称范围正确且未混入其他任务。

运行结果和帧序号、采集及发布时间通过一份进程内快照传递，按活动任务和配置版本校验；重启前的持久化读数不作为首帧之前的实时结果。界面更新时间使用 elapsedRealtime，CameraX 采集时间只用于原时间源的配对，不与另一时间源直接相减。Internal 调试沿用有界诊断，只记录帧序号、处理耗时、时间和结果种类，不记录读数、任务身份或画面。

后一层不能由前一层代替。外部静态图直喂用于区分模型、预处理和 adapter 问题；PJA110 屏幕短片用于验证真实 CameraX 到事件／UI 的产品链路。两者都不能作为自然场景准确率、设备差异、hosted production 或完整产品体验证据。

## 模型质量与设备顺序

质量集合、来源隔离规则和验收阈值以当前签名包的发布方资料和审核记录为准。仓库只提交 suite definition、harness／测试代码和 `evidence/current/` 的紧凑结论；外部素材、prepared bundle、生成短片、逐次 runner、录屏和设备 raw evidence 均留在外置盘的忽略目录。成熟预训练模型使用签名 Manifest 冻结的产品参数；没有明确审核理由不偏离发布方推荐配置。仓库验证接线、合同、许可、设备兼容和基础正负 smoke，不复制训练方的长期 benchmark，也不为追求 evidence 数量反复调参。

`model-tools/v3/evaluation/external-replay-set-v1.json` 是当前不过度扩张的外部回放定义；`tools/vision-eval/` 只负责确定性准备、逐 active 包签名 runtime 直喂、紧凑评分和短片生成。数字读数的 `full_frame` 结果诊断未画框时的产品自动定位路线；它不执行 Commercial 准确率门，但失败也不能解释为产品合同之外。素材与逐次结果必须留在外置盘，参数固定为签名产品配置且不做 sweep。Catalog active 集合、runtime/preprocess/adapter 或相应包字节变化时重跑受影响切片；普通 UI、文案和无关 cloud 变更不重复跑整套。

候选验证顺序固定为：候选字节/签名 → 合同/unit → 最小模型 smoke → 外部签名 runtime 直喂 → PJA110 CameraX 屏幕回放 → API 36 其余功能路径 → 自然画面目标物理设备 → 三种手动路线和助手提案路线的关键生命周期 → hosted production／双机 → Commercial。任一层失败先修复该层，再重跑受影响切片和必要回归。

## 两阶段体验与稳定性验证

1. 合同和 unit 必须证明两阶段激活同一精确签名包、阈值与质量门；预览使用签名最小处理间隔，持续监控使用任务绑定的持久化处理间隔。
2. 当前物理设备以一次可复现的关键路径 smoke 验证配置完整即可启动，以及可选测试中的候选反馈、稳定结果、移动丢失、`unavailable` 和重新获取；只有真实体验出现问题时才增加分段性能采样。
3. 固定机位验证现有出现持续秒数、5 秒离开确认、同一 episode 不重复通知和短暂模糊不关闭 episode。新增 Catalog 包只做基础正负样例和启动 smoke，不为每个模型建立长期重复语料工程。
4. 助手验证文字多轮澄清、精简回复、唯一 tool 严格解码、无副作用配置页 handoff、Catalog 外目标拒绝；语音只做一次有界 AAC→Qwen ASR smoke，覆盖点按不录音、长按权限、松开发送、转写后自动进入对话、取消／失败清理和无 Pro 拒绝。DeepSeek/Qwen hosted 路径未取得真实结果时保持 `open`。
5. 长时功耗、热量或大规模模型准确率测试只在当前变更、真实故障或 Commercial 风险要求它们时执行；不能用历史结果冒充当前结果，也不为关闭表格而制造重复 evidence。

密码恢复用一次真实邮件往返验证 App Link、更新密码和一次性/过期链接；订阅用 Play license tester 覆盖月付、年付、一次性三日试用、返订无试用、恢复、取消、退款、宽限、hold、到期和 RTDN 刷新。Hosted Auth、SMTP、Digital Asset Links、Play 商品和服务账号未配置时保持 `open`，不以 mock 或本地通过冒充。

## Evidence 规则

- `evidence/current/01-foundation.json`～`05-release.json` 是五个当前结果流；它们必须清楚区分 closed、observed 和 open。
- 同一个候选涉及多条结果流时，提交、Catalog、APK 和设备身份必须一致；未在当前候选重跑的旧结果不得称为 current。
- `current` 不嵌套历史候选。已冻结的发布摘要写入 `evidence/releases/<catalog-version>.json`，其余历史由 Git 保存。
- Evidence 只记录精确事实、命令层级和开放门，不保存密钥、账号、设备 serial、用户素材、原始相机帧或重复 raw runner。

当前工程状态只看 `evidence/current/`。文档中的规范、测试计划和命令不等于对应验收已经通过。

### 识别文件下载的交互约束

`ModelPreparationCoordinator` 在签名准入后、模型文件请求前发布 `AwaitingDownload`，等待这次精确包的内存确认。所有手动和助手预填路线复用相机入口现有的加载状态；`ModelPreparationContent` 显示用途、模型名、Manifest 总字节、流量提醒与真实进度。有效已安装包直接复用；详情页和相机设置可见本机文件状态。退出取消协程，下载回调必须传播取消异常，部分文件由原有 Range 和 SHA-256 流程复用。确认后重新检查权益和当前有效期，不持久化跨包下载许可。

本地回归包含 `ModelDownloadRequestTest`（确认前等待、重复点击、离开和不同请求隔离）。可选真机 `ModelDownloadFlowInstrumentedTest` 使用独立缓存目录、公开签名 Catalog 与真实模型，验证确认前无文件请求、取消／Range 续传、离线复用以及 1.5 倍字体渲染；执行参数为 `runLiveModelDownload=true`。测试的下载流限速仅用于观察与取消，不代表生产下载耗时，不清理用户模型或任务。

离开 App 的相机边界：真机覆盖运行中回系统桌面、切换其他 App、锁屏、快速开始后切出；检查 CameraX/相机 AppOps 已释放，返回保持停止。在 App 内首页、记录和黑屏页仍可连续监控。

助手无响应时，用本机 `assistant_request_failed` 的阶段、异常类型／HTTP 状态、耗时和随机 turn ID 对照 Edge 日志；不记录对话、请求体、响应体或凭据。参考图片提案的名称和条件必须带入下一页，恢复已编辑草稿时保留用户修改。

## 官网支付开发与验证

新增 `website` 发布变体继承 release 的 Commercial 模型准入和 arm64 要求，`PLAY_BILLING_ENABLED=false`、`WEBSITE_BILLING_ENABLED=true`。运行 `:app:testFunctionalTestUnitTest :app:compileWebsiteKotlin` 验证现有功能与官网代码；这不产生可销售 APK。`bash tools/release/build-website-apk.sh` 要求干净提交、已冻结 Commercial current/rollback 模型资料与仓库外发布配置，随后执行 lint、构建、真实 APK 验签、绑定 website 运行时依赖的 SBOM 和 OSV 扫描。Gradle 全程使用现有 JDK 25，因为 assembly 也会调用 AGP lint；Android compileOptions／jvmTarget 仍为 Java 17。候选按提交及 versionCode 分目录且不可覆盖。

支付数据库回归：`npm test --prefix supabase/tests`。Stripe 校验：`mise exec deno@2.9.5 -- deno test --allow-env=STRIPE_LIVE_MODE --lock=supabase/functions/website-billing/deno.lock supabase/functions/website-billing/core_test.ts`。部署先迁移数据库，再更新新函数与使用共享权益门的 `monitor-assistant`、`voice-transcription`；`WEBSITE_BILLING_ENABLED` 缺省关闭。

商户配置见 `release/website/README.md`。测试与正式商户配置、Price 和 webhook secret 必须隔离；不复用 ShowforAI 的 API key 或修改其 webhook。签名私钥和恢复副本不进入 Git。

网页 Auth 行为回归运行 `node --test tools/ci/website-auth.test.mjs`；覆盖账号切换、令牌刷新、无效回调和 API 配置边界。网页回调及外部脚本必须配套部署 `_headers`，Auth/账号路径使用 no-store 和精确 Supabase CSP。真实 Auth Admin 生成的测试链接可证明服务端单次消费和改密，但不能替代普通用户 SMTP 收件和邮件打开验收。
