# Be Your Eye／帮你盯名称初筛

检查日期：2026-08-15。结论：Production 英文品牌采用 `Be Your Eye`，中文展示名采用
“帮你盯”。它直接表达“固定画面发生变化时提醒”，不使用“眼睛”、无障碍志愿服务或
安全报警语义，也不再延续旧工作名与 `Be My Eyes` 的混淆。

## 可复现检查

| 范围 | 精确查询 | 2026-08-15 观察 |
|---|---|---|
| Apple Search API | [`Be Your Eye` US](https://itunes.apple.com/search?term=Be Your Eye&entity=software&country=us&limit=200)、[`CN`](https://itunes.apple.com/search?term=Be Your Eye&entity=software&country=cn&limit=200)、[`GB`](https://itunes.apple.com/search?term=Be Your Eye&entity=software&country=gb&limit=200) | 三个 storefront 的返回中均无 `trackName == Be Your Eye` |
| Apple Search API | [`帮你盯` CN](https://itunes.apple.com/search?term=%E5%B8%AE%E4%BD%A0%E7%9B%AF&entity=software&country=cn&limit=200) | 返回中无 `trackName == 帮你盯` |
| Google Play | [`Be Your Eye`](https://play.google.com/store/search?q=Be Your Eye&c=apps&hl=en_US&gl=US) | 页面明确返回 `No results for Be Your Eye` |
| 公开网页索引 | `"Be Your Eye" app/software/trademark`，并限定 Justia、WIPO Brand Database、EUIPO 与 CNIPA 公开站点 | 未出现同名消费级 App、软件品牌或精确商标记录；一般结果只包含 HTTP/2 源码里的 `Be Your Eye` 类型名 |

旧工作名的反例是明确的：Apple App Store 已有
[`Be Your Eyes`](https://apps.apple.com/us/app/be-your-eyes/id6480009961)，Google Play 已有
[`Be My Eyes`](https://play.google.com/store/apps/details?id=com.bemyeyes.bemyeyes)。二者都位于
视觉／读图邻近领域，因此旧名退出全部用户可见界面和 Production 商店材料。

## 仍需真实发布主体关闭的门

这是精确字符串和公开索引初筛，不是目标法域的完整近似商标法律意见。仓库没有法定发布者名称，
也不能替发布主体选择申请人、商品／服务类别或取得付费检索。Production 提交前，真实发布主体仍须：

1. 在实际销售法域对 `Be Your Eye`、`帮你盯` 及近似读音／字形执行正式商标检索；
2. 确认开发者账号展示名称与法律主体一致；
3. 在 Google Play 开发者账号中填写与账号验证一致的真实法定发布者名称；支持邮箱与私密安全
   入口已于 2026-08-26 配置为 `support@beyoureye.com` 与
   `https://beyoureye.com/security/`。

正式近似商标检索、法定发布者名称或 Play 账号验证未关闭时，品牌名称仍可用于候选构建，
但 Google Play Production 发布保持阻断。
