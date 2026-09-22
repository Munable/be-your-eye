<div align="center">

# 帮你盯 · Be Your Eye

### 别总回头看。让旧手机帮你盯着。

[English](README.md) · [自己构建](docs/community/BUILD.md) · [使用说明](USER_MANUAL.md) · [参与贡献](CONTRIBUTING.md)

</div>

<table>
<tr><th>数字过线，它记下来</th><th>有人出现，它看得到</th><th>给它图片，让它找目标</th></tr>
<tr>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="250" src="docs/community/demos/numeric.gif" alt="Android 应用读取真实电源仪表，并记录阈值事件"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="250" src="docs/community/demos/person.gif" alt="Android 应用识别街景视频中的行人"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="250" src="docs/community/demos/reference.gif" alt="Android 应用匹配参考图案"></a></td>
</tr>
<tr><td>画面里的数字，变成可以判断的读数。</td><td>选一个支持的目标，就能开始盯。</td><td>三张图片，告诉它你在找什么。</td></tr>
</table>

<sub>Community 版的 Android 模拟器实录。前两段把真实视频送入摄像头，第三段使用绘制的测试图案；识别和触发都由应用完成。点击动图下载清晰版视频。[素材来源、录制方法与结果](docs/community/demos/SOURCES.md)。</sub>

| 你总在回头看什么 | 给它什么 | 让它盯什么 |
| --- | --- | --- |
| 仪表、设备屏幕 | 画面里的数字 | 读数是否越过你设的阈值 |
| 门口、工作区域 | 支持的目标，比如“人” | 目标是否出现在画面中 |
| 某个具体物品 | 3–20 张参考图片 | 有没有和参考图片匹配的目标 |

**不用账号，不用订阅，不用填 API Key。识别就在手机上。**

## 从反复看一眼，到留下一条记录

固定手机，选好目标，设好条件。条件满足时，应用留下记录，也可以在这台手机上提醒你。

<table>
<tr><th>① 选你要盯的东西</th><th>② 设好触发条件</th><th>③ 回看发生了什么</th></tr>
<tr>
<td width="33%"><img width="250" src="docs/community/images/home.png" alt="首页的三种创建入口"></td>
<td width="33%"><img width="250" src="docs/community/demos/condition.png" alt="应用中设置读数阈值"></td>
<td width="33%"><img width="250" src="docs/community/demos/history.png" alt="视频回放中实际产生的本机事件记录"></td>
</tr>
</table>

## 放下另一台手机，也能收到提醒

| 监控手机 A | 随身手机 B |
| --- | --- |
| 创建配对组，显示二维码 | 扫码，开启收信 |
| 盯住画面，条件满足时发出提醒 | 收到加密文字提醒，查看时间和读数 |

两台手机联网就能配对，无需账号或自建服务器。提醒通过免费的公共 ntfy 中转，也可以自行更换；公共服务有额度和可用性限制。[两台手机怎么配](docs/community/PAIRING.md)。

<table>
<tr><th>A 发出测试提醒</th><th>B 收到同一条提醒</th></tr>
<tr><td align="center"><img width="250" src="docs/community/images/paired-send.png" alt="发送端应用已发出加密配对测试"></td><td align="center"><img width="250" src="docs/community/images/paired-receive.png" alt="另一台设备的收件箱中出现同一条测试提醒"></td></tr>
</table>

<sub>两个独立的 Android 模拟器安装，实际经过公共中转、手机端加密和系统通知。这里验证的是消息送达，摄像头识别演示见上方。[录制说明](docs/community/demos/SOURCES.md#paired-alerts)。</sub>

## 试试看

目前是**开源开发预览版**，还没有公开 APK。可以按[构建指南](docs/community/BUILD.md)运行 Community 版。

| 准备好 | 使用时记住 |
| --- | --- |
| Android 8+、arm64、8 GB 内存 | 固定机位、持续供电，保持应用可见 |
| 首次下载模型时联网 | 安装并校验过的模型可以离线复用 |
| 一个可以安全试错的场景 | 先用“测试识别”看看自己的画面是否适合 |

<details>
<summary><b>目前能做什么，哪些还在实验中</b></summary>

数字读数是当前主路线。参考图匹配和目录目标检测仍属实验功能。文字描述用于搜索有限的目标目录，不是任意一句话都能识别。“人”表示画面中有人，不是辨认人脸或确认身份。

同一时间运行一个监控。应用内的黑屏模式可以继续工作；切到其他应用或锁屏会停止。需要另一台手机提醒时，可用[扫码配对](docs/community/PAIRING.md)。消息先在手机端加密，再经免费公共中转发送；接收手机需主动开启常驻收信。

上面的片段展示了应用处理指定回放输入的过程，不代表自然现场准确率、逐帧无漏检、真实手机速度或长时间运行可靠性。真机、自然场景和长时间验收仍然开放，详情见[设备范围](docs/community/DEVICE_SUPPORT.md)和[当前证据](evidence/current/05-release.json)。它是帮助日常查看的早期工具，不是安全报警设备。

</details>

<details>
<summary><b>模型、构建与隐私细节</b></summary>

构建不需要维护者凭据，也不需要部署服务器。[模型就在这个仓库的 Releases](https://github.com/Munable/be-your-eye/releases/tag/models-v1)，下载前显示大小并征求确认。下载一次，校验后在手机上运行；不需要续订或定期续签目录。原始模型、数据来源和许可见[模型说明](docs/community/MODELS.md)。

识别在本机进行。普通相机帧只留在内存，参考图片和触发图保存在应用私有存储。跨手机提醒只发送加密文字，不传图像。项目不提供服务器、云端 AI、账号或收费服务；可选公共中转的额度与可用性由其运营者决定。

应用使用 Kotlin 与 Jetpack Compose，四个模块分别负责界面、领域规则、存储和视觉运行时。开发可从[架构](docs/ARCHITECTURE.md)与 [Community 检查入口](tools/ci/run-community.sh)开始。

</details>

## 你会把它对准什么？

一块总要走过去看的屏幕，还是一件一直在等它出现的东西？欢迎[告诉我们](https://github.com/Munable/be-your-eye/issues)：你的场景、想等的条件，以及试用时发生了什么。一个能复现的小失败也很有帮助，请不要上传私人照片或凭据。

[参与贡献](CONTRIBUTING.md) · [私下报告安全问题](SECURITY.md)

第一方代码：[Apache-2.0](LICENSE)。[模型](docs/community/MODELS.md)、[演示素材](docs/community/demos/SOURCES.md)和[第三方依赖](THIRD_PARTY_NOTICES.md)各自遵循原有许可。
