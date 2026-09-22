<div align="center">

# Be Your Eye

### Stop checking. Let a spare Android phone watch.

**Open-source developer preview · No public APK yet**

[中文](README.zh-CN.md) · [Watch the demo](#see-a-number-trigger-an-event) · [Build and run](docs/community/BUILD.md) · [User guide](USER_MANUAL.md)

</div>

Point a fixed phone at a display, a supported object or a doorway. Set a condition; Be Your Eye can record the event and alert you. **Recognition runs on the phone. No account, subscription or API key.**

The monitoring phone needs **Android 8+, arm64 and 8 GB RAM** for the current models. Keep it powered and the app visible: **switching apps or locking the phone stops monitoring**. In-app dark-screen mode can keep it running.

This preview is for developers who can build the app. [Releases currently contains model files only](https://github.com/Munable/be-your-eye/releases/tag/models-v1), not an app installer. Physical-device, natural-scene and long-running acceptance remain open.

## See a number trigger an event

**Numeric reading · Main route, still a preview.** In this recording, a power-supply reading starts at `03.2`. The condition is **above 8 for one second**; the app stores an event at `08.8` and shows a local notification.

<p align="center"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="320" src="docs/community/demos/numeric.gif" alt="Emulator replay: the power-supply reading crosses 8, the record counter changes from 0 to 1, and a local notification appears"></a></p>

[Download the 23-second video](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4) · [See the configured condition](docs/community/demos/condition.png) · [Sources and recording method](docs/community/demos/SOURCES.md)

The actual Community app processes prerecorded video through an Android emulator camera. This is controlled replay evidence, not a live physical-phone demonstration or an accuracy, speed or reliability measurement.

## Three ways to choose what to watch

- **Numeric reading — main route.** Give it a visible number and a threshold. The replay above produced an event; test your own display and lighting.
- **Catalog target — experimental.** Choose a supported target such as “person”. The [17-second person replay](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4) produced an appearance record. This detects presence, not identity or a people count.
- **Reference images — experimental.** Supply 3–20 pictures of one target. The [12-second reference replay](https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4) matched an illustrated test pattern; general real-object matching remains unverified.

A text description searches the finite signed Catalog; it is not an open-ended AI prompt. An unsupported target is rejected. [Browse the person GIF](docs/community/demos/person.gif), [reference GIF](docs/community/demos/reference.gif) or [recorded results](docs/community/demos/SOURCES.md#what-was-recorded).

## Build it and try one scene

1. **Check your phone.** Monitoring requires Android 8+, arm64 and 8 GB RAM under the current signed model profiles. No Google Play Services are required. See [device and network scope](docs/community/DEVICE_SUPPORT.md).
2. **Build and install the Community debug app.** Follow the [build guide](docs/community/BUILD.md). There is no public APK yet; the unsigned release output is not installable until signed.
3. **Fix the phone in place and choose a target.** Confirm the first model download; numeric reading needs about 78 MB. Verified models can then be reused offline. Only one monitor runs at a time.
4. **Set a condition and check a real result.** Recognition testing is optional; a complete configuration can be saved without a successful test. A numeric monitor waiting for its baseline cannot create threshold events until you confirm the baseline and condition. Check the local record and notification before relying on a scene.

[Follow the user guide](USER_MANUAL.md) · [View the creation screen](docs/community/images/home.png) · [View replay event history](docs/community/demos/history.png)

The app follows the system language, with a saved manual choice in About: English, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, French, German and Brazilian Portuguese.

## Send an alert to another phone

Pair the Community app on both phones by QR code or copied code. The monitoring phone sends encrypted text; the receiving phone must allow notifications and explicitly start receiving. **Only the monitoring phone needs the recognition models and their 8 GB RAM profile.** Receiving alone does not run vision models; physical receiver compatibility remains unverified. Both phones need internet for paired alerts.

<p align="center"><img width="320" src="docs/community/images/paired-receive.png" alt="A separate emulator installation showing the received encrypted test alert in its inbox"></p>

[Pair two phones](docs/community/PAIRING.md) · [View the sender](docs/community/images/paired-send.png) · [Read the capture details](docs/community/demos/SOURCES.md#paired-alerts)

The evidence above is a **test alert between two separate emulator installations**, delivered through the real public relay and shown in the receiver inbox and notification. A continuous physical-camera event → second-phone notification demonstration is still pending.

Alerts use the independent free public ntfy relay, or another compatible HTTPS relay you choose. Quotas, network failures and Android battery restrictions can delay or lose alerts. A separately started receiver may run in the background; the monitoring camera still requires a visible app. This preview is not a safety alarm.

## Privacy and open-source details

Ordinary camera frames stay in memory. Reference pictures and saved trigger images stay in private storage on the monitoring phone. Paired alerts carry **encrypted text, never pictures or video**. The relay can see connection metadata and ciphertext; it does not receive the group key. The project operates no backend, cloud AI, account or paid service.

Models download after confirmation and are verified before use. Installed, verified models can be reused offline without periodic catalog renewal. [Model sources and licenses](docs/community/MODELS.md) · [Architecture](docs/ARCHITECTURE.md) · [Current evidence](evidence/current/05-release.json)

## Help with one concrete task

- **Describe a real scene:** what you repeatedly check, the condition you care about, and when you can next try it. [Share a monitoring task](https://github.com/Munable/be-your-eye/issues/new?template=task.yml).
- **Try one compatible phone:** record the build, phone model, scene, expected result and actual result. Include misses and false events. [Report a reproducible failure](https://github.com/Munable/be-your-eye/issues/new?template=bug.yml).
- **Review one language:** check a setup screen, a condition and a notification for clarity or clipping. [Start contributing](CONTRIBUTING.md).

Please leave private photos, pairing codes and credentials out of reports. [Report a security issue privately](SECURITY.md).

First-party code: [Apache-2.0](LICENSE). [Models](docs/community/MODELS.md), [demo footage](docs/community/demos/SOURCES.md) and [dependencies](THIRD_PARTY_NOTICES.md) retain their own licenses.
