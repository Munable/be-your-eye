<div align="center">

# 帮你盯 · Be Your Eye

**让闲置手机，帮你盯着。**

Android 本地视觉监控，无需账号、订阅或 API Key。

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.zh-CN.md)
[![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)
[![开发预览版](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#开始使用)

[English](README.md) · **简体中文**

[使用说明](USER_MANUAL.zh-CN.md) · [从源码构建](docs/community/BUILD.zh-CN.md) · [参与贡献](CONTRIBUTING.zh-CN.md)

</div>

## 看看它能做什么

<table>
<tr><th>读取数字</th><th>发现目标</th><th>匹配图片</th></tr>
<tr>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="250" src="docs/community/demos/numeric.gif" alt="应用读取电源仪表并记录一次越界事件"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="250" src="docs/community/demos/person.gif" alt="应用识别街景视频中的行人"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="250" src="docs/community/demos/reference.gif" alt="应用匹配绘制的参考图案"></a></td>
</tr>
<tr><td>读数越过阈值时留下记录。</td><td>检测支持的目标。<br><em>实验功能</em></td><td>匹配 3–20 张参考图片。<br><em>实验功能</em></td></tr>
</table>

Android 模拟器中的应用实录：读数和检测使用视频回放，图片匹配使用绘制的测试图案。点击动图查看视频。[来源与结果（英文）](docs/community/demos/SOURCES.md)。

- **本机识别。** 下载模型后，可以离线监控。
- **本机记录。** 事件和触发图保存在监控手机。
- **可选配对提醒。** 将加密文字发送到另一台手机。
- **九种语言。** 跟随系统，也可手动选择。

## 选好目标，设好条件，开始监控

固定手机，选择目标并设置条件。条件满足时，应用留下记录，也可以通知你。

<table>
<tr><th>选择目标</th><th>设置条件</th><th>回看记录</th></tr>
<tr>
<td width="33%"><img width="250" src="docs/community/images/home.png" alt="首页的三种监控创建入口"></td>
<td width="33%"><img width="250" src="docs/community/demos/condition.png" alt="设置数字越界阈值"></td>
<td width="33%"><img width="250" src="docs/community/demos/history.png" alt="演示中实际产生的本机事件记录"></td>
</tr>
</table>

## 一台手机盯着，另一台手机提醒你

扫码配对两台手机，加密文字通过公共中转发送，图片留在监控端。[配对指南](docs/community/PAIRING.zh-CN.md)。

<table>
<tr><th>发送</th><th>接收</th></tr>
<tr>
<td align="center"><img width="250" src="docs/community/images/paired-send.png" alt="发送端已发出加密测试提醒"></td>
<td align="center"><img width="250" src="docs/community/images/paired-receive.png" alt="另一接收端的收件箱中出现同一条测试提醒"></td>
</tr>
</table>

图中是两个模拟器经过公共中转收发测试提醒。送达受网络、中转可用性和 Android 电池设置影响。

## 开始使用

**开发预览版，暂无公开 APK。** [构建 Community 应用](docs/community/BUILD.zh-CN.md)后，按[使用说明](USER_MANUAL.zh-CN.md)操作。[Releases](https://github.com/Munable/be-your-eye/releases/tag/models-v1) 目前只有模型文件。

监控端要求 **Android 8+、arm64、8 GB 内存**。固定手机、持续供电并保持应用可见；切换应用或锁屏会停止监控。

数字读数是当前主路线，目标检测和参考图匹配仍属实验功能。文字用于搜索有限目录中的支持目标。真机准确率和持续运行可靠性尚未验证，不适合作为安全报警器。[设备范围与限制](docs/community/DEVICE_SUPPORT.zh-CN.md)。

## 参与贡献

试一个场景、[报告问题](https://github.com/Munable/be-your-eye/issues/new?template=bug.zh-CN.yml)，或改进翻译。详见[贡献说明](CONTRIBUTING.zh-CN.md)与[架构（英文）](docs/ARCHITECTURE.md)。漏洞请按[安全策略](SECURITY.zh-CN.md)私下报告。

## 许可

第一方代码使用 [Apache-2.0](LICENSE)。[模型](docs/community/MODELS.md)、[演示素材](docs/community/demos/SOURCES.md)和[依赖](THIRD_PARTY_NOTICES.md)各自遵循原有许可，详细说明为英文。
