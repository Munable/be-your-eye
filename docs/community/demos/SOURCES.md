# Demo footage and reproduction

The public gallery uses recordings of the Community Android app. Input footage is replayed through the emulator's rear camera; recognition and event creation run in the app. These are controlled replay demonstrations, not field accuracy or latency measurements. No readings, detection boxes or event counts are added in editing.

## Source footage

| Scene | Source | Rights |
| --- | --- | --- |
| Bench power supply | [Using power supply machine to control electrical voltage](https://www.pexels.com/video/using-power-supply-machine-to-control-electrical-voltage-3466611/) by José Alfredo Munguía Lira | [Pexels License](https://www.pexels.com/license/); cropped, silent excerpts inside an app demonstration. No endorsement implied. |
| Pedestrians | [People walking on a busy urban street during daytime](https://www.pexels.com/video/video-of-different-kinds-of-people-walking-in-the-street-during-daytime-5044656/) by Artem Shutkin | [Pexels License](https://www.pexels.com/license/). Category detection only; no face recognition, identification or endorsement. |
| Reference pattern | The three `reference-camera-target-*.png` fixtures in this repository | First-party illustrated test input, Apache-2.0. |

Original video hashes (SHA-256):

- Power supply: `80a33dac05299fbe269b1a95644abb402303d3363d011411ca51b7ca3422f3e9` (1280 × 720, 45.87 s).
- Pedestrians: `657685336c3d6e2c546bebb17a05d00eddffcb045b176434ee62db9a76623a59` (1920 × 1080, 4.52 s).

The Pexels footage retains its own terms; the repository's Apache-2.0 license does not relicense it. Original stock videos are not bundled in the source tree. The old concept advertisement is not used as evidence of the current app.

## What was recorded

| Demo | App configuration | Observed result |
| --- | --- | --- |
| [Numeric video](numeric.mp4) · [setup](condition.png) | Manual box around the voltage display; baseline `03.2` confirmed unchanged; above `8` for `1 second`; local notifications on | Counter `0 → 1`; stored event value `08.8`; the recording also shows the local notification and subsequent `09.4` reading. |
| [Person video](person.mp4) · [setup](person-setup.png) | Signed catalog target `person`, EfficientDet-Lite2 COCO; appears for `1 second` | “Target found”; counter `1 → 2` after restarting the saved monitor with the clearer street input. This detects a category, not identity or the number of people. |
| [Reference video](reference.mp4) · [three input pictures](reference-setup.png) | Three repository reference fixtures; MediaPipe MobileNetV3 Large Image Embedder package; appears for `1 second` | “Target found”; counter `1 → 2` after restarting the saved monitor; a local trigger image appears in [history](history.png). |

The counters in the last two recordings begin at one because an earlier run already created a record. They were not reset for the gallery. The earlier person input was [Big City Life by Coverr](https://commons.wikimedia.org/wiki/File:Big_City_Life.webm), CC0 1.0; its SHA-256 is `2834fbc411cc59f06a63f9ac7ce9ba5b748b67242d1b36a4b18043a070a7966a`. That blurrier clip is not used in the final person video.

The local Room database was inspected after stopping the app: one numeric threshold event, two person appearances, two reference appearances, plus four episode-close markers. No monitor observations, event payloads or counts were inserted by a test harness.

## Capture conditions

Recorded September 21, 2026 with Community debug `0.3.0-community-preview`, Android API 36 / arm64 emulator configured with 8 GB RAM. Display capture: 540 × 1200. The production runtime code was unchanged during this work. APK SHA-256: `de1e709ce487c954af4f380ed230a0859315f402a3aa8057a220b548b503f8c0`.

The app downloaded the signed Community packages through its normal confirmation screen. The host supplied a video file to the emulator rear camera. CameraX, the actual vision runtimes, the ordinary monitor rules and local storage handled the rest. Setup and recording were driven through the Android UI.

Input preparation:

- **Numeric:** from source second 5, crop `500:350:340:0`, scale to `960:672`, pad to `1280:816` at `(160,120)`, then keep the top `1280:720`. A 40-second hold of the first frame gives time to set up the monitor, followed by the original moving footage. That hold is outside the published excerpt.
- **Person:** resize to `700:394`, place at `(80,150)` in a `1280:720` frame, with a six-second plain lead-in. The 4.52-second source clip repeats five times in the input reel; repeat boundaries are visible. It is not a continuous new street recording.
- **Reference:** scale the first fixture to `420:560`, place at `(250,80)` in a `1280:720` frame, with a five-second plain lead-in. This is an illustrated, static test target presented through the camera, not a photographed object or a new accuracy dataset.

The recorder captured the actual app at normal speed. Final videos are continuous excerpts: numeric 23 seconds, person 17 seconds, reference 12 seconds. GIFs preserve the same timing with fewer frames and colors. Editing is limited to trimming, scaling and compression; no app output is redrawn or added.

## Limits observed

These are selected demonstrations, not accuracy measurements. Earlier numeric attempts occasionally missed a decimal point or returned unavailable on a changing frame; the final setup used a manual region and confirmed the baseline. The plain input intervals produce “Unable to tell yet,” which is not evidence of a target's absence. No departure/re-entry or precise dwell-time claim is made from those intervals.

Initial emulator runs had slow startup / unresponsive UI while the host was loaded. The recorded runs used host graphics and a smaller display. These clips do not establish real-phone speed, battery use, sustained operation or unattended reliability. [Physical acceptance remains open](../DEVICE_SUPPORT.md).

Original source footage and uncut recordings are retained outside Git. The repository contains only the small public excerpts, GIFs, selected app screenshots and this provenance note.
