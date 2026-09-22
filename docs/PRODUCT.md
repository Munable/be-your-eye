# Be Your Eye product scope

Be Your Eye helps a user turn a monitoring intention into a task. The user
places a portrait rear-camera phone on continuous power and lets it wait for a
target. The production English brand is **Be Your Eye**; **帮你盯** is the
Chinese display name. `app.beyoureyes.monitor` remains the Android technical
identity.

## Independent Community edition

Community creates tasks, downloads signed models, runs the camera, sends local
notifications and keeps local history without an account, subscription or API key.
The project operates no backend and offers no cloud AI, voice assistant, billing
or hosted sync. Models are distributed through this repository’s GitHub Releases.
After the first verified download, local recognition works offline without a
service lease. Optional paired alerts use a user-selected third-party relay.

The three peer creation paths are reference images, numeric reading and a
Catalog-backed visual target.

## Creation paths

### Reference images

The user imports 3–20 images of one target. The app rotates, scales and stores
them privately; only decode failures and definite duplicates are rejected. Every
visible image participates in the reference prototype. A complete configuration
can start without a successful preflight. Optional testing recognition uses the
same signed runtime and never creates an event or history row.

### Numeric reading

Without a manual region, every frame searches the full visible image for a
readable number row and chooses the nearest candidate to the frame centre. More
than one number does not block the path. The user may drag from the top-left to
the bottom-right to define a strict region; the two handles resize it and a drag
outside the region redraws it. A stable reading can become the baseline. If no
stable value is available, the task may start as **baseline pending** and the
first stable value later asks the user for confirmation before conditions become
active. Missing frames, poor quality and inference errors are `unavailable`, not
absence or a normal reading.

### Text-described visual targets

The user describes what should be visible, for example “notify me when an apple
appears”. Matching uses only the signed Catalog's exact target IDs, aliases,
localized labels, capability and active package. No nearby class, generic model
or unapproved package is a fallback. After the exact target and condition are
selected, the task can start immediately. Optional testing recognition remains
available from setup and details.

## Testing and continuous monitoring

Monitoring runs only while the app is visible. Moving to another app, the system
desktop or the lock screen stops it and releases CameraX; returning does not
restart it. Navigation inside the app and the visible black-screen option may
continue monitoring. Black screen reduces display power only.

Testing recognition and continuous monitoring share the exact signed model
package, runtime, preprocessing, quality gate and typed `Observation`. Testing
uses the signed minimum interval, gives immediate candidate feedback, requires
at least two valid observations across 400 ms for a stable result, and treats
`unavailable` as missing evidence. Severe thermal state slows processing within
the signed bounds; critical state releases the camera and stops safely.

Continuous monitoring uses the task's persisted interval and thermal policy. A
condition must remain true for its configured one-to-60-second duration. An
appearance episode closes only after five continuous seconds without the target;
one episode produces one event. The app keeps target-present and target-absent
facts separate, does not turn uncertainty into absence, and keeps the same task
when a baseline is later confirmed.

## Records and paired alerts

The run page shows local state and records for the selected monitor. A first
trigger may save one private JPEG. Camera frames and tensors stay in memory;
reference material and trigger photos remain on the source phone.

A user may pair two or more phones by QR code or a copied pairing code. They
share one random topic and encryption key. Monitoring events with notifications
enabled send encrypted text: monitor name, condition, time and optional reading.
The receiving phone shows a local notification and stores a bounded text inbox.
Images, live video, configuration and remote camera control are not transmitted.

The default relay is the independent free public ntfy service; users can choose
another compatible HTTPS relay. No maintainer account, server, API key, paid
subscription or paid fallback is needed. Public service quotas and availability
apply. The receiver must explicitly enable persistent receiving and allow
notifications; a visible service notification offers Stop. Android battery
restrictions or a killed app can interrupt delivery. This is best-effort
messaging, not a guaranteed alarm. To remove a group member, form a new group
with a new pairing code. Existing members share the same authority.

## Nine-locale user experience

The shipped locales are `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`, `de`
and `pt-BR`. The default follows the system language. A language
picker is available from About. The choice survives restart, and
Android 13 system app-language settings interoperate with Android 8+ fallback.

Matching maps mainland China and Singapore to Simplified Chinese, Taiwan, Hong
Kong and Macau to Traditional Chinese, Portuguese to Brazilian Portuguese, and
unsupported languages to English. Dates, numbers, durations, file sizes and
file sizes use the active locale. Stable protocol fields retain their literal meaning. User-entered text, saved names, source
recognition text are never translated automatically.

Every Android string, accessibility label, notification, worker message and new
Catalog display label follows the shared resource contract. `check-i18n` rejects
missing keys, placeholder drift and hard-coded display copy. There is no hosted
website, account, checkout, email or assistant locale surface.

## Product limits

The first release targets fixed-camera personal and small-workplace use. It does
not promise identity recognition, medical, industrial, infant or elder safety
monitoring. Community supports Android API 26+, arm64 and the signed device
profile; the current model requirement is eight GB RAM and no GMS dependency.
Evidence for physical devices, public-relay delivery and human acceptance
is kept separate from builds, fixtures and emulator results.
