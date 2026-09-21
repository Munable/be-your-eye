# Be Your Eye 发布门

## Community Preview 首发门

主推荐制品为独立包名 Community 0.x Preview；发布不以 Play 或官网付费闭环为前提。第一方源码以 Apache-2.0 开源；第三方许可和品牌归属不被替换。源码公开不代表 APK 的实物、持续运行或真机验收已通过。旧私有仓库不改可见性、不删历史；从已提交快照导出干净候选并审核内容和历史扫描结果。新公共仓库接管后仅保留一个开发源。

本地候选至少验证：C01 无账号安装与手动创建；C02 下载确认/取消/恢复/损坏拒绝；C03 模型取得后断网与冷启动；C04 旧云租约/目录到期仍可复用，真实授权期限继续拒绝；C05 数字实物阈值与非触发样本；C06 不确定输出 unavailable；C07 前台、黑屏、切出与锁屏边界；C08 相称持续运行；C09 无不需要的 Billing/FCM/云连接；C10 无秘密干净构建与运行；C11 同签名升级保留资料；C12 源码/模型/APK/签名来源绑定。每项按实际测试层级记录，未测为 open。

发布物包含签名 APK、校验、证书指纹、模型来源和通知、SBOM、精确源码及限制。签名密钥从仓库外读取；`--unsigned` 无需秘密，`--signed` 缺材料必须失败。无许可证/主能力/分发安全证明时，不以 Preview 名称绕过门。

自然实物、1–2 小时持续运行和真人独立使用必须由真实证据证明；构建、单测和屏幕回放不能代替。所有待定对外动作汇总到 `release/community/RELEASE_CHECKLIST.md`，不反复申请同批授权；代码、模型、网站和宣传必须对应同一候选。有限远程试验只在出现真实离场需求后启动，不阻塞本地首发，也不能用本机提醒否定远程价值。

## 保留连接版的渠道边界

- 普通 `debug` 只供 UI；功能测试和 `communityDebug` 可验证真实模型，但不作正式发行签名。
- `website` 是官网商业分发渠道：生产 package、自持长期 app signing key、Stripe 单次 30 天使用权；模型、设备与隐私门仍按 Commercial 执行，渠道上线不等待 Play 上架。
- `internal` 是绑定签名 Internal Catalog 的工程候选，可用于当前能力验证，但不等于 Beta、Commercial 或 Google Play 就绪。
- `internal` 为 `.internal` 侧载包，明确关闭 Play Billing，只接受显式加入的服务端测试 entitlement；界面必须说明这是测试权限而不是网络故障、免费试用或真实购买。新注册账号不会自动取得该权限。
- 大陆无代理体验分别验收 Supabase 登录／权益／AI、R2 模型下载和 Google 服务；单一 Wi-Fi 实测不代表所有运营商。手机通过 Supabase Edge 调用北京百炼，不能只凭百炼可达就断言手机端整条链路可用。Play 购买及 FCM 远程通知必须单独验证，不能由本机监控成功代替。
- Commercial 候选必须由同一干净提交重建、签名并冻结官网 APK 或 Play AAB、当前/回滚 Catalog、Manifest、模型制品、SBOM 和发布摘要。

保留连接版产品集合包括 DeepSeek 多轮配置助手、长按 AAC→Qwen ASR 语音输入，以及参考图片、数字读数、文字描述可见目标／现象三条手动路线。助手唯一 tool 为无副作用的 `propose_monitor_configuration`。首次打开即进入不可关闭的订阅墙；只有登录账号的有效试用或付费权益可解锁全部产品能力。视觉能力只能选择同一签名 Catalog 中精确 active 的兼容模型；Catalog 只列出已可执行条目，没有匹配项时不得兜底。配置时间只表示现有触发持续秒数，不包含排班。

## 阶段性收官的状态判断

一个 Internal 阶段可以在“已交付、边界清楚、剩余问题已列明”时收官，不得因此改称发布就绪。若签名 Catalog 超过新鲜度窗口，当前阶段应标记为已关闭但候选已过期；不能修改旧 Catalog 的签发时间或放宽客户端校验来维持可用性。下一阶段必须用新的版本化地址和同一候选身份重新验证，之前的设备、hosted 或人工结果只能按原范围引用。

