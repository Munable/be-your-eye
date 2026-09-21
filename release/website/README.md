# 官网渠道的付款与签名

官网静态页面仍在 `release/google-play/privacy/`；新增 `/account/` 与 App 共用 Supabase 账号。
当前实现是每账号一次的 3 天试用、Stripe Checkout 单次购买 30 天使用权，不自动续费。
本轮沿用已配置的 HK$48／30 天作为首轮验证价格，代码不写死价格。
正式 Price 已连接后端配置，但尚未公开收款。

## Stripe 商户配置

ShowforAI 的商户不改动。Be Your Eye 使用同一香港公司 Marine Mystique Solutions Limited
下的独立 Stripe account；可复用现有登录及合法的公司／结算资料。
用户已提交创建独立账户；2026-09-18 已在管理平台核对新账户。
用户已完成最终激活提交；2026-09-18 在真实账户模式核对支付和提现均为「已启用」，
账户状态无待办。这只证明商户可接入，不代表真实付款验收已完成。

2026-09-18 按用户要求重新核对隔离方式：在 Stripe 账户切换器中选择
「创建账户 → 创建单独的账户」。该选项明确说明不与 ShowforAI 共享数据、团队成员和报告，
不选择组织共享。正式商户 ID 为 `acct_1UGyQADH5fbMYDjd`，独立沙盒 ID 为
`acct_1UGyQLDVzzYaphtQ`；两者均不是 ShowforAI 的账户。
后续商品、订单、API key、webhook 与账单展示都归属新账户；香港公司资料及登录可复用。

新账户基础引导已填写官网及下述业务介绍，选择在线收款、按需使用产品和 Stripe Checkout；
未选择 Managed Payments、额外账单或 Stripe Tax 开通。沙盒中已创建并回读：

| 测试配置 | 当前值 |
| --- | --- |
| Product | `prod_VHXbwhohmJUphZ` / Be Your Eye Pro — 30 days |
| Price | `price_1UGyWhDVzzYaphtQsV7U32mq` |
| 金额与周期 | HKD 48.00，一次性 |
| Product 与 Price metadata | `product=be_your_eye_pro`、`access_days=30` |

沙盒不连接生产 Supabase，也不代表已验证付款或发放权益。
激活后仅复制上述商品到独立正式账户，已在正式模式回读：

| 正式配置 | 当前值 |
| --- | --- |
| Product | `prod_VHXbwhohmJUphZ` / Be Your Eye Pro — 30 days |
| Price | `price_1UH079DH5fbMYDjdyeQWUuSx` |
| 金额与周期 | HKD 48.00，一次性、active、默认 Price |
| Product 与 Price metadata | `product=be_your_eye_pro`、`access_days=30` |

Stripe 复制后保留 Product ID，正式 Price ID 与沙盒不同；判断身份仍须同时核对账户和模式。
用户已明确允许复用现有公司、负责人验证资料和结算账户；激活摘要已显示这些资料。
Stripe 将法律实体标记为共享，修改法律实体会影响使用该实体的账户，因此此次不改法定主体资料。
仅在 Be Your Eye 账户保存官网 `https://beyoureye.com`、本项目软件服务介绍、
账单描述符 `BE YOUR EYE` 和简短描述符 `BEYOUREYE`。
随后只读核对 ShowforAI：官网仍为 `showforai.com`、公开商家名称仍为 `showforai`，
账单描述符仍为 `SHOWFORAI SUBSCRIPTION`。

激活摘要已确认两步验证开启，使用已包含的 Radar Lite；自动税务计算与 Climate 捐款关闭。
最终「同意并提交」已由用户本人完成。正式账户的支持邮箱 `support@beyoureye.com`
和支持网址 `https://beyoureye.com/` 已保存并回读。
银行卡、Apple Pay 和 Link 已启用；Alipay、WeChat Pay 的开通申请已提交，均显示「待批准」，
尚未验证真实收银台是否出现或支付成功。

经用户临执行授权，已创建名为 `Be Your Eye Website Billing Live` 的受限 key：Checkout Sessions 写入，
Accounts、Prices、Payment Intents、Charges and Refunds 只读，共 5 项权限；不授予退款、
转账或提现写权限。正式 webhook `we_1UH0PnDH5fbMYDjdmW37djvL` 已创建并显示「使用中」，
只监听下述 5 个事件，目标为
`https://tgrqcibikdcradotgqbq.supabase.co/functions/v1/stripe-webhook`。
事件来源仅为本账户，快照 API 版本为 `2026-08-26.dahlia`。
两把密钥均已保存到本项目 Supabase Function Secrets，并核对配置名；
商户 ID、正式 Price ID、正式模式、官网 Origin 和关闭收款开关的配置摘要全部匹配。
Stripe 密钥没有本地文件、聊天或 Git 副本，浏览器临时变量和一次性密钥展示已清理。

