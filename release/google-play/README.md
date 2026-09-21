# Google Play 发布材料

状态：`draft`。这里保存不含密钥的商店材料和可复现命令，不代表已经上传、通过审核或上线。

Production 英文品牌已固定为 `Be Your Eye`，中文商店展示名为“帮你盯”；既定
`applicationId=app.beyoureyes.monitor` 不改。2026-08-15 的精确名称初筛未在 Apple App
Store、Google Play 或公开索引的美欧国际商标记录中发现明显同名；已废弃早期名称与现有
App Store／Google Play 产品明显冲突。检查范围和可复现入口见
[`brand-screening-2026-08-15.md`](brand-screening-2026-08-15.md)。这项初筛不替代真实发布
主体在目标法域进行的正式近似商标检索。

公开支持邮箱已固定为 `support@beyoureye.com`，私密安全入口为
`https://beyoureye.com/security/`。法定发布者名称仍须与未来 Google Play 开发者账号验证
结果一致，因此该项继续保持 Production blocker；不得用 Git／Cloudflare 登录邮箱或虚构主体代填。

## 一次性准备 upload key

```bash
tools/release/prepare-google-play-upload-key.sh
```

脚本使用 Android Studio JBR 的 `keytool` 生成 4096-bit RSA upload key，输出到
`~/.config/be-your-eyes/android-upload/`，目录权限 `0700`，keystore、证书和环境文件权限
`0600`。私钥和密码不进入仓库。Play App Signing 的 app signing key应由 Google Play
生成；本地 key 只用于签署待上传的 AAB。

## 构建候选 AAB

1. 填写仓库外的 `~/.config/be-your-eyes/android-upload/release.env`；仓库模板见
   `release.env.example`。
2. 确认 `MODEL_CATALOG_URL` 与 `MODEL_RELEASE_URL` 指向同一冻结、签名有效的
   `commercial` 发布；`MODEL_ROLLBACK_CATALOG_URL` 与 `MODEL_ROLLBACK_RELEASE_URL`
   指向较早、不同哈希且同样完整验证的签名商业发布。四者都不能使用
   `internal-evaluation`。
3. 运行：

```bash
tools/release/build-google-play-bundle.sh
```

构建脚本与本地 CI 使用同一 Java 边界：Android production bytecode/AAB 固定 JDK 17，
AGP 9.3 `lintRelease` 通过 `mise` 隔离运行在 Temurin 25；缺少任一运行时都会在生成 AAB 前
失败，不使用系统默认 Java 猜测环境。

脚本在读取发布环境、下载模型或启动 Gradle 前，先要求当前 Git checkout 精确对应一个
clean commit：任何 staged、unstaged 或未忽略的 untracked 文件都会 fail-closed；正常被
`.gitignore` 忽略的构建输出不影响检查；`assume-unchanged` 和 `skip-worktree` 标记也会
被拒绝，不能用 Git index 隐藏 tracked source。发布记录固定写入该 commit 和
`dirty_worktree=false`，并在产出记录前再次确认 HEAD 与工作树未改变，不生成无法由所记
commit 重建的正式 AAB。随后下载并验证 Catalog、
每个 active Manifest、许可文本和脱敏许可审核 attestation；只有 Manifest 明确声明为
`verified` 且绑定 SHA-256 的可选专项 evidence 才会一并冻结，不无条件要求独立大语料或
延迟／功耗／温升三份报告，
并把每个模型与 sidecar 完整流式下载后核对 Manifest 中的大小和 SHA-256；任一字节不符，
Gradle 都不会启动。成功后产出仓库外 `.local/google-play/<version>/`：冻结模型发布快照、
签名 AAB、AAB SHA-256、由固定版本 bundletool 生成并校验签名的 universal APK、精确
`releaseRuntimeClasspath` 与 AAB 绑定的依赖 SBOM、OSV 漏洞扫描结果，以及记录当前/回滚
  Catalog、Manifest、模型和实际存在的可选 evidence 哈希的 `release-record.json`。当前与回滚的每个模型及 sidecar
在验签后保存在快照内按角色和 SHA-256 命名的不可变相对路径，远端对象删除后仍可离线重建。
脚本不登录 Play Console，也不上传或发布；universal APK 仍必须在干净真机完成
安装烟测后才形成发布证据。