本轮重新开放的 Internal 候选为 `2026.09.15.1`：版本化签名 Catalog 已在线返回
`HTTP 200`，新鲜期至 `2026-09-22T06:00:19Z`；对应 APK 已在 PJA110 真机完成安装、冷启动、
测试账号权益登录、模型下载校验、后置相机启动、待确认基准监控运行和停止后的相机释放。该轮只
恢复了一个可复测的 Internal 候选，不关闭自然场景准确率、三路线完整相机验收、长时间稳定性、
真实 Play 购买或生产发布门。

当前阶段的逐流结论和开放门见 [evidence/current/05-release.json](../evidence/current/05-release.json)；
本轮不可变候选身份与阶段范围见 [evidence/releases/2026.09.15.1.json](../evidence/releases/2026.09.15.1.json)，
上一轮已过期候选见 [evidence/releases/2026.09.07.1.json](../evidence/releases/2026.09.07.1.json)。

## 候选身份门

一次候选验收必须绑定同一组身份：

- 完整 Git commit 与干净工作树状态；
- application ID、版本、ABI、APK/AAB 字节和 SHA-256；
- Catalog 版本、URL、签名和 SHA-256；
- 每个 active Manifest、模型及 sidecar 的来源、大小、SHA-256 和签名；
- 设备型号/API、测试层级和结果时间。

任何运行时代码、资源、Catalog、Manifest 或模型字节变化都会形成新候选。旧候选的设备、hosted 或人工结果不能自动沿用。

## Internal 验收门

Internal 候选至少需要：

1. 本地政策、合同、unit、build、lint、SBOM 和密钥卫生检查通过；
2. 三种能力在签名包上的合同、最小推理 smoke 与目标设备激活通过；当前 active 包完成固定公开外部切片的签名 runtime 直喂，代表性外部短片完成相称的 CameraX／产品规则屏幕回放；成熟预训练模型使用签名产品参数，不复制训练方的长期 benchmark，也不以该回放冒充自然场景准确率；
3. 当前 APK 完成三种能力的真实相机关键路径；全新安装且模型无缓存时，「保存并开始监控」和「测试识别」都必须展示下载用途、大小与确认／取消入口，并在确认后完成下载和启动，不能只验证已有缓存；参考图片与文字描述目标／现象必须覆盖配置完整直接启动，以及可选测试中的候选反馈、稳定反馈、目标丢失、`unavailable` 和重新获取；数字读数覆盖现场基准与待确认基准、保存后的启动重试和确认；
4. 手持预览必须覆盖 `SEVERE` 降速、`CRITICAL` 清除旧确认／释放相机和安全等级恢复后的重绑；固定持续监控必须覆盖持久化处理间隔、热策略、`CRITICAL` 热暂停 `unavailable` 与安全停止、配置的触发持续秒数、“出现”条件的 5 秒离开确认和 episode 去重，以及“持续可见／消失”的独立事实、权限、保存、首帧、事件、App 内返回和黑屏继续、切出／锁屏释放相机、返回不自启、停止、删除和失败恢复；无权益时产品能力全部锁定、活动 FGS 最迟 5 秒内停止、本机历史只读且账号／设备管理仍可达；
5. 助手完成三路线自然意图选择、默认 1 秒与任意 1–60 整数秒、用户纠正对话、精简回复、唯一 tool 严格解码、无副作用配置页 handoff、Catalog 外目标拒绝；语音完成点按不录音、长按权限、AAC 大小/时长边界、Qwen 转写后自动进入对话、缓存清理和无 Pro 拒绝。未取得的 DeepSeek/Qwen hosted、设备、cloud 或人工结果继续明确标为 `open`。

APK 构建、Catalog validator、模拟器或屏幕回放不能替代自然画面真机和人工产品验收。

## Commercial 与商店门

进入 Commercial/Beta/Google Play 前还必须关闭：

- 每个活动模型包的许可、来源、再分发、训练数据风险和目标法域人工审核；
- 三条手动路线和助手配置路线在当前物理设备上的关键体验、失败恢复和隐私边界；只有当前风险、真实故障或商店审核要求时，才增加专项性能、长时功耗／热量或更大语料验证；
- 每个活动模型包的 target/capability/package 精确绑定、基础正负 smoke、无兼容模型 fail-closed，以及签名 Manifest 参数未被客户端静默改写；偏离发布方推荐配置必须有明确审核记录；
- 根级产品权益在下载、缓存复用和 runtime 启动处都正确执行；Catalog 不携带模型级计费档位，每条 operational capability 都必须绑定可执行 active package，清单中不得出现未就绪占位行；
- 当前和回滚 Catalog 的在线下载、缓存、离线复用、损坏恢复及远端删除后的离线重建；
- 双物理设备同步、通知、端侧加密触发图大小/清理和账号删除人工验收；
- 精确 Commercial APK／AAB 的签名、漏洞扫描、SBOM、隐私政策、发布主体、支持与安全联系信息；Play 渠道另需 Data safety 和商店素材。