2026-09-18 云端配置验证通过：复用实际服务端校验读取正式账户和 Price，
Payment Intents／Charges 只读及 Checkout Sessions 创建／读取权限可用。
仅创建一个未公开、未付款的配置检查 Checkout Session，随后确认其已失效且仍未付款，
没有生成本项目订单或发放权益。此会话返回的付款类型为 `card`、`link`；钱包显示仍需实际设备验收。
已签名的无业务动作探针返回 200，测试模式和过期签名探针均返回 400；
无签名回调返回 400，未登录的付款请求返回 401。
探针不是真实 Stripe 付款事件，不能代替实际支付、退款、Stripe 投递重试或自动解锁验收。
临时检查函数只允许已验证的本项目 service-role JWT，匿名请求被拒绝；检查后已删除。

2026-09-18 已发布官网账号、确认／恢复页面、官网付款及退款条款、隐私政策和官网签名
Digital Asset Links。公网及 Pages 部署地址的 5 个页面均 HTTP 200 且与源文件逐字节匹配；
3 个 JS 模块也已回读。Chrome 实际显示官网 30 天一次付款条款。Stripe 公开详情中的
`https://beyoureye.com/privacy/` 与 `https://beyoureye.com/terms/` 已保存、重新打开回读。
Stripe Checkout 的法律条款、支持邮箱和支持网站展示已启用并保存回读。最新付款方式页面
仍显示支付宝和微信支付「待批准」、无需补充操作；银行卡、Apple Pay 和 Link 已启用。

后续普通表单使用以下已核对资料；涉及尚未提供的法定资料，不猜测或复制不适用的旧业务信息：

| 字段 | 填写内容 |
| --- | --- |
| 品牌 | Be Your Eye / 帮你盯 |
| 法定主体 | Marine Mystique Solutions Limited |
| 运营地区 | Hong Kong |
| 官网 | https://beyoureye.com |
| 支持邮箱 | support@beyoureye.com |
| 业务类型 | Android software / software services，以实际表单选项为准 |
| 收费方式 | One-time payment for 30 days of Pro access; no automatic renewal |
| 首轮验证价格 | HK$48／30天，不自动续费；正式收款尚未开放 |

英文业务介绍草稿：We develop Be Your Eye, an Android application for visual monitoring
using a phone camera. Users configure monitoring with reference images, numeric readings,
or supported visible targets. Camera monitoring runs on the device while the app is visible.
We plan to sell 30-day Pro access through our website as a one-time payment, with no automatic renewal.

1. 商户网站 `https://beyoureye.com`，支持邮箱 `support@beyoureye.com`，品牌和账单描述应能让买家认出 Be Your Eye。
2. 创建 Pro 30 days 商品及 **one-time** Price。Price metadata 必须含
   `product=be_your_eye_pro`、`access_days=30`。初版固定金额，不设置优惠码或自动税额叠加；
   发布方需核对定价、税务处理和退款条款，Stripe 在此不是销售主体。
3. 在此商户申请实际需要的付款方式（银行卡、Alipay、WeChat Pay 等）；
   可选项、批准及收银台实际显示是三件事。先做单次付款，不承诺钱包自动续费。
4. 创建专属服务端凭据。只需读取商户／Prices／PaymentIntents／Charges，
   创建和读取 Checkout Sessions；不需要转账或退款写权限。具体受限 key 权限以 Stripe 当前控制台为准。
5. 配置 webhook 到 `https://PROJECT.supabase.co/functions/v1/stripe-webhook`，监听
   `checkout.session.completed`、`checkout.session.async_payment_succeeded`、
   `charge.refunded`、`charge.dispute.created`、`charge.dispute.closed`。
6. 将 `STRIPE_SECRET_KEY`、`STRIPE_WEBHOOK_SECRET`、`STRIPE_ACCOUNT_ID`、
   `STRIPE_PRICE_ID`、`STRIPE_LIVE_MODE=true`、`WEBSITE_ORIGIN=https://beyoureye.com`
   放进 Supabase Function Secrets。密钥不得写进聊天、Git、App 或静态网页。
   正式发布前才设置 `WEBSITE_BILLING_ENABLED=true`；默认关闭。

