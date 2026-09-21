<div align="center">

# Be Your Eye

### Give your spare Android phone something to watch.

Point it at a display. Set a condition. Let it tell you when the number gets there.

[中文](README.zh-CN.md) · [Build it](docs/community/BUILD.md) · [How it works](USER_MANUAL.md) · [Contribute](CONTRIBUTING.md)

</div>

Some things have a screen, but no way to tell you when they need your attention. You walk over, check the number, walk away—and come back to check it again.

**Be Your Eye turns the camera into that missing connection.** Fix an Android phone in place, point it at the display, and choose what should trigger an alert. Recognition runs on the phone; the alert and event history stay there too.

No account. No subscription. No API key. No server to set up.

<p align="center">
  <img src="docs/community/images/home.png" width="240" alt="Be Your Eye Community home screen">
  <br><sub>Current Community home screen · Android emulator</sub>
</p>

## Point. Set. Watch.

1. Open **Numeric reading** and point the camera at the reading. Let it find the number, or draw a box around it.
2. Confirm the baseline and set a condition, such as going above a value for a few seconds.
3. Start monitoring. When the condition holds, the phone records the event and gives a local alert.

There are two other ways to set up a monitor: give it **3–20 reference pictures**, or choose a **supported visible object** by description. Both are experimental. Descriptions match a defined catalog of targets; they are not an open-ended AI prompt.

## Try the developer preview

The source is open under **Apache-2.0**. There is no public APK release yet: physical-device, natural-scene and extended-run acceptance are still in progress. The current build and emulator checks are documented in [device support](docs/community/DEVICE_SUPPORT.md).

Start with the [build guide](docs/community/BUILD.md). Community builds work without maintainer credentials; recognition models download separately with your confirmation. Once verified and installed, those models can be reused offline. The bundled model catalog is dated **September 21, 2026** and admits new downloads for seven days; the guide explains refreshing signed metadata for an independent build.

You'll need **Android 8+, arm64 and 8 GB RAM** for the current models. Keep the phone fixed, powered and the app visible. The in-app dark screen keeps monitoring active; switching apps or locking the phone stops it. One monitor runs at a time, and notifications arrive **on that phone**. Remote alerts are not part of Community.

Use “Test recognition” on your own scene first. Glare, tiny digits and movement matter. This is an early tool for everyday checking, not a safety alarm.

## Have something you keep checking?

Tell us what it is, what you're waiting for, and what would make an alert useful. A concrete task—or a case where recognition fails—is a great place to start an [issue](https://github.com/Munable/be-your-eye/issues). Please keep private photos and credentials out of reports.

For code contributions, see [CONTRIBUTING](CONTRIBUTING.md). The app is Kotlin and Jetpack Compose, with four modules separating UI, domain rules, storage and vision. [Architecture](docs/ARCHITECTURE.md) explains the boundaries; [Community checks](tools/ci/run-community.sh) is the local verification entry point. The retained connected-service source is optional and is not activated by Community.

---

First-party code: [Apache-2.0](LICENSE). Models and dependencies keep their own licenses: [model sources](docs/community/MODELS.md), [third-party notices](THIRD_PARTY_NOTICES.md). [Report a security issue privately](SECURITY.md).
