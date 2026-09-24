<div align="center">

# 帮你盯 · Be Your Eye

**让闲置手机，替你盯着。**

对准摄像头，设好条件，发生时提醒你。

[English](README.md) · **简体中文** · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.zh-CN.md) [![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE) [![开发预览版](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**[观看演示](#demo)** · **[从源码构建](#get-started)** · [使用说明](USER_MANUAL.zh-CN.md)

</div>

## 摄像头就是连接

让原本只会显示的设备，也能提醒你。把一台兼容的闲置手机对准显示屏，设好读数条件，就能把看得见的变化变成事件，无需改装被观察的设备。识别和记录都留在手机上。

[产品的出发点与取舍](docs/PRODUCT_POSITIONING.md#简体中文)。

<a name="demo"></a>

## 不用反复看仪表，让手机帮你盯着。

<table>
<tr>
<td width="45%" align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="280" src="docs/community/demos/numeric.gif" alt="应用实录：读取电源仪表，触发越界事件"></a></td>
<td width="55%">
<h3>读数超过 8，手机就留下记录。</h3>
<p>一个电源仪表、一个摄像头，再加一条简单规则：</p>
<ol><li>盯住屏幕上的数字。</li><li>超过 8 并持续 1 秒时触发。</li><li>在读数 08.8 时记录事件，并弹出本机通知。</li></ol>
<p><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4">观看 23 秒实录</a></p>
</td>
</tr>
</table>

演示为 Android 模拟器中的应用实录，输入为回放视频和绘制的参考图案；展示的是操作流程，不代表真机准确率。 [演示来源与结果（英文）](docs/community/demos/SOURCES.md).

- **画面留在你手里。** 识别在手机上运行，图片保留在本机。
- **无需账号、订阅或 API Key。** 下载模型后即可离线监控。
- **发生过什么，随时回看。** 在本机回看记录，也可向另一台手机发送加密文字提醒。

## 选好目标，设好条件，开始监控

选择要盯的目标，设好条件，保持应用可见。需要时回看事件记录。

<table>
<tr><th>选择目标</th><th>设置条件</th><th>回看记录</th></tr>
<tr><td align="center" width="33%"><img width="220" src="docs/community/images/home.png" alt="首页的三种监控创建入口"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/condition.png" alt="设置数字阈值和持续时间"></td><td align="center" width="33%"><img width="220" src="docs/community/demos/history.png" alt="本机历史中的目标演示事件"></td></tr>
</table>

## 也可以盯这些

<table>
<tr><th>有人出现时提醒你 · 实验功能</th><th>用图片指定要找的目标 · 实验功能</th></tr>
<tr><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="240" src="docs/community/demos/person.gif" alt="应用在回放的街景视频中检测行人"></a></td><td align="center" width="50%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="240" src="docs/community/demos/reference.gif" alt="应用将绘制的目标与参考图片匹配"></a></td></tr>
<tr><td>从目录中选择支持的目标。演示检测的是行人类别，不识别身份。</td><td>提供 3–20 张参考图片。演示匹配的是绘制的测试图案。</td></tr>
</table>

## 一台手机盯着，另一台手机提醒你

扫码配对手机，加密文字通过你选择的中转服务发送，图片留在监控端。 [配对指南](docs/community/PAIRING.zh-CN.md).

<table>
<tr><th>发送提醒</th><th>另一台手机收到提醒</th></tr>
<tr><td align="center" width="50%"><img width="240" src="docs/community/images/paired-send.png" alt="发送端已发出加密测试提醒"></td><td align="center" width="50%"><img width="240" src="docs/community/images/paired-receive.png" alt="另一接收端显示同一条测试提醒"></td></tr>
</table>

图中是两个模拟器通过公共中转收发测试提醒。送达受网络、中转可用性和 Android 电池设置影响。

<a name="get-started"></a>

## 开始使用帮你盯

**开发预览版，暂无公开 APK。** 从源码构建 Community 应用，再按使用说明操作。Releases 目前只有模型文件。

**[从源码构建](docs/community/BUILD.zh-CN.md)** · [使用说明](USER_MANUAL.zh-CN.md)

**监控手机要求：** Android 8+、arm64、8 GB 内存。固定手机、持续供电并保持应用可见；切换应用或锁屏会停止监控。

数字读数是当前主路线，另外两种路线仍属实验功能。支持目标来自有限目录。真机准确率和持续运行可靠性尚未验证，不适合作为安全报警器。 [设备要求与限制](docs/community/DEVICE_SUPPORT.zh-CN.md).

## 一起把它做好

试一个场景、报告问题，或改进翻译。 [参与贡献](CONTRIBUTING.zh-CN.md) · [架构（英文）](docs/ARCHITECTURE.md) · [安全策略](SECURITY.zh-CN.md).

## 许可

**可免费使用、修改和分发，包括商用。** 第一方代码采用 [Apache-2.0](LICENSE)，无需另行申请许可；请保留许可要求的许可证、署名及修改声明。[模型](docs/community/MODELS.md)、[演示素材](docs/community/demos/SOURCES.md)和[依赖](THIRD_PARTY_NOTICES.md)仍遵循各自许可，详细说明为英文。