测试商户、测试 Price 和测试 webhook 必须使用隔离的 Supabase 测试项目；
不能在生产项目打开测试模式并把测试付款兑换为生产权益。

## 部署与验收

先执行 `supabase db push --linked`，再部署 `product-entitlement`、`website-billing`、
`stripe-webhook`，以及使用统一权益检查的 `monitor-assistant`、`voice-transcription`。
函数 JWT 策略取 `supabase/config.toml`；不能统一关闭全部函数 JWT 校验。
网站 `account/config.json` 仅含 Supabase URL 和公开 publishable key。

返回网页不能直接发权益；Webhook 和回跳核对都从 Stripe 重新读取真实付款，
核对商户、模式、Price、金额、币种和订单归属后幂等写入独立账本。
相邻购买的使用期合并展示；全额退款或争议会收回对应期，后续已购买时长前移补齐空档。
部分退款不更改期限。已收回的订单不由迟到的付款事件重新激活；争议胜诉后的恢复需人工处理。
服务端即时按账本拒绝云能力；App 每 5 分钟刷新，离线租约最多 1 小时。

必须完成普通新用户注册／真实确认邮件、试用、付款到账自动解锁、重复 webhook、
付款成功但浏览器关闭、退款／到期／账号隔离／删号和手机网络可达性验证。
本地 SDK 测试和数据库测试不算真实交易验收。正式 APK 可下载后才发布付款入口。

## Android 签名

运行 `tools/release/prepare-website-signing-key.sh` 只初始化一次，已有材料时拒绝覆盖。
当前自持 app signing key 为 RSA 4096，证书有效至 2054-02-02；
公有证书 SHA-256 为 `A4:51:BA:73:21:1A:81:3D:50:99:74:25:75:24:0B:E5:6D:7B:19:C8:40:2C:A5:68:04:CB:1F:52:D9:14:80:9B`。

- 原始密钥及受限权限的 `signing.env`：`~/.config/be-your-eyes/android-app-signing/`。
- 唯一恢复副本：`/Volumes/DevDisk/DeveloperData/be-your-eyes/repo-local/signing-backup/website/`。
- Google Play upload key 保持原样，不能代替或擅自轮换官网 app signing key。
- 未来如需 Play 与官网包互相覆盖更新，应在 Play App Signing 的正式配置阶段安排相同的 app signing 身份；不能假定新建另一把 Play key 后仍可跨渠道覆盖。
- 备份含密钥与密码，文件 0600、目录 0700；勿公开或放进 Git。

在 `~/.config/be-your-eyes/website-release.env`（0600）准备现有云公共参数、
Commercial current／rollback Catalog 与 release URL、版本号等发布输入后，
从干净提交执行 `tools/release/build-website-apk.sh`。
产物写入外置盘该项目 `repo-local/releases/website/<commit>/<versionCode>/`，已存在的候选拒绝覆盖。
脚本先校验 Commercial 模型，使用现有 JDK 25 运行 AGP lint 和 `website` 构建
（Android 字节码目标仍为 Java 17）并核对 APK 签名，然后生成绑定该 APK 的 website
依赖 SBOM 与 OSV 漏洞报告。
仍需 APK 全新安装、同 key 覆盖升级、App Link、真实功能和收款验收。

2026-09-18 从干净提交 `e52c6950badc5722a45ff5f9e8b1e74f94c1f36a` 构建了 26／27 两版
官网 APK，用同一张自持证书签名。27 版 SHA-256 为
`f4febdaff3587a65e95263a1f6b09b2558aaf15cf40169beddc053bd45e69236`；
两版的 lint、Commercial 模型冻结、绑定 APK 的五份 SBOM 和 OSV 扫描通过。
2026-09-18 与 09-21 在连接的 PJA110 上尝试首次安装 26 版均返回 Android 安装错误
`Failure [-99]`，所以真机没有首次安装或 26→27 覆盖升级的通过记录。27 版仍是候选，
下载和收款入口暂不公开。
2026-09-21 再次安装 26 版仍返回相同错误。重复的预检模型下载、失败构建目录和本任务
`work/` 临时日志已清理，保留两版完整候选及相应签名模型快照，供手机安装和覆盖升级复测。

