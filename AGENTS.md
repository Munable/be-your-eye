# Be Your Eye repository rules

This file applies to the whole repository. A deeper `AGENTS.md` may add rules
for its directory, but may not weaken these requirements.

## Authority and product scope

The four current authorities are `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`,
`docs/DEVELOPMENT.md`, and `docs/RELEASE.md`. They are written in English and
must describe the same shipped behavior. Historical evidence and frozen release
artifacts do not override them.

The production brand is **Be Your Eye**. The Chinese display name is **帮你盯**;
the Android application ID and package remain `app.beyoureyes.monitor`.
Community works locally without an account or subscription. The connected
edition keeps its existing account, entitlement, Play Billing, website billing,
assistant, voice and sync boundaries.

The product has three peer creation paths: reference images, numeric reading,
and a Catalog-backed visual target or visible phenomenon. The signed Catalog is
the only source of target IDs, aliases, display labels, capabilities and model
packages. An unknown target must fail closed; do not substitute a nearby class
or a generic model. A complete configuration can be created without a
successful preflight recognition. Testing recognition is optional.

The assistant is a short, structured, multi-turn configurator. Its only product
tool is `propose_monitor_configuration`; it may open a confirmation page but
must not create, save, start, download, bypass Catalog admission or bypass user
confirmation. Text and foreground hold-to-talk voice use the same conversation.
Community does not enable assistant, voice or cloud services.

## Architecture boundaries

- Keep the four Gradle modules: `core:domain` owns product models,
  `core:data` owns Room, settings, private media and Supabase, `core:vision`
  owns signed runtime families, and `app` owns Compose screens and lifecycle.
- `MonitoringService` owns foreground-service and CameraX ownership;
  `MonitoringSession` owns frames, observations, rules and events.
- Cloud access is limited to Supabase/Edge Functions, R2, FCM, static legal
  pages, Alibaba Cloud Model Studio, Stripe Checkout and Google Play interfaces.
  Credentials stay server-side.
- Do not add a second model registry, model-specific Android backend, scheduler,
  background monitoring mode, training/export entry point or compatibility shell.
- Camera frames and intermediate tensors remain in memory. Reference material
  and the first trigger JPEG remain in the app-private directory. Events,
  database rows, Storage and FCM must not contain media.

## Mandatory nine-locale i18n standard

The supported locale tags are `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`,
`fr`, `de`, and `pt-BR`. English is the source locale. Android resources,
website dictionaries, account/checkout copy, notifications, email templates,
assistant/ASR requests, legal pages and Catalog display labels must use these
tags. The default follows the system or browser language; the user may choose
any supported locale, and the choice survives sign-out and restart.

Every user-visible string must be a resource or dictionary key. Do not compose
sentences by concatenating translated fragments, hard-code display text in
Kotlin/Java/HTML/JavaScript, or show a server exception directly. ViewModels,
workers and services pass a message key plus typed arguments; the current locale
resolves it at display time. Dates, numbers, durations, sizes and money use the
current locale. User-entered text, saved names, recognition source text and
historical conversations are never translated automatically.

New or changed copy must update all nine locales, preserve placeholder and
plural contracts, and be reviewed for long text and accessibility labels. New
Catalog targets use a locale-keyed `labels` map signed with the Catalog; legacy
frozen artifacts are not rewritten in place.

Run `node tools/ci/check-i18n.mjs` and its test for every copy or locale change.
The check is required by `tools/ci/run-community.sh` and release builds. It
rejects missing or duplicate keys, invalid references, placeholder mismatches,
missing plural branches, unsupported locale tags and hard-coded display text.

## Safe work and evidence

- Before editing, inspect the real branch, worktrees, dirty files, processes and
  open files. Preserve unrelated changes, `.local` data, credentials and active
  previews. Never reset, clean or mass-delete shared data.
- Put large downloads, model files, build output and test copies under the
  existing project directory on `/Volumes/DevDisk/DeveloperData`; keep only
  source and frequently used runtime data in the repository checkout.
- Finish cleanup in this task: remove only disposable files created by this
  task, retain one recovery copy and the evidence needed for the conclusion,
  and do not create background cleanup jobs.
- Complete one real end-to-end path before adding abstractions or broad test
  suites. Distinguish unit/build, emulator, physical-device, hosted and human
  acceptance evidence. A lower layer cannot close a higher-layer gate.
- Update only the relevant `evidence/current/01` through `05` summary or one
  release summary. Keep unverified device, hosted and manual gates marked
  `open`.
- Do not commit secrets, service accounts, tokens, account data, device serials,
  raw camera frames or model binaries. Do not push automatically.

## Standard verification commands

```bash
bash tools/ci/run-community.sh
bash tools/release/build-community.sh --unsigned
node tools/ci/check-i18n.mjs
npm test --prefix supabase/tests
```

Hosted, physical-device, payment and store acceptance remain separate gates.
Documentation may describe a gate, but only current evidence can close it.