漏洞门固定使用 OSV-Scanner `v2.5.1`；该版本已修复 `v2.5.0` 读取
CycloneDX 时丢失 npm namespace 的问题，见[上游发布说明](https://github.com/google/osv-scanner/releases/tag/v2.5.1)。

```bash
bash tools/release/smoke-install-google-play-apk.sh \
  .local/google-play/<version-name>-<version-code> --serial SERIAL
```

烟测脚本只接受 `release-record.json` 已绑定的 production universal APK，校验包名、版本和
SHA-256 后卸载 `app.beyoureyes.monitor` 并执行不带 `-r` 的安装；它会删除该设备上既有
Production App 数据，不触碰 Internal application ID。

clean-source 门可独立、无网络自检；测试只创建和删除临时 Git 仓库，不触碰当前工作树：

```bash
bash tools/release/test-clean-git-checkout-gate.sh
bash tools/release/test-prepare-google-play-upload-key.sh
bash tools/release/test-public-service-inputs.sh
bash tools/release/test-scan-sbom-vulnerabilities.sh
bash tools/release/test-smoke-install-google-play-apk.sh
```

## 商店材料

- `listings/`：中文与英文标题、短说明、完整说明草稿；最终候选只可宣传已通过
  Commercial 门的参考图片、固定数字屏读数，以及签名 Catalog 有精确 active 包覆盖的文字目标／现象。没有专业 active 模型时必须写不可配置，不能把安全、缺陷、人物身份或抽象状态推断成通用类别。AI、语音和 Pro 文案只在对应 Hosted smoke 关闭后保留到最终商店稿。
- `privacy/`：不含跟踪脚本的中英双语静态站点，同时提供隐私、订阅条款、账号删除、恢复回落和
  私密安全报告入口。政策覆盖本机相机帧、DeepSeek、Qwen AAC 转写、Supabase 账号恢复与
  Google Play Pro 数据流。固定公开地址为 `https://beyoureye.com/privacy/`，订阅条款为
  `https://beyoureye.com/terms/`，账号删除锚点为
  `https://beyoureye.com/privacy/#delete-account`。
- `privacy/company/`：Marine Mystique Solutions Limited（藍色聚合有限公司）的中英双语
  公司与三产品介绍，公开路径为 `https://beyoureye.com/company/`。公司名称及
  2023-12-29 成立日期由穆滨于 2026-09-14 提供；页面明确产品仍在开发、内部验证或私测。
  这项官网补充不代表 Google Play 发布者验证、Commercial 验收或 Startup 申请已完成。
- `data-safety-draft.md`：按当前二进制数据流填写的 Play Data safety 工作表；发布者仍需在 Console 逐项确认。
- `foreground-service-declaration.md`：Camera FGS Console 说明与同一 track 候选的视频脚本草稿。
- `assets/`：512×512 商店图标和 1024×500 feature graphic 草稿。
- `screenshots/`：3 张来自 Internal 物理设备 UI、无真人素材的 1080×2160 参考图；它们
  只用于商店叙事和隐私检查。最终仍必须从精确 Commercial AAB 分别重截至少 6 张英文和
  6 张简体中文手机图并再次检查。
- `ready-to-upload/README.md`：1.0.0 上传包的唯一状态索引；阻断关闭前保持 `blocked`，不放入
  Internal 制品或把草稿命名成最终上传文件。

静态站更新后从仓库根目录运行以下命令；它只上传 `privacy/` 静态文件，不需要 Worker、
Pages Function 或运行时密钥：

```bash
npx --yes wrangler@4.120.0 pages deploy release/google-play/privacy \
  --project-name be-your-eye-privacy \
  --branch main \
  --commit-hash "$(git rev-parse HEAD)" \
  --commit-dirty=true
```

## 密码恢复 App Link

生产恢复地址固定为 `https://beyoureye.com/auth/callback`，并在 Commercial
构建环境中设置：

```bash
export AUTH_REDIRECT_URL='https://beyoureye.com/auth/callback'
```

1. 在 Supabase Dashboard → Authentication → URL Configuration，把这个**完整地址**加入
   Redirect URLs；不要放宽为通配 host。
2. `supabase-auth-config.public.json` 是不含密码的生产 Auth 配置基线；在
   Authentication → SMTP 另行配置已验证发件域名／发件人、SMTP 用户和密码以及
   生产限流。仓库不保存 SMTP 密码。
3. Play Console 上传 track 后，在 App integrity → App signing key certificate 复制 SHA-256；
   不使用本地 upload key 指纹。把 `assetlinks.json.template` 中
   `PLAY_APP_SIGNING_SHA256` 替换为大写冒号分隔指纹，并将结果放到部署目录：

```bash
mkdir -p release/google-play/privacy/.well-known
cp release/google-play/assetlinks.json.template \
  release/google-play/privacy/.well-known/assetlinks.json
# 编辑唯一占位符后再部署；含 PLAY_APP_SIGNING_SHA256 的文件不得发布。
```

4. 重新部署 Pages，确认
   `https://beyoureye.com/.well-known/assetlinks.json` 返回 `200`、JSON 和准确
   package/SHA；同时确认 `https://beyoureye.com/auth/callback` 直接返回 `200` 且不追加尾斜杠，
   避免浏览器回落路径改变 App 严格校验的恢复地址。再用从 Play track 安装的 AAB 验证恢复邮件
   → App Link → 更新密码。模板本身不表示 Digital Asset Links 已配置。

当前 Outlook 实收的双语恢复邮件落入垃圾邮件夹；这证明 SMTP 投递和链接结构，不代表
收件箱可达性通过。该封邮件的原始头显示 SPF、DKIM、DMARC 和 Microsoft composite auth
全部通过，`BCL=0`，但 Microsoft 仍给出 `SCL=5`／`SpamFilterAuthJ` 并投递到 Junk。现有结果
因此没有暴露认证对齐故障，剩余风险集中在新发件域／发件路径声誉与收件方内容过滤。上架前
必须在未训练过的新收件人上重测 inbox placement，并持续观察 DirectMail 发件声誉；不通过
降低 Auth 安全设置、重复发送或把单个 Outlook 账号点“不是垃圾邮件”冒充全局修复。

当前 `privacy/.well-known/assetlinks.json` 包含 Internal 证书、本地 upload-key 证书和官网自持
app signing 证书。2026-09-18 已部署官网证书绑定；APK App Link 仍须真机核对。它不是 Play
App Signing 的证明，Play 指纹仍须由该平台回读后追加并用 track 安装包验证，勿覆盖官网证书。

## Hosted AI、语音与 Pro

以下值只进入 Supabase Function Secrets，不写 `release.env`、APK 或 Git：

```bash
supabase secrets set \
  DASHSCOPE_API_KEY='SECRET' \
  DASHSCOPE_BASE_URL='https://WORKSPACE.cn-beijing.maas.aliyuncs.com/compatible-mode/v1' \
  GOOGLE_PLAY_SERVICE_ACCOUNT_JSON='SERVICE_ACCOUNT_JSON' \
  GOOGLE_PLAY_RTDN_AUDIENCE='https://PROJECT.supabase.co/functions/v1/play-rtdn' \
  GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL='OIDC_PUSH_SERVICE_ACCOUNT'
```

- `DASHSCOPE_BASE_URL` 必须是北京 workspace 的 OpenAI-compatible base URL；助手固定
  `deepseek-v4-flash-0731`，语音固定 `qwen3-asr-flash-2026-02-10`。部署后各做一次登录且
  有 Pro 的真实请求，以及无账号／无 Pro 拒绝；日志不得含对话、转写、AAC 或相机媒体。
- Play Console 只建立一个自动续订商品 `be_your_eye_pro`，包含目标价 `$5.99/月` 的
  `monthly-auto` 与目标价 `$49.99/年` 的 `annual-auto` 两个 base plan，并为两者分别建立
  一次性 `trial-3d` P3D 新用户 offer；Production 展示的价格、
  币种和资格只由 Play 返回。服务账号启用 Android Publisher API 并只授予该 App 所需订阅权限。
- 建立 Pub/Sub RTDN topic 和 OIDC authenticated push subscription，push endpoint 为
  `https://PROJECT.supabase.co/functions/v1/play-rtdn`，audience 与 secret 完全一致；再在 Play
  Console 将 RTDN 连接到该 topic。
- 使用 license tester 和已上传 track 验证购买、恢复、acknowledge、续订、宽限／暂停、取消、
  退款与 RTDN。仓库实现、本地 mock 或商品可见都不能关闭这些 hosted/付款门。

## 发布前仍需人工填写

1. 在 Play Console 填写与开发者账号验证一致的法定发布者名称；公开支持邮箱
   `support@beyoureye.com`、私密安全入口 `https://beyoureye.com/security/`、稳定 HTTPS
   `PRIVACY_POLICY_URL` 与 `ACCOUNT_DELETION_URL` 已固定。
2. 用户按真实法律主体选择个人或组织开发者账号；项目不替用户选择。完成开发者身份、付款资料、
   协议、2FA、内容分级、目标受众、广告、Data safety 与账号删除 URL。
3. 上传同一冻结 AAB 到 internal/closed track；Play Console 生成的 app signing
   certificate SHA-256 用于 Digital Asset Links，不能和本地 upload certificate 混称。
4. 用精确 Commercial AAB 分别重截至少 6 张英文和 6 张简体中文 1080×2160 手机图，并人工
   检查无邮箱、令牌、用户素材、测试账号或其他 App 通知图标；仓库内候选图不构成该证据。
5. 关闭三能力商业 Catalog、当前真机关键路径、Hosted Auth/SMTP/App Link、DeepSeek/Qwen、
   Pro/RTDN、内部产品 Beta 和 P0/P1 门。稳定性验证按当前风险做一次有界真机检查；只有发现故障
   或风险评估明确要求时才扩大长时测试。若选择 2023-11-13 后创建的个人账号，还需至少 12 名测试者连续 14 天保持
   加入 closed test、申请 production access，并用非 root Android 10+ 真机完成设备验证；
   组织账号则需完成 D-U-N-S 和组织验证。只有适用门全部关闭后才推进生产发布。
6. 真实发布主体以 `Be Your Eye`／“帮你盯”完成目标法域的正式近似商标检索；初筛
   只排除了当前可见的明显冲突，不能替代法律结论。

Google 官方依据：

- [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [New-personal-account device verification](https://support.google.com/googleplay/android-developer/answer/14316361)
- [Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885)
- [Developer account information requirements](https://support.google.com/googleplay/android-developer/answer/13628312)
- [Existing App Store app named Be Your Eyes](https://apps.apple.com/us/app/be-your-eyes/id6480009961)
- [Be My Eyes on Google Play](https://play.google.com/store/apps/details?id=com.bemyeyes.bemyeyes)
