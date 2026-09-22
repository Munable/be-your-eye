# Be Your Eye product scope

Be Your Eye helps a user turn a monitoring intention into a task. The user
places a portrait rear-camera phone on continuous power and lets it wait for a
target. The production English brand is **Be Your Eye**; **帮你盯** is the
Chinese display name. `app.beyoureyes.monitor` remains the Android technical
identity.

## Community and connected editions

Community is a local tool. It creates tasks, downloads signed models, runs the
camera, sends local notifications and keeps local history without an account or
subscription. It exposes three peer manual paths: reference images, numeric reading,
and a text-described visual target/phenomenon. Community does not show
login, purchase, AI assistant, voice, cross-device alerts or cloud sync.

The connected edition retains its account and entitlement gate. When signed out
or without `ProductAccessState.Granted`, a subscription wall blocks product
capabilities while account management, restore, legal pages and read-only local
history stay reachable. Google Play keeps the existing subscription channel.
The website offers a one-time Stripe purchase for 30 days with no automatic
renewal and one user-claimed three-day trial after email confirmation. Both
channels share the root entitlement boundary.

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

The DeepSeek assistant follows the same rules. It receives all three routes and the
complete signed capability summary, asks only for material ambiguity, and uses
the single `propose_monitor_configuration` tool to open a confirmation page.
The tool has no create, save, start, download or notification side effect. It
defaults an omitted duration to one second and accepts an explicit integer from
one to 60 seconds. The user confirms through the original setup path.

Text input and foreground hold-to-talk belong to the same conversation. Only a
long press requests the microphone and records a bounded AAC file. The
authenticated Qwen ASR function receives that file, returns text and deletes the
cache immediately. Camera frames, reference images, trigger images and history
never enter assistant or ASR requests. Community disables both routes.

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

## Records, privacy and account boundaries

The run page shows the current state, local record count and the latest record.
Details and recent records are filtered by the exact monitor ID. A first trigger
may save one private JPEG; media is never put in an Event payload, Supabase row,
Storage object or FCM message. A same-account device may request a temporary
end-to-end-encrypted copy through Realtime Broadcast, limited to 720 px and
120 KiB; the viewer keeps it only in a private temporary cache.

Ordinary camera frames and intermediate tensors stay in memory. Sign-out,
account deletion or remote-event removal clears temporary media and leases.
Diagnostics contain no target names, images, readings, raw recognition text,
tokens or device serials. Provider purchase tokens are transient; only their
hash and provider state may be persisted.

## Nine-locale user experience

The shipped locales are `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`, `de`
and `pt-BR`. The default follows the system or browser language. A language
picker is available from Community About and the connected Account surface even
before sign-in or entitlement. The choice survives restart and sign-out, and
Android 13 system app-language settings interoperate with Android 8+ fallback.

Matching maps mainland China and Singapore to Simplified Chinese, Taiwan, Hong
Kong and Macau to Traditional Chinese, Portuguese to Brazilian Portuguese, and
unsupported languages to English. Dates, numbers, durations, file sizes and
prices use the active locale. Stable protocol fields and provider-returned
prices retain their literal meaning. User-entered text, saved names, source
recognition text and conversation history are never translated automatically.

Every Android string, accessibility label, notification, worker message, web
template, legal/help page, account/checkout message, assistant response and
Catalog display label follows the shared resource/dictionary contract. ViewModels
and services carry message keys plus arguments; clients localize stable server
error codes. `check-i18n` blocks missing keys, placeholder drift and hard-coded
display copy.

## Product limits

The first release targets fixed-camera personal and small-workplace use. It does
not promise identity recognition, medical, industrial, infant or elder safety
monitoring. Community supports Android API 26+, arm64 and the signed device
profile; the current model requirement is eight GB RAM and no GMS dependency.
Evidence for physical devices, hosted services, payments and human acceptance
is kept separate from builds, fixtures and emulator results.
