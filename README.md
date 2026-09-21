<div align="center">

# Be Your Eye

### Stop checking. Let a spare phone watch.

[中文](README.zh-CN.md) · [Build it](docs/community/BUILD.md) · [User guide](USER_MANUAL.md) · [Contribute](CONTRIBUTING.md)

</div>

<table>
<tr><th>A number crosses the line</th><th>Someone enters the scene</th><th>Your pictures become a target</th></tr>
<tr>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="250" src="docs/community/demos/numeric.gif" alt="The Android app reading a real power-supply display and recording a threshold event"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="250" src="docs/community/demos/person.gif" alt="The Android app detecting pedestrians in street footage"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="250" src="docs/community/demos/reference.gif" alt="The Android app matching an illustrated reference target"></a></td>
</tr>
<tr><td>A camera becomes a reading.</td><td>A supported target becomes a monitor.</td><td>Three pictures. One thing to watch for.</td></tr>
</table>

<sub>Actual Community app recordings on Android emulator. The first two use real video as camera input; the third uses an illustrated test target. Tap a clip to download the full-size video. [Sources, setup and results](docs/community/demos/SOURCES.md).</sub>

| What you keep checking | What you give it | What it watches for |
| --- | --- | --- |
| A meter or display | A visible number | A value crossing your threshold |
| A doorway or work area | A supported target, such as “person” | The target appearing in the frame |
| A particular object | 3–20 reference pictures | A visual match to your pictures |

**No account. No subscription. No API key. Recognition runs on the phone.**

## From a glance to a record

Fix the phone in place, choose what matters, and start monitoring. When your condition holds, the app records the event and can alert you on that phone.

<table>
<tr><th>1 · Choose what to watch</th><th>2 · Set the condition</th><th>3 · Check what happened</th></tr>
<tr>
<td width="33%"><img width="250" src="docs/community/images/home.png" alt="Three creation routes on the home screen"></td>
<td width="33%"><img width="250" src="docs/community/demos/condition.png" alt="A threshold configured in the Android app"></td>
<td width="33%"><img width="250" src="docs/community/demos/history.png" alt="Actual local events recorded during the camera replay"></td>
</tr>
</table>

## Try it

This is an **open-source developer preview**. Build the Community app with the [build guide](docs/community/BUILD.md); there is no public APK release yet.

| Bring | Keep in mind |
| --- | --- |
| Android 8+, arm64, 8 GB RAM | Keep the phone fixed, powered and the app visible |
| An internet connection for the first model download | Installed, verified models can be reused offline |
| A scene you can try safely | Test recognition on your own scene before relying on it |

<details>
<summary><b>What works, what is still experimental</b></summary>

Numeric reading is the main route. Reference matching and catalog-based object detection are experimental. A description searches a finite catalog; it is not an open-ended AI prompt. “Person” means person presence, not facial identity.

One monitor runs at a time. In-app dark-screen mode keeps it running; switching apps or locking the phone stops it. Community notifications and history stay on the monitoring phone. There are no remote alerts in this edition.

These selected replay clips show the app processing controlled inputs. They do not establish natural-scene accuracy, reliable detection of every frame, phone performance or extended-run reliability. Physical-device and natural-scene acceptance remain open. See [device support](docs/community/DEVICE_SUPPORT.md) and [current evidence](evidence/current/05-release.json). This is an early tool for everyday checking, not a safety alarm.

</details>

<details>
<summary><b>Models, builds and privacy</b></summary>

Community builds require no maintainer credentials or server. Models download separately after showing their size and asking for confirmation. The bundled signed catalog is dated September 21, 2026 and admits new downloads for seven days; the [build guide](docs/community/BUILD.md) explains refreshing signed metadata for an independent distribution.

Recognition runs locally. Ordinary camera frames stay in memory. Reference pictures and any saved trigger image stay in the app's private storage. The retained connected-service source is optional and is not activated by Community.

The app uses Kotlin and Jetpack Compose, with four modules separating UI, domain rules, storage and vision. Start with [architecture](docs/ARCHITECTURE.md) and [Community checks](tools/ci/run-community.sh).

</details>

## What would you point it at?

A display you walk over to check? Something you are waiting to arrive? [Tell us the scene](https://github.com/Munable/be-your-eye/issues), the condition, and what happened when you tried it. A small, repeatable failure is especially useful. Please leave private photos and credentials out of reports.

[Contributions welcome](CONTRIBUTING.md) · [Report a security issue privately](SECURITY.md)

First-party code: [Apache-2.0](LICENSE). [Models](docs/community/MODELS.md), [demo footage](docs/community/demos/SOURCES.md) and [dependencies](THIRD_PARTY_NOTICES.md) retain their own licenses.
