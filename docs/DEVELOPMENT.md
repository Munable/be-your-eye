# Be Your Eye development standard

This document is the engineering authority for the repository. Paths, API
identifiers, resource keys, source comments and development documentation use
English. The localized README and historical release material are user-facing
or archival content and are intentionally kept in their original language.

## Working rules

Keep the four Gradle modules and signed model contracts. Inspect the current
branch, worktrees, dirty files, processes and real source paths before editing.
Preserve unrelated changes, `.local` data, credentials and active previews.
Never reset, clean, migrate by copying an old shell, or add a compatibility
layer merely to make a test pass. Finish a small real end-to-end path before
adding abstractions or broad tests.

Large downloads, model packages, test copies and build output belong in the
existing project directory under `/Volumes/DevDisk/DeveloperData`. Do not put
large temporary assets in the system disk, home directory, Downloads folder or
repository root. At task close, remove only disposable artifacts created by the
task, retain the evidence and one recovery copy, and do not create a cleanup
daemon or scheduled job.

## Product and runtime boundaries

`core:domain` is the only product-model source. `core:data` owns Room, private
media, settings, task codecs, account state and Supabase. `core:vision` owns the
signed Catalog/Manifest, preprocessing, generic runtime families and package
verification. `app` owns Navigation Compose, ViewModels, CameraX setup and
foreground-service handoff. `MonitoringService` owns FGS/CameraX lifecycle;
`MonitoringSession` owns frames, typed observations, rules and events.

The three manual paths are reference images, numeric readings and a
Catalog-backed visual target/phenomenon. A complete configuration may start
without a preflight recognition. Recognition testing is optional and shares the
same signed runtime, preprocessing, quality gate and typed observation as
continuous monitoring. No model package name, vendor or filename may control
runtime dispatch.

The assistant is a bounded multi-turn configurator. It sends only user-entered
conversation context and a Catalog capability summary to the authenticated
server function. `propose_monitor_configuration` is its only tool and has no
create/save/start/download side effect. Hold-to-talk records a bounded AAC file,
sends it to the authenticated ASR function, deletes it after the request and
never sends camera frames or reference images. Community has no cloud, assistant
or voice capability.

## Nine-locale i18n contract

The source locale is `en`; the shipped locales are:

| Tag | Native name |
| --- | --- |
| `en` | English |
| `zh-Hans` | 简体中文 |
| `zh-Hant` | 繁體中文 |
| `ja` | 日本語 |
| `ko` | 한국어 |
| `es` | Español |
| `fr` | Français |
| `de` | Deutsch |
| `pt-BR` | Português (Brasil) |

Android uses AppCompat application locales and `locales_config.xml`; the website
uses a single browser dictionary and local storage. Both expose a system/browser
choice plus the nine explicit choices. Locale matching gives Simplified Chinese
to mainland China/Singapore, Traditional Chinese to Taiwan/Hong Kong/Macau,
Brazilian Portuguese to Portuguese, and English for unsupported languages.
Android 8+ must work offline after installation, and Android 13+ system app
language settings must remain interoperable.

All Android strings, accessibility labels, notifications, workers, website
templates, account/checkout messages, legal/help pages, service errors,
assistant prompts and Catalog labels are localized. User text, saved names,
recognition source text and conversation history remain unchanged. UI state
stores a message key and typed arguments, never a translated sentence. Service
responses use stable error codes; clients translate them locally. Dates, numbers,
durations, sizes and prices use the active locale, while protocol identifiers and
provider prices keep their literal value.

Every copy change must update all nine locale dictionaries/resources and preserve
placeholder, plural and accessibility contracts. Sentence concatenation and
hard-coded display text are forbidden. The signed Catalog accepts an optional
locale-keyed `labels` map for new metadata; frozen signed artifacts are not
rewritten in place.

The mandatory check is:

```bash
node tools/ci/check-i18n.mjs
node --test tools/ci/check-i18n.test.mjs
```

It checks Android resource parity, the web registry, locale configuration,
placeholders, plurals, invalid references, unsupported tags and hard-coded
display strings across Kotlin, JavaScript, HTML, notifications and metadata.
`tools/ci/run-community.sh` and release builds run it automatically. Add a
negative fixture when changing the checker so the checker itself is proven to
fail for a missing translation or hard-coded string.

## Contracts and data

The signed Catalog is the only source of target IDs, aliases, localized labels,
capabilities and active model packages. Unknown targets, unknown fields, invalid
ROI, mismatched capability/package identities and unsupported modes fail closed.
No generic or nearby-model fallback is allowed. Frames and intermediate tensors
stay in memory; reference material and one trigger JPEG stay in the app-private
directory. Events, Supabase rows, Storage and FCM do not carry media.

The connected edition uses the existing root entitlement gate. Website orders
are one-time 30-day Stripe purchases, Play remains the subscription channel, and
the first Checkout language is stored with the order so retries keep the same
Stripe locale. Supabase functions return stable codes. Auth emails use the
account's last known locale, falling back to English.

Forward-only migrations are required for hosted schema changes. Do not edit a
migration already applied to the hosted project. Do not modify frozen signed
Manifests or model bytes; issue a new signed metadata version when a contract
changes.

## Verification layers

Run the smallest affected unit/contract test first, then the complete local
Community checks. Keep these evidence layers separate:

1. static policy, schema, signature, unit, lint, build and SBOM;
2. exact model package contract, activation and inference smoke;
3. signed runtime replay on the fixed external slice;
4. physical CameraX/device screen replay;
5. hosted assistant, billing, auth, cloud and dual-device checks;
6. human acceptance of the complete user journey.

A build, emulator, fixture, dashboard or UI test cannot close a physical-device,
hosted or human gate. Current conclusions belong in `evidence/current/01` through
`05`; unresolved gates remain `open`.

## Required local entry points

```bash
bash tools/ci/run-community.sh
bash tools/release/build-community.sh --unsigned
bash tools/ci/run-local.sh
npm test --prefix supabase/tests
```

The website Auth regression is `node --test tools/ci/website-auth.test.mjs`.
The website billing function is tested with the pinned Deno command recorded in
the repository. Hosted secrets, real payments, Play purchases, SMTP, App Links,
ASR and natural-scene device results must be recorded as their own evidence and
must never be inferred from a local build.