2026-09-21 在已有 Android 36／arm64／8GB／GMS 模拟器上，用上述两份**原始签名候选**完成
26 版全新安装和 26→27 版覆盖升级。版本号变为 27，首次安装时间保持不变；升级后可冷启动，
订阅墙、账号页和邮箱登录表单实际显示，未见 AndroidRuntime 致命异常。这个结果只证明模拟器
层的安装、签名兼容、启动与入口显示；没有执行真实邮箱登录、试用、付款、相机识别或手机安装。
PJA110 的 `-99` 原因仍未确定，真机门和发布门继续开放。
模拟器已正常停止；本轮 `work/` 中约 572 KiB 临时截图与日志已清理，已有 AVD 保留
27 版安装状态供后续账号和 App Link 验证，两份外置盘发布候选未复制或改动。

2026-09-18 新模型候选输入在 `model-tools/v3/releases/website-2026-09-18/templates/`。
current `2026.09.18.1` 与初始 rollback `2026.09.18.0` 已签名、发布至现有
`https://models.beyoureye.com/commercial/2026-09-18/be-your-eye-v1/`，并由 Commercial
freezer 从公网重新下载、验签和核对三个模型包及许可。初始 rollback 使用相同模型字节；
它是首发可恢复基线，不声称存在已验收的历史商业版本。两份 Catalog 分别新鲜至
2026-09-25 12:49:39Z／12:49:38Z。官网 APK 使用可维护的
`https://models.beyoureye.com/commercial/website/catalog.json` 入口；其 current／rollback
Catalog 和 release 描述符初始字节与上述版本完全相同。版本化原件保留，更新入口不改变
App 的签名、哈希和七天新鲜度校验。公开收款前仍须安排续签：到期影响新模型安装和
Catalog 查询，不能承诺只凭这份七天候选持续接纳新用户；已安装包的运行许可不是七天到期。

续签沿用现有 `release-builder-cli.mjs` 和仓库外 Ed25519 密钥：在新的版本目录生成
current／rollback 输入及签名，保留实际审核日期和模型字节，校验 Commercial freezer，
再发布版本化原件并更新上述入口的 `release.json`、`catalog.json` 及 `rollback/` 对。
回滚保留已核验包的精确身份，不能回指历史 Internal 包或直接修改旧签名文件的时间。
入口更新后再次从公网冻结校验；记录新版本、签发时间和下一次到期时间。当前未创建自动续签
任务，也没有把离线签名私钥上传云端。

参考权重的商业许可复核见
`model-tools/v3/reviews/reference-publisher-license-2026-09-18.md`：精确权重内嵌 Apache-2.0
声明、官方来源哈希和发布者公开说明支持商业使用及再分发，原先逐件索取签字信的要求已修正。
原 Internal 文档／签名保留原貌；新 Commercial Manifest 绑定新的审核哈希。模型署名及
Apache-2.0 全文已加入 App 的第三方许可页。许可结论不替代尚未通过的参考图真机识别。

此路线仍遵守 `docs/RELEASE.md` 的 Commercial 模型许可与实际设备门；
不能把 Internal 调试包、签名密钥存在或构建成功称为已经开放正式下载与收款。

依据：[Stripe 多账户规则](https://docs.stripe.com/get-started/account/multiple-accounts)、
[Checkout 到期参数](https://docs.stripe.com/api/checkout/sessions/create)、
[Android 自持签名](https://developer.android.com/studio/publish/app-signing)。


### 本轮账号与网页验证

- 网页支持确认邮件、重发确认、找回密码和设置新密码；回调在联网前从地址栏移除令牌。
- 关闭购买开关时，已登录用户仍能查看既有权益；新试用和新订单明确被拒绝，已付款会话仍可核对。
- 切换账号／登出清理旧订单请求标识；失效会话不继续携带旧订单。
- 6 项网页 Auth 行为测试、4 项 Stripe 核对测试、23 项 PostgreSQL／RLS 回归通过。
- 生产 Supabase 的一次性临时测试身份通过 22 项检查：未确认拒绝登录、确认／恢复链接单次消费、改密后旧密码失效、新密码登录、关闭收款时不生成订单或试用、来源限制及删除回读。测试账号已删除。
- 上述链接由 Auth Admin 测试接口生成，没有发送确认邮件，不能当作真实用户邮件到达、超时链接、Stripe 扣款／退款或付款后监控的验收。
