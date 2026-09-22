# 参与贡献

[English](CONTRIBUTING.md) · [项目首页](README.zh-CN.md)

## 先选一件小事

| 任务 | 需要记录什么 | 从这里开始 |
| --- | --- | --- |
| 描述一次反复检查 | 显示屏或物品、关心的条件、现在如何处理、下次尝试机会 | [提交监控场景](https://github.com/Munable/be-your-eye/issues/new?template=task.zh-CN.yml) |
| 试一台兼容手机和一个场景 | 构建版本或提交、Android 版本、机型和内存、设置、时长、预期与实际事件、漏检和误报；区分真机与模拟器回放 | 失败使用[问题报告](https://github.com/Munable/be-your-eye/issues/new?template=bug.zh-CN.yml)，现场体验使用[监控场景](https://github.com/Munable/be-your-eye/issues/new?template=task.zh-CN.yml) |
| 检查一种支持的语言 | 应用语言、页面或操作、当前措辞或截断、修改建议；可以只检查一次创建流程及其通知 | [问题报告](https://github.com/Munable/be-your-eye/issues/new?template=bug.zh-CN.yml) |

目前没有公开 APK。手机试用需要先[本地构建 Community](docs/community/BUILD.zh-CN.md)，准备固定机位和可以安全试错的场景。“真机相机触发事件并到达另一台手机”是尚缺的证据；发送测试消息需要单独标明。公开报告不需要私人影像或配对码。

## 代码与文案修改

先阅读[构建说明](docs/community/BUILD.zh-CN.md)，运行 `bash tools/ci/run-community.sh`。保留四个模块和签名模型合同。新增模型或抽象之前，先说明具体用户任务。不要添加训练、后台相机监控、广告 SDK 或插件平台。

应用可见文案遵循[开发规范（英文）](docs/DEVELOPMENT.md)中的九语言合同：`en`、`zh-Hans`、`zh-Hant`、`ja`、`ko`、`es`、`fr`、`de`、`pt-BR`。使用资源或字典键，不硬编码显示文本；占位符和复数规则保持一致，并运行 `node tools/ci/check-i18n.mjs` 及其测试。中转与网络错误在客户端本地化；用户输入和历史内容不会自动翻译。

报告问题时，请提供应用版本、Android 版本与机型（不要提供序列号）、步骤、预期和实际表现，以及问题发生在下载、设置、识别还是提醒阶段。照片和日志是可选的，分享前移除个人内容和凭据。也请说明你原先需要反复查看什么，以及下一次真实使用机会；星标和下载量不能代替使用证据。

第一方源码使用 Apache-2.0；有意提交的贡献采用同一许可，贡献者必须有权提交相关内容。请保留第三方原始声明，无需复杂 CLA。名称和标志只用于标识来源，不能暗示修改版得到官方认可。

Apache-2.0 代码许可不授予商标使用许可。请保留必须的声明，准确描述修改版，不能将其冒充为正式 Be Your Eye 发布或暗示发布者背书。