## Hosted Auth、AI 与 Play 配置门

Auth 和 AI 在生产项目及精确 Commercial APK／AAB 上验证；Play 专属项只对 Play 渠道适用。仓库模板或本地测试通过不表示已完成：

1. 在 Supabase Auth redirect allowlist 加入与 APK／AAB `AUTH_REDIRECT_URL` 完全一致的规范 HTTPS `/auth/callback`；配置已验证发件域名、发件人、生产 SMTP、重置邮件模板和限流，并完成真实邮件的一次性／过期链接与改密往返。
2. 将 `release/google-play/assetlinks.json.template` 的 `PLAY_APP_SIGNING_SHA256` 替换为 Play Console **App signing key certificate** 的 SHA-256（不是 upload key），部署到同一 host 的 `/.well-known/assetlinks.json`，再用已上传 track 的 AAB 验证 Android App Link。
3. 仅在 Supabase Function Secrets 配置 `DASHSCOPE_API_KEY` 和北京 workspace 的 `DASHSCOPE_BASE_URL`；部署并验证 `monitor-assistant` 与 `voice-transcription` 的登录、Pro、超时、无内容日志和普通相机帧不上传边界。
4. 在 Play Console 建立唯一自动续订商品 `be_your_eye_pro`，包含 `monthly-auto`（目标价 $5.99/月）和 `annual-auto`（目标价 $49.99/年）两个 base plan，并为各自建立一次性 `trial-3d` P3D 新用户 offer；给校验服务账号最小 Android Publisher 权限并配置 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`。完成真实月／年购买、试用、返订无试用、恢复、acknowledge、续订、宽限、hold、取消、退款和到期。
5. 建立 Google Pub/Sub RTDN topic 与带 OIDC 的 push subscription，受众设为 `https://PROJECT.supabase.co/functions/v1/play-rtdn`，并配置 `GOOGLE_PLAY_RTDN_AUDIENCE`、`GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL`；将 topic 连接到 Play RTDN 后验证状态刷新。原始 purchase token 不得进入数据库、日志或仓库。

商店截图和说明只能描述已由同一 Commercial 候选证明的能力，不得把 Internal、历史截图或未来路线写成正式功能。

unit、回放、build 或模拟器结果不能关闭自然画面真机、hosted production 或人工产品体验门。专项长时、功耗、热量与大规模准确率门只有被本候选风险评估明确要求后才成为发布阻塞项；未要求时不得为了 evidence 数量自行扩张。

## 状态来源

`evidence/current/05-release.json` 是当前发布结论；`01`～`04` 提供其实现、参考图片、数字读数和 cloud 依据。只有 `release_quality_ready=true` 且相称的人工/设备门已关闭时，才可以使用“发布就绪”措辞。

本文件不保存候选哈希、一次性性能数字、提交叙事或上传状态；正式冻结身份写入 `evidence/releases/`。

## 官网商业分发门

官网路线与 Play 上架分别验收。官网上线要求独立 Stripe 商户在同一香港主体下完成激活、专属凭据与 webhook、批准的价格和实际支付方式；验证全新普通账号注册／确认／试用、真实付款到账自动解锁、重复 webhook 不多发、退款收回、到期、账号隔离与删号。Stripe 测试模式与数据库模拟不等同真实收款。

官网 APK 用自持 app signing key，完成首次安装和同 key 覆盖升级，并部署匹配的 Digital Asset Links。签名密钥已生成仅表示签名材料存在，不能关闭实际 APK 签名、更新或商业模型门。下载页面只能指向通过验收的精确 APK、版本和 SHA-256；不发布 Internal 调试包作为付费正式包。

当前实施与尚未关闭的商户、Commercial Catalog 和真实交易门记录在 `evidence/current/05-release.json`。任何未完成门继续标记 open。
