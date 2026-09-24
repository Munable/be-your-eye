# Be Your Eye — product positioning

Reviewed against the Community source and public README at `f32b538` on
2026-09-24. This is a product and writing reference. [Product scope](PRODUCT.md),
[architecture](ARCHITECTURE.md) and [release evidence](../evidence/current/05-release.json)
remain the authorities for behavior and readiness.

## English

### The idea to remember

**Give ordinary objects a way to notify you.**

A display can show a changing number without being able to tell you when it
matters. Put a compatible spare phone in front of it, set a condition, and let
Be Your Eye turn the visible reading into an event. The device being watched
needs no network connection, software integration or modification. The camera
is the connection.

This is our clearest expression of software changing the physical world: add a
useful new behavior to an existing object by changing the software on a phone.

### Who it helps and what they want done

Someone repeatedly checking a visible display or a supported target in a fixed
scene, at home or in a small workspace. Their job is concrete: “Tell me when
this condition happens, and leave a record I can check.” Numeric reading is the
first route to communicate; it makes the benefit tangible without asking the
reader to understand a general vision model.

### Choices that serve the idea

| Choice | What it gives the user | The tradeoff we accept |
| --- | --- | --- |
| Use the camera as the interface to an existing object | Observe a readable display without adding electronics or integrating its software | A fixed view, suitable lighting and a compatible monitoring phone are part of setup |
| Keep recognition, rules and history on the phone | Download the models once, then run local monitoring without an account, subscription or recognition service | Supported models and targets are deliberately bounded; the phone does the work |
| Turn observations into conditions and events | Ask for a threshold or duration, then review what triggered | Recognition confidence and unavailable frames must affect the rule, rather than being hidden |
| Send an optional encrypted text alert | A second phone can receive the result while images remain on the monitoring phone | Remote delivery needs a relay and network; it is best effort |

The defining benefit is **adding a condition and an alert to something already
visible**. Spare-phone reuse and local operation make that possible with fewer
dependencies. They are supporting reasons to believe the idea, not three
unrelated headline claims.

### Website copy

**Give ordinary objects a way to notify you.**

A spare phone watches a display. You set the condition. Be Your Eye turns a
visible reading into a local event and an alert, with recognition on the phone
and images kept there.

Short descriptor: **A camera, a condition, a useful signal.**

Status beside the link: **Open-source developer preview**. Point the link to
the repository, where installation requirements and current evidence are
available.

### Evidence and claim boundaries

- The numeric demonstration uses the ordinary app pipeline to read a
  power-supply display, trigger above `8` for one second, record `08.8` and show
  a local notification. Camera input is controlled replay on an emulator. It
  demonstrates the loop; it does not measure physical-device accuracy.
  See [demo sources and results](community/demos/SOURCES.md).
- The camera → observation → rule → event path exists in
  [`MonitoringSession`](../android/app/src/main/java/app/beyoureyes/monitor/service/monitoring/MonitoringSession.kt)
  and [`GenericObservationRuleEngine`](../android/core/domain/src/main/java/app/beyoureyes/core/domain/GenericObservationRuleEngine.kt).
  Optional text delivery is implemented in
  [`PeerAlertSender`](../android/app/src/main/java/app/beyoureyes/monitor/feature/peers/PeerAlertSender.kt).
- [Product scope](PRODUCT.md) and [architecture](ARCHITECTURE.md) define local
  recognition, private images, no project-operated backend and offline reuse
  after model download. Paired alerts are optional network traffic, so do not
  describe the entire app as never using the internet.
- [Device support](community/DEVICE_SUPPORT.md) currently requires Android 8+,
  arm64 and 8 GB RAM for recognition. Say “compatible spare phone”; do not claim
  every old phone can run it or promise lower power use without measurements.
- The public entry point is a developer source preview. Numeric reading is the
  main route; reference-image and Catalog-target routes are experimental.
  Physical-device accuracy, sustained operation and human acceptance are still
  separate open gates. Keep those details at the evaluation/install entry,
  without turning the company description into a list of defects.

Do not describe it as a general scene-understanding assistant, an identity
recognition service, a guaranteed alarm or a system that controls the observed
device. The current product observes, records and notifies.

### A decision rule for future work

Prefer changes that help a person define one visible condition, trust what the
app did with the evidence, and receive a useful event with less setup. A new
model, integration or feature should strengthen that loop. Adding more things
the app can talk about is not sufficient by itself.

## 简体中文

### 要让人记住的点

**让原本只会显示的设备，也能提醒你。**

一个显示屏会跳动数字，却未必能在你关心的时刻通知你。把一台兼容的闲置手机对准它，
设好条件，帮你盯就把看见的读数变成事件。被观察的设备不用联网、不用接入软件，也不用改装。
摄像头就是连接。

这件事直接体现“用软件改变现实”：不更换眼前的东西，通过手机里的软件，为它增加一种有用的行为。

### 为谁，解决什么事

面向在家庭或小型工作场所里反复查看显示屏、或等待固定场景中某个支持目标的人。
他们要的结果很明确：“条件发生时告诉我，留一条可以回看的记录。”
官网首先讲数字读数，让人马上理解用途；三种创建方式不需要争抢同一个标题。

### 所有特色围绕同一个结果

- **用摄像头连接现实。** 对准已有的显示屏，就能设定读数条件；不用改装被观察的设备。
  固定机位、合适的光线和兼容的手机是必要设置。
- **在手机上完成判断。** 模型下载后，本地识别、规则和记录无需账号、订阅或识别服务。
  支持范围由实际模型决定，计算由手机承担。
- **把画面变成事件。** 关注阈值、持续时间和留下的记录；识别不确定时必须保留不确定性。
- **需要时，把结果带走。** 可向另一台手机发送加密文字，画面留在监控端；远程送达取决于网络和中转服务。

核心始终是：**给眼前看得见的变化，加上一条规则和一次提醒。**
闲置手机再利用、本地运行、可选配对，都在服务这件事。

### 官网用语

**让原本只会显示的设备，也能提醒你。**

一台闲置手机盯住显示屏，你来设定条件。帮你盯把可见的读数变成事件和提醒；识别在手机上完成，画面也留在本机。

短描述：**一个摄像头，一条规则，一次有用的提醒。**

入口状态：**开源开发预览版**。链接指向仓库，由安装与评估文档说明设备要求和当前验证范围。

### 表达边界与后续取舍

已公开的数字演示记录了“超过 8 并持续 1 秒”到本地事件、通知的完整路径，输入是模拟器中的回放画面。
它能证明流程，不能证明真机准确率。目前主线是数字读数，参考图和目录目标仍属实验路线。
监控端目前需要 Android 8+、arm64、8 GB 内存，并保持应用可见，因此不能宣传“所有旧手机都能用”。
具体依据见上方英文证据链接，真机和长时间运行的验收状态仍由发布证据决定。

后续优先改善“设定一个可见条件 → 正确处理观察结果 → 留下有用事件”这条路径。
功能是否值得做，要看它能否让这件事更容易、更可信。观察、记录、提醒，是当前产品清楚的落点。
