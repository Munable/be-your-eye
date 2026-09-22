# Device and network scope

[中文](DEVICE_SUPPORT.zh-CN.md) · [Build and install](BUILD.md) · [User guide](../../USER_MANUAL.md)

Installation: Android 8 / API 26 or newer, arm64-v8a. Current signed model profiles require 8192 MB marketed RAM. Community excludes Firebase Messaging and Play Billing and does not require GMS. No 4/6 GB performance claim is made.

The 8 GB recognition requirement applies to the monitoring phone. A phone used only to receive paired text alerts does not load vision models or need the camera for receiving; QR scanning is optional because a copied pairing code also works. The receiver still needs a compatible Community installation, internet, notification permission and explicitly enabled receiving. A 4 GB API 36 emulator received a test alert; this is not a supported physical-device list or a guarantee for every 4 GB phone.

Current testing is recorded in `evidence/current/05-release.json`. The arm64 API 36 emulator passed a fresh debug startup test and a separate signed release cold launch; those startup checks alone are not model/camera acceptance. The later [demo recordings](demos/SOURCES.md) exercised the actual Community numeric, person and reference runtimes through an emulator video-file camera and verified local records. They remain controlled replay evidence, not physical natural-scene acceptance. PJA110 / API 36 refused the new package with error -99; installation needs the owner’s on-device authorization. No existing connected installation or data was removed. A camera pointed at a computer screen is replay evidence, not natural-object accuracy. Real-object threshold events, a representative 1–2 hour run, and unaided participants remain open until recorded.

Mainland without proxy and overseas site/APK/model reachability are separate checks. A successful request from the development Mac proves only that path. Public APK download is pending. Keep the phone fixed, powered, portrait/rear-camera and the app visible; leaving or locking stops capture. Thermal critical state stops monitoring without silently restarting.
