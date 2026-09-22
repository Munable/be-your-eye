# Send an alert to another phone

[中文](PAIRING.zh-CN.md) · [User guide](../../USER_MANUAL.md) · [Build and install](BUILD.md)

**Phone A watches. Phone B tells you when something happens.** Both phones need
the Community app and internet for paired alerts. Only the monitoring phone
needs the recognition model and its supported hardware.

The app requires Android 8+ and arm64. The current recognition profiles require 8 GB RAM on the monitoring phone; receiving text alone does not load these models. The recorded receiver test used a 4 GB emulator, not a physical-phone compatibility test. There is no public APK yet, and physical pairing and sustained delivery remain unverified. See [device scope](DEVICE_SUPPORT.md).

| On phone A | On phone B |
| --- | --- |
| Open **About → Paired phones → Create a pairing group** | Open **About → Paired phones → Scan pairing QR** |
| Confirm the relay, then show the QR code | Scan A’s code and confirm the relay |
| Keep the QR code private | Allow notifications and tap **Start receiving** |
| Tap **Send a test alert** | Check the new alert in the inbox and notification shade |
| Enable notifications for a monitor and start it | Keep receiving enabled; follow the battery-settings hint |

The copied pairing code works if scanning is inconvenient. Stop monitoring before
opening the scanner because both features use the camera. More phones can join
the same group. Pairing does not need a Be Your Eye account, API key, port
forwarding or a maintainer server.

## What travels

A small **encrypted text message** contains the monitor name, event kind, time
and, for a reading event, the displayed value. No photos, thumbnails, live video
or remote camera commands are sent. The receiving inbox holds the latest 200
alerts. The monitoring phone keeps its own images and full local history.

The QR code contains the group encryption key. Share it only with people you
trust. Every member can read and send group alerts. To exclude one member,
unpair and create a new group, then re-pair only the remaining phones. Unpairing
a phone does not erase a key someone else already copied.

## Free relay, explicit limits

The default is [ntfy.sh](https://ntfy.sh), an independent public service, not a
Be Your Eye server. Publishing is anonymous. The app encrypts with AES-256-GCM
before sending; the operator can still see IP addresses, a random topic, timing
and message size. You may choose another compatible anonymous HTTPS ntfy origin
while creating a group. The project provides no hosting, account, API key,
paid subscription or automatic paid fallback.

At the time of implementation, ntfy.sh’s [published free limits](https://docs.ntfy.sh/publish/)
include 250 messages per day and a 4,096-byte message limit. Shared IP addresses
can share quotas. Limits, retention and availability can change. No service is
promised permanently free or always available. Use a small number of meaningful
alerts, not a continuous stream of readings.

The app shows a persistent notification while receiving. Android battery
restrictions, a force-stopped app, no network or an unavailable/rate-limited relay
can delay or lose messages. The receiver requests up to 24 hours of available
cached messages on reconnect; this cannot recover messages the relay never
accepted or no longer retains. After a restart or force-stop, open the app and
start receiving again. “Sent” means the relay accepted the message: check the
other phone to confirm actual delivery.

Monitoring and receiving have different lifecycles: **locking the monitoring
phone stops its camera**; a separately started text receiver may continue in the
background. Neither feature is a safety alarm.

Protocol details and trust boundaries: [ARCHITECTURE](../ARCHITECTURE.md).

The inbox retains 200 alerts. Receipt IDs are bounded to 2,000; at capacity, a persisted timestamp floor rejects older delayed messages rather than displaying an already handled alert again. Restarting the app preserves this floor. This is another reason to treat cross-phone alerts as best effort.
