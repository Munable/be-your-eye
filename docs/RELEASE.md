# Be Your Eye release gates

A build, emulator, public URL or screenshot proves only its own layer. Current
results belong in `evidence/current/01` through `05`; unresolved device, human
and long-running reliability gates remain `open`.

## Open-source Community preview

1. Keep Apache-2.0 first-party source, dependency notices and exact model sources,
   dataset disclosures, licenses, signed metadata and artifact checksums.
2. Verify API 26+ arm64 packaging, the signed eight-GB recognition constraint,
   no GMS/account/subscription dependency and offline reuse. A Community release
   must remain installable without renewable maintainer metadata.
3. Test reference images, numeric reading and Catalog visual targets, including
   first frame, optional testing, event, local notification, history, stop,
   delete and failure recovery. Unsupported targets fail closed.
4. Test QR/code pairing, encrypted real relay publication/reception, tamper and
   replay rejection, duplicate suppression, disconnect/reconnect, unpairing and
   notification permission. Relay availability and Android battery behavior are
   explicit limits. The project must not create a hosted or paid dependency.
5. Validate all nine locales, persisted selection, long text, notifications and
   accessibility. Test Android 8+ fallback and Android 13+ system selection
   separately from compile-time checks.
6. Run Community CI and a release build, inspect the merged manifest/dependency
   inventory, scan secrets, and keep signing keys and personal data outside Git.

## Distribution

Git contains code, metadata and source/license records. GitHub Releases contains
verified model weights and associated license/checksum files; do not use Git LFS
for the default model distribution. Signed model metadata is immutable. New
URLs, class labels, runtime contracts or weights require new signed metadata.
Private keys never enter the APK, repository, public CI or logs.

A model-only release must say it is model data and must not imply an installable
APK or completed physical acceptance. A runnable official APK needs a stable
release signing certificate, exact source commit and model identities, APK hash,
license notices and a tested installation/upgrade path. Never label a debug
signature as official. An unsigned candidate is not installable until signed.
Record candidate identities in evidence, not in these authority documents.

## Acceptance and service retirement

Real recognition on the intended phone and scene, sustained operation, missed
and false events, thermal behavior and human use remain separate from replay
clips. Selected demo clips must state their input source and test environment.
No identity recognition or safety-alarm claim is permitted without its own
validated product scope.

There is no connected/paid edition in the current source. Old hosted/payment
results are historical, not current release gates. Removing service code does
not cancel old resource billing: verify each project-specific resource and
subscription separately, preserve necessary user data and shared resources,
and record what was actually disabled. Never claim a bill has stopped without
provider confirmation.
