<div align="center">

# Be Your Eye

**Give your spare phone something to watch.**

Local visual monitoring for Android. No account, subscription or API key.

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](docs/community/DEVICE_SUPPORT.md)
[![Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)
[![Developer preview](https://img.shields.io/badge/Status-Developer_preview-e6b86a)](#get-started)

**English** · [简体中文](README.zh-CN.md)

[User guide](USER_MANUAL.md) · [Build from source](docs/community/BUILD.md) · [Contribute](CONTRIBUTING.md)

</div>

## See it in action

<table>
<tr><th>Read a display</th><th>Watch a target</th><th>Match your pictures</th></tr>
<tr>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/numeric.mp4"><img width="250" src="docs/community/demos/numeric.gif" alt="The app reading a power-supply display and recording a threshold event"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/person.mp4"><img width="250" src="docs/community/demos/person.gif" alt="The app detecting people in street footage"></a></td>
<td width="33%"><a href="https://github.com/Munable/be-your-eye/raw/refs/heads/main/docs/community/demos/reference.mp4"><img width="250" src="docs/community/demos/reference.gif" alt="The app matching an illustrated reference target"></a></td>
</tr>
<tr><td>Record a value crossing a threshold.</td><td>Detect a supported target.<br><em>Experimental</em></td><td>Match 3–20 reference pictures.<br><em>Experimental</em></td></tr>
</table>

Actual app recordings on an Android emulator: video replay for reading and detection, an illustrated target for matching. Click a demo for its video. [Sources and results](docs/community/demos/SOURCES.md).

- **On-device recognition.** Download the models once, then monitor offline.
- **Local history.** Keep events and trigger images on the monitoring phone.
- **Optional paired alerts.** Send encrypted text to another phone.
- **Nine languages.** Follow the system language or choose your own.

## Choose. Set. Monitor.

Fix the phone in place, choose a target and set the condition. When it holds, the app records an event and can notify you.

<table>
<tr><th>Choose a target</th><th>Set a condition</th><th>Review events</th></tr>
<tr>
<td width="33%"><img width="250" src="docs/community/images/home.png" alt="Home screen with three monitoring routes"></td>
<td width="33%"><img width="250" src="docs/community/demos/condition.png" alt="Setting a numeric threshold"></td>
<td width="33%"><img width="250" src="docs/community/demos/history.png" alt="Local event history from the recorded demos"></td>
</tr>
</table>

## One phone watches. Another tells you.

Pair two phones by QR code. Alerts travel as encrypted text through a public relay; pictures stay on the monitoring phone. [Pairing guide](docs/community/PAIRING.md).

<table>
<tr><th>Send</th><th>Receive</th></tr>
<tr>
<td align="center"><img width="250" src="docs/community/images/paired-send.png" alt="Sender after sending an encrypted test alert"></td>
<td align="center"><img width="250" src="docs/community/images/paired-receive.png" alt="A separate receiver with the same test alert in its inbox"></td>
</tr>
</table>

Shown: a test alert between two emulators through the public relay. Delivery depends on network, relay availability and Android battery settings.

## Get started

**Developer preview — no public APK yet.** [Build the Community app](docs/community/BUILD.md) and follow the [user guide](USER_MANUAL.md). [Releases](https://github.com/Munable/be-your-eye/releases/tag/models-v1) currently contains model files only.

Monitoring requires **Android 8+, arm64 and 8 GB RAM**. Keep the phone fixed, powered and the app visible; switching apps or locking stops monitoring.

Numeric reading is the main route; target detection and reference matching are experimental. Text searches a finite Catalog of supported targets. Physical-device accuracy and sustained reliability remain unverified. This is not a safety alarm. [Device scope and limits](docs/community/DEVICE_SUPPORT.md).

## Contributing

Try a scene, [report a bug](https://github.com/Munable/be-your-eye/issues/new?template=bug.yml), or improve a translation. See [Contributing](CONTRIBUTING.md) and [Architecture](docs/ARCHITECTURE.md). Report vulnerabilities through the [security policy](SECURITY.md).

## License

First-party code is [Apache-2.0](LICENSE). [Models](docs/community/MODELS.md), [demo footage](docs/community/demos/SOURCES.md) and [dependencies](THIRD_PARTY_NOTICES.md) retain their own licenses.
