# Be Your Eye architecture

## Four modules

| Module | Responsibility |
| --- | --- |
| `core:domain` | Monitor targets, rules, observations and events |
| `core:data` | Room, private media, settings, task codecs, signed metadata and model delivery |
| `core:vision` | Manifest runtime contracts, preprocessing, generic LiteRT/ONNX families and adapters |
| `app` | Compose, ViewModels, CameraX lifecycle, local notifications and encrypted paired alerts |

`AppContainer` assembles these components. There is no account, entitlement,
billing, cloud assistant, ASR, FCM or project-operated backend. An optional
third-party relay handles encrypted text; it never performs recognition.

## Signed model distribution

The signed Catalog is the only list of capabilities, targets and packages.
Ed25519 and RFC 8785 bind the Catalog and each Manifest. Artifact byte counts,
SHA-256, source licenses, IO contracts and device constraints are checked before
installation. A missing exact target fails closed, without a nearby-class or
unapproved-model fallback. Runtime dispatch depends on signed runtime family,
preprocess ID, tensor roles and adapter contract, never a vendor or filename.

Community bundles signed metadata and obtains weights from GitHub Releases.
The downloader accepts a single HTTPS hop from a GitHub release download URL to
`release-assets.githubusercontent.com`; other redirects fail. Exact bytes are
verified after download and again when installed packages are opened. A
Community Catalog is an immutable release, with no renewable seven-day lease.
Actual license deadlines and all signature/hash checks remain enforced.
Independent distributors can mirror artifacts and sign their own metadata with
their own keys; the app pins public keys, never private signing material.

## Camera lifecycle

`MonitoringService` owns foreground-service and CameraX ownership;
`MonitoringSession` owns frames, observations, rules, episodes and events.
One monitor runs at a time, while the app is visible. Leaving the app, locking
or removing its task stops the camera. Returning does not restart it. The
in-app dark-screen mode may continue monitoring because the app remains visible.
Critical thermal state stops safely. Missing/poor frames and inference errors
produce `unavailable`, never target absence or a normal reading.

Testing recognition shares the signed runtime and preprocessing with monitoring,
but cannot create events. A complete monitor can be saved and started without a
successful test. Numeric baseline-pending tasks retain their identity when a
later stable reading is confirmed. Domain rule timing remains independent of
UI frame cadence.

## Paired text alerts

Pairing creates a random 192-bit topic and 256-bit shared secret. A QR code (or
copied code) carries the HTTPS relay origin, topic and secret. Joining shows the
relay and privacy implications before confirmation. The key never goes to the
relay. All devices in a group are trusted equally; there is no individual member
revocation. To exclude a device, create a new group and re-pair the others.

A local event with notifications enabled queues an AES-256-GCM message through
WorkManager. Each encryption uses a fresh 96-bit nonce and binds the protocol
version/topic as authenticated data. Only event ID, sender ID, time, event kind,
monitor name and optional displayed reading are inside the encrypted payload.
There are no images, thumbnails, camera streams, account records or remote
commands. Network work is bounded and retries transient failures. Sending is
best effort; an accepted relay response does not prove delivery to another phone.

The receiver is a separately user-started `remoteMessaging` foreground service.
It reads the relay's HTTPS JSON stream, authenticates messages, rejects stale or
future messages, ignores its own messages and deduplicates event IDs. A bounded
private inbox persists before a local notification is shown. It can reconnect
and ask for up to 24 hours of cached messages, subject to the relay's retention.
It does not own a camera, start monitoring, run at boot or silently restart after
being killed. Battery restrictions, network failures and relay quotas can delay
or lose alerts. No paid fallback is configured.

The default `ntfy.sh` is an independent public service. Users may select another
compatible anonymous HTTPS origin. This is relay messaging, not direct P2P or a
maintainer-hosted service. Relay operators still observe IP addresses, random
topics, timing and ciphertext size. Pairing secrets and inbox data are private
and excluded from Android backup.

## Local data and language

Ordinary frames and tensors stay in memory. Reference images and the first
trigger JPEG stay in app-private storage. Legacy Room tables are retained for
safe database compatibility, but no cloud synchronization path uses them.
Diagnostics exclude names, readings, images, pairing codes and keys, raw
recognition text, tokens and device serials.

Android AppCompat locales and resources support `en`, `zh-Hans`, `zh-Hant`, `ja`,
`ko`, `es`, `fr`, `de`, `pt-BR`. System language is the default; a persisted picker
is available in About. New Catalog class maps sign all nine labels. Services and
screens resolve resource keys using the current locale. User-entered names and
recognized text remain literal. No raw exception is presented as UI copy.
