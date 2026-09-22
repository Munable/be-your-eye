# Be Your Eye architecture

## Module boundaries

The repository keeps four Gradle modules:

- `core:domain` is the single source of product models, monitor targets,
  rules, typed observations, events and assistant proposals.
- `core:data` owns Room, private media, settings, task codecs, account state,
  Supabase synchronization and the assistant gateway.
- `core:vision` owns signed Catalog/Manifest decoding, package verification,
  preprocessing, generic LiteRT/ONNX runtime families and adapters.
- `app` owns Navigation Compose, Lifecycle ViewModels, CameraX setup, foreground
  service handoff and user-facing screens.

`AppContainer` is the assembly point. Do not add another dependency-injection
layer, model registry, model-specific Android backend or independent app server.
DeepSeek, Qwen ASR, Stripe and Google Play are reached through Supabase Edge
Functions; credentials never enter the APK.

## Community assembly

Community variants use the same four modules with `BuildChannel.COMMUNITY` and a
separate package. Local-use access decides only local capabilities; connected
`ProductAccessState` still decides online capabilities. Community compiles cloud,
AI, Billing and FCM integrations out of the product path and performs no account,
lease or network work.

Signed Community metadata may ship with the APK, but the same public key,
strict decoder, hash checks, license checks and runtime lease rules apply. A
stale Catalog may only resolve an already-installed exact package; a new install
must use a fresh Catalog. Frozen signatures and package bytes are never rewritten.

## Domain and transport models

`MonitorKind` covers reference images, numeric readings and Catalog visual
targets. `MonitorTarget.ObjectClass` stores a stable target ID, the legacy source
labels, a locale-keyed `labels` map when present, the operational capability and
the exact package selection. Unknown fields, target IDs, modes, ROIs and
capability/package relationships fail closed during write, restore and start.

The shared task contract uses `reference_images`, `none` or `object_detection`.
Cloud projection uses `visual_target` with the same finite object-target shape;
there is no second task type or model registry. New signed metadata may include
localized Catalog labels for all nine supported locales. Legacy signed metadata
remains readable only because frozen artifacts cannot be changed in place.

## Assistant and voice

The assistant request contains authenticated user conversation context and a
signed Catalog capability summary. The server function normalizes the requested
locale to `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`, `de` or `pt-BR` and
instructs the provider to answer in that locale unless the user explicitly asks
for another language. The only tool is `propose_monitor_configuration`; strict
decoding rejects unknown fields, unknown enums, Catalog-outside identities and
provider claims of execution.

Voice is foreground hold-to-talk only. The app bounds AAC recording, sends it to
the authenticated ASR function with the normalized language parameter and
deletes the cache after success, failure or cancellation. No camera frame,
reference image, trigger image, history or transcript is stored by the product.

## Model and Catalog contracts

Runtime dispatch is determined only by the signed Manifest's runtime family,
preprocess ID, adapter contract, tensor roles and package metadata:

- `similarity_match_v1` handles reference-image targets;
- `reading_pipeline_v1` handles structured readings;
- `object_detection_v1` handles finite Catalog visual targets.

The Catalog is the only model list. Each operational capability binds one recipe,
model card and at least one active package, together with target IDs, localized
labels, aliases, license, source, hash, device constraints and evidence. A
missing exact match fails closed; a nearby class or generic model is never used.
No package contains billing tiers, invented accuracy or release status. Frames,
poor quality, tensor failures, preprocessing failures and adapter failures all
produce `unavailable`.

## Camera and monitoring lifecycle

Setup previews and continuous monitoring share camera orientation, signed model,
quality gate, preprocessing and typed observations. They differ only in frame
cadence and rule consumption. Preview uses the signed minimum interval and gives
candidate feedback; severe thermal state slows within signed bounds; critical
state clears the old result and releases CameraX. Continuous monitoring uses the
persisted task interval; critical state produces `unavailable`, stops safely and
does not auto-restart.

The app writes a complete task atomically before starting the existing service.
Numeric baseline-pending tasks remain the same task when a later stable reading
is confirmed. `MainActivity.onStop` (except configuration recreation) and
`onTaskRemoved` stop the service and release the camera. Navigation inside the
visible app and the visible black-screen page may continue. One monitor runs at a
time.

`MonitoringService` owns FGS and CameraX ownership. `MonitoringSession` owns
frames, observations, rules, episode de-duplication and events. The appearance
rule uses five seconds of confirmed absence to close an episode; present and
absent facts remain separate. The configured one-to-60-second duration is a
trigger duration, never a scheduler.

## Account, entitlement and App Link

Connected navigation, model download/run, AI, voice, FGS and cloud activation
consume one root `ProductAccessState`. The FGS rechecks it at least every five
seconds. Supabase Auth handles sign-in and one-time password recovery; only the
configured HTTPS `/auth/callback` App Link and `type=recovery` fragment are
accepted. Provider purchase tokens are hashed before persistence.

Play validates the single `be_your_eye_pro` subscription and its monthly/annual
plans. Website billing uses a separate Stripe order ledger but grants the same
root entitlement. Website Checkout stores the first selected locale on the order
and reuses it for idempotent retries. Supabase functions return stable error
codes. Auth email templates use the account's latest locale and fall back to
English.

## Data and privacy boundaries

Camera frames and intermediate tensors stay in memory. Reference material and
the first trigger JPEG stay in the source device's private directory. Supabase
stores only account/task/event facts, cursors and minimal entitlement state.
FCM carries event cursor metadata, never media. Same-account trigger-image
viewing uses a temporary end-to-end-encrypted Realtime relay capped at 720 px and
120 KiB; the viewer cache is private and short-lived.

Diagnostics never record names, images, raw target text, readings, transcripts,
tokens, serials or provider payloads. No assistant transcript or ASR audio table
is added. Account deletion and sign-out clear leases, temporary media and local
remote-event caches according to the existing boundary.

## Nine-locale implementation

Android uses AppCompat application locales, `locales_config.xml` and resources
for `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`, `de` and `pt-BR`. The web
uses one dictionary, `navigator.languages`, a query override and device/browser
storage. System matching handles Chinese script/region and maps Portuguese to
Brazilian Portuguese. The language picker is reachable without entitlement.

Every user-facing string is a resource or dictionary key. ViewModels, workers,
notifications and services pass message keys and arguments; no exception text or
sentence concatenation is displayed. The `tools/ci/check-i18n.mjs` policy scans
Kotlin, JavaScript, HTML, notification resources and signed metadata and is run
by Community CI and release builds.

## Explicit exclusions

The architecture has no side-effectful agent tool, independent model router,
second model registry, schedule/calendar subsystem, background-monitoring mode,
training/fine-tuning/export entry point or model-specific Android backend.
Current device, hosted, payment and human results live in `evidence/current/`,
not in this architecture document.
