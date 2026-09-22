# Be Your Eye release gates

This document defines release gates. A command, build artifact, emulator, mock,
dashboard or recorded frame is evidence only for its own layer. It cannot close
a physical-device, hosted-production, payment, store or human-acceptance gate.
Current results belong in `evidence/current/01` through `05`; unresolved gates
remain `open`.

## Community Preview

Before a Community preview, verify:

1. Apache-2.0 first-party source, third-party notices, signed Catalog and exact
   Manifest/package hashes are present;
2. API 26+ arm64 packaging, no GMS dependency, eight-GB model boundary,
   offline reuse of an installed package and stale-Catalog fail-closed behavior;
3. all three manual creation paths, the optional recognition test, first-frame
   start, event, record, stop, delete and failure recovery on the fixed device;
4. no account, subscription, AI, voice, cloud, FCM or cross-device capability
   is reachable from the Community build;
5. the nine-locale resources, app-language picker, notifications and offline
   switching pass `check-i18n` and the Android 8+/Android 13+ paths;
6. no secret, raw frame, device serial, user data or model binary is committed.

Community release output is unsigned unless the release workflow supplies the
approved signing environment. The model package is not copied into Git.

## Connected channels

The connected Android package keeps the existing Play subscription and root
entitlement gate. The website package uses Stripe for one-time 30-day access and
an optional three-day trial; it never auto-renews. Both paths use the same
entitlement decision for app capabilities. Account, restore, manage-subscription,
legal, delete-account and read-only history remain reachable while locked.

The assistant and voice routes require a signed-in account and active entitlement.
The assistant's only tool is `propose_monitor_configuration`. A hosted result
must prove that the proposal opens a confirmation page without creating, saving,
starting or downloading. Voice must prove hold-to-talk, bounded AAC, normalized
ASR locale and cache deletion. Hosted failures remain open until a real response
and privacy log boundary are observed.

## Candidate identity and artifact gates

Every candidate records the exact source commit, build variant, version code,
Catalog version, active package IDs, Manifest hashes, signing identity and
rollback candidate outside this document. Existing frozen signed artifacts are
never modified. A contract change gets a new forward migration and a newly
signed metadata version; model weights are reused only when their hash and
license remain exact.

The candidate must pass the schema and signature validators, package license and
source review, runtime-family dispatch tests, model activation smoke and the
repository policy. Catalog entries without an executable active package are
forbidden. Identity by package name, vendor, filename or model artifact is a
release blocker.

## Required verification sequence

Run in order and record each layer separately:

1. `bash tools/ci/run-community.sh`, unit tests, schema/signature checks, lint,
   compile, SBOM and `node tools/ci/check-i18n.mjs`;
2. exact package activation and a minimal signed-runtime inference smoke;
3. external replay using the exact active package and signed preprocessing;
4. physical CameraX replay covering all three creation paths, first frame,
   quality/unavailable, event, notification, records, stop and restart;
5. Android API compatibility, locale switching, large text, offline switching,
   app-language settings and draft preservation;
6. hosted Auth, assistant, ASR, website billing, Stripe idempotency, entitlement,
   notification and two-device media-relay checks;
7. human review of the complete download, setup, use, payment/refund and delete
   journeys where those channels are enabled.

The full nine-locale path must cover first system-language selection, manual
selection, restart persistence, returning to system, notifications, assistant
responses, ASR language, plural/placeholder checks, long copy, date/number/
decimal formatting, unsupported-language English fallback, Chinese script
routing and Portuguese routing. Website checks cover first visit, refresh,
cross-page storage, storage failure, Auth callback and Checkout return.

## Internal and Commercial gates

Internal builds use the existing `.internal` identity and test entitlement. They
must not initiate production Play purchases or show Play management for an
unavailable provider. A new Auth account alone never grants product access.

Commercial or store release additionally requires per-package license and
redistribution review, exact Catalog target/capability/package binding, current
physical-device acceptance, privacy and support pages, signed APK/AAB, SBOM and
vulnerability scan. Play releases also require Data Safety and store assets. The
website route additionally requires an independent Stripe merchant, dedicated
Price/webhook configuration, real payment/refund/expiry and delete-account
checks, a self-held signing key and matching Digital Asset Links.

## Hosted configuration gates

Before hosted release, configure and test the exact HTTPS `/auth/callback` in
Supabase allowlists, verified SMTP and one-time/expired password links, Android
App Links using the Play app-signing certificate, the scoped Model Studio key,
Supabase function secrets, Play product/base plans/offers and RTDN OIDC push.
Purchase tokens, service keys and raw provider payloads never enter source,
logs or evidence.

Website billing must run the forward migration before the function update.
Checkout accepts only the configured origin, product and one-time amount;
client-supplied amount or duration is rejected. The first order locale is stored
and reused for Stripe retries. Webhook signature verification, duplicate-event
idempotency, full refund revocation, account isolation and deletion must be
observed with real hosted data.

## Release conclusion

`evidence/current/05-release.json` is the sole current release conclusion. Use
“release ready” only when the corresponding device, hosted, payment and human
gates are closed with current evidence. Do not infer readiness from a green
build, a dashboard setting, an old screenshot or an unconfirmed deployment.
