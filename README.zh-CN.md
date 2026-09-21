<div align="center">

# 帮你盯 · Be Your Eye

### 别再隔几分钟过去看一眼。

让一台闲置安卓手机盯住那个数字，达到条件时叫你。

[English](README.md) · [从源码构建](docs/community/BUILD.md) · [上手手册](USER_MANUAL.md) · [一起改进](CONTRIBUTING.md)

</div>

有些设备能显示数字，却不会告诉你什么时候该过去。于是你走过去看一眼，走开，过一会儿再看一眼。

**帮你盯想省掉的，就是这些反复查看。** 把手机固定好，用相机对准显示屏，设好提醒条件。识别在手机上完成，提醒和记录也留在这台手机上。

不用注册，不用订阅，不用填 API key，也不用搭服务器。

<p align="center">
  <img src="docs/community/images/home.png" width="240" alt="Be Your Eye Community home screen">
  <br><sub>当前 Community 首页 · 安卓模拟器截图</sub>
</p>

## 对准，设好，交给它盯

1. 打开「数字读数」，对准要看的数字。让它自动找数，也可以自己画框指定。
2. 确认基准值，设好条件，比如超过某个数值并持续几秒。
3. 开始监控。条件满足时，手机记录事件并在本机提醒。

除了数字，也可以用 **3–20 张参考图片**，或用**文字选择支持的可见物体**来配置监控。这两条路线还在实验阶段。文字描述只能匹配已有目标目录，不是想写什么就能识别什么的 AI 提示词。

## 现在可以试到什么

源码已按 **Apache-2.0** 开放。目前是开发预览，**还没有公开发布 APK**：真机、自然场景和长时间运行仍在验收。已经做过的构建与模拟器检查见[设备支持说明](docs/community/DEVICE_SUPPORT.md)。

想动手，可以从[构建指南](docs/community/BUILD.md)开始。Community 构建不需要维护者的私有配置；识别模型在你确认后单独下载。安装并校验通过后，已有模型可以离线复用。当前随包目录签发于 **2026 年 9 月 21 日**，七天内允许新下载；独立构建如何更新签名目录，也写在指南里。

当前模型需要 **Android 8 及以上、arm64、8 GB 内存**。手机要固定、接电，并保持 App 可见；App 内的黑屏模式可以继续运行，切到其他 App 或锁屏则会停止。同一时间只运行一个监控，提醒出现在**正在监控的这台手机上**。Community 暂不提供远程提醒。

先用「测试识别」看看自己的现场。反光、太小的数字、晃动都会影响结果。它还不是可以承担安全责任的报警设备。

## 你有什么东西，总得过去看一眼？

告诉我们你在盯什么、等什么变化，以及怎样的提醒才有用。一个具体场景，或者一次识别失败，都很适合成为一条 [Issue](https://github.com/Munable/be-your-eye/issues)。请不要上传私人画面或凭据。

想改代码，可以看[贡献指南](CONTRIBUTING.md)。App 使用 Kotlin 和 Jetpack Compose，四个模块分别负责界面、业务规则、存储和视觉运行。[架构说明](docs/ARCHITECTURE.md)解释了分工，[Community 检查入口](tools/ci/run-community.sh)可以在本地运行。仓库保留了连接版服务源码，Community 不会启用这些服务。

---

第一方代码采用 [Apache-2.0](LICENSE)；模型和依赖保留各自许可：[模型来源](docs/community/MODELS.md)、[第三方声明](THIRD_PARTY_NOTICES.md)。[私下报告安全问题](SECURITY.md)。
