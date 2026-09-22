<div align="center">

# 帮你盯 · Be Your Eye

### 别总回头看。让闲置安卓手机帮你盯着。

**开源开发预览版 · 暂无公开 APK**

[English](README.md) · [观看演示](#看一次数字越界触发) · [构建并运行](docs/community/BUILD.zh-CN.md) · [使用说明](USER_MANUAL.zh-CN.md)

</div>

固定手机，对准一块数字屏、支持的物品或门口。设好条件，帮你盯可以记录事件并提醒你。**识别就在手机上，不用账号、订阅或 API Key。**

当前识别模型要求监控手机具备 **Android 8+、arm64 和 8 GB 内存**。请持续供电、保持应用可见：**切换应用或锁屏会停止监控**。应用内的黑屏模式可以继续运行。

目前适合能自行构建应用的开发者试用。[Releases 现在只有模型文件](https://github.com/Munable/be-your-eye/releases/tag/models-v1)，没有应用安装包。真机、自然场景和长时间运行验收仍未完成。

## 看一次数字越界触发

**数字读数 · 当前主路线，仍属预览。** 这段录制中，电源仪表从 `03.2` 开始变化。条件设为**高于 8、持续 1 秒**；应用在读到 `08.8` 时保存事件，并显示本机通知。

<p align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="320" src="docs/community/demos/numeric.gif" alt="模拟器回放：电源仪表读数超过 8，记录计数从 0 变成 1，并出现本机通知"></a></p>

[下载 23 秒完整片段](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4) · [查看触发条件](docs/community/demos/condition.png) · [素材来源与录制方法（英文）](docs/community/demos/SOURCES.md)

这是 Community 应用通过 Android 模拟器摄像头处理预录视频的实际过程，属于受控回放证据；尚不代表真实手机拍摄现场的表现，也不是准确率、速度或可靠性测量。

## 三种方式，选你要盯的东西

- **数字读数 · 当前主路线。** 提供画面中的数字和阈值。上方回放产生了一条越界事件，仍需测试自己的屏幕和光线。
- **目录目标 · 实验功能。** 选择支持的目标，比如“人”。[17 秒行人回放](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4)产生了出现记录，检测的是有人出现，不是身份或人数。
- **参考图片 · 实验功能。** 提供同一个目标的 3–20 张图片。[12 秒参考图回放](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4)匹配了绘制的测试图案，一般真实物品的匹配效果仍待验证。

文字描述用于搜索有限的签名目录，不是任意一句话都能识别；不支持的目标会明确拒绝。[查看行人动图](docs/community/demos/person.gif)、[参考图动图](docs/community/demos/reference.gif)或[录制结果（英文）](docs/community/demos/SOURCES.md#what-was-recorded)。

## 构建后，先试一个场景

1. **确认手机条件。** 当前签名模型要求 Android 8+、arm64、8 GB 内存，不依赖 Google Play 服务。详见[设备与网络范围](docs/community/DEVICE_SUPPORT.zh-CN.md)。
2. **构建并安装 Community 调试版。** 按[构建指南](docs/community/BUILD.zh-CN.md)操作。目前没有公开 APK；未签名的发布构建也不能直接安装。
3. **固定手机，选择目标。** 首次下载模型前需要确认，数字读数约需 78 MB。校验后的模型可以离线复用；同一时间运行一个监控。
4. **设好条件，确认一次真实结果。** “测试识别”是可选操作，完整配置不需要先测试成功才能保存。数字监控若仍在等待基准，需要确认基准和条件后才会产生越界事件。先检查本机记录和通知，再判断自己的场景是否适合。

[阅读使用说明](USER_MANUAL.zh-CN.md) · [查看创建入口](docs/community/images/home.png) · [查看回放事件记录](docs/community/demos/history.png)

应用默认跟随系统语言，也可在“关于”中手动选择并保存：英语、简体中文、繁体中文、日语、韩语、西班牙语、法语、德语和巴西葡萄牙语。

## 让另一台手机收到提醒

两台手机都安装 Community 应用后，通过二维码或复制的配对码加入同一组。监控端发送加密文字；接收端需要允许通知并主动开启收信。**只有监控端需要识别模型及其 8 GB 内存配置。** 单纯收信不运行视觉模型，接收端真机兼容范围仍待验证。跨手机提醒需要两端联网。

<p align="center"><img width="320" src="docs/community/images/paired-receive.png" alt="另一个独立模拟器安装的收件箱中出现已收到的加密测试提醒"></p>

[两台手机怎么配](docs/community/PAIRING.zh-CN.md) · [查看发送端](docs/community/images/paired-send.png) · [录制说明（英文）](docs/community/demos/SOURCES.md#paired-alerts)

这里的证据是**两个独立模拟器安装之间的测试提醒**：实际经过公共中转，并出现在接收端收件箱和通知中。“真机相机事件 → 另一台手机通知”的连续演示仍待完成。

提醒默认经过独立的免费公共 ntfy 中转，也可以自行选择兼容的 HTTPS 中转。服务额度、断网和手机省电限制可能导致延迟或丢失。主动开启的收信服务可以在后台运行；监控相机仍要求应用可见。这款预览工具不适合作为安全报警设备。

## 隐私与开源

普通相机帧只在内存处理。参考图片和触发图保存在监控手机的应用私有存储。跨手机提醒**只发送加密文字，不传图片或视频**。中转方能看到连接元数据和密文，拿不到群组密钥。项目不运营后端、云端 AI、账号或收费服务。

模型下载前会请求确认，使用前会校验；已安装并通过校验的模型可离线复用，不需要定期续签目录。[模型来源与许可（英文）](docs/community/MODELS.md) · [架构（英文）](docs/ARCHITECTURE.md) · [当前证据（英文）](evidence/current/05-release.json)

## 从一件具体的小事参与

- **描述一个真实场景：** 你反复检查什么、关心什么条件、下次什么时候能试。[提交监控场景](https://github.com/Munable/be-your-eye/issues/new?template=task.zh-CN.yml)。
- **试一台兼容手机：** 记录构建版本、机型、场景、预期和实际结果，包括漏检与误报。[报告可复现的问题](https://github.com/Munable/be-your-eye/issues/new?template=bug.zh-CN.yml)。
- **检查一种语言：** 看一遍创建流程、条件和通知，指出难懂或显示不全的地方。[参与贡献](CONTRIBUTING.zh-CN.md)。

请不要在报告中附上私人照片、配对码或凭据。[私下报告安全问题](SECURITY.zh-CN.md)。

第一方代码：[Apache-2.0](LICENSE)。[模型](docs/community/MODELS.md)、[演示素材](docs/community/demos/SOURCES.md)和[第三方依赖](THIRD_PARTY_NOTICES.md)各自遵循原有许可，详细来源说明为英文。
