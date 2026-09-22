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

Follow [ARCHITECTURE](ARCHITECTURE.md). Keep the three peer manual creation paths,
optional recognition testing and fail-closed signed Catalog admission. No
maintainer service, account, billing, cloud assistant, ASR or FCM may be added.
Do not put a second registry, vendor-specific backend, training/export tool,
scheduler or background camera mode beside the existing runtime.

Paired alerts are optional encrypted text through a user-selected relay. Keep
camera monitoring and message receiving as separate lifecycles. Do not leak
pairing secrets, monitor names, readings or ciphertext to diagnostic logs.
Never describe relay acceptance as confirmed delivery.

## Nine-locale i18n contract

English is the source. Ship `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`,
`de`, `pt-BR` together. Every visible string and accessibility label belongs in
Android resources. Preserve placeholder and plural contracts. Format dates,
numbers and sizes using the selected locale. Never concatenate translated
sentence fragments or display raw exceptions. User content is not automatically
translated. A language choice persists across app restarts.

New Catalog target labels use a signed locale-keyed map with all nine languages.
Do not modify old signed releases in place. Run `node tools/ci/check-i18n.mjs`
and `node --test tools/ci/check-i18n.test.mjs` for every copy change. Check long
text and accessibility on a rendered Android screen.

## Verification and delivery

Use [BUILD](community/BUILD.md) for JDK/SDK requirements and independent builds.
`bash tools/ci/run-community.sh` runs repository policy, localization, model
schema/signature tests, secret hygiene, Kotlin tests, lint and a debug build.
`bash tools/release/build-community.sh --unsigned` freezes a committed candidate
in the explicit external output directory. Release signing keys stay private.

Test the actual path being changed: download and native self-test for a model,
real encrypted publication and reception for pairing, camera input through a
real runtime for recognition. Unit, emulator, physical-device and human results
are separate. Update the relevant existing evidence summary; leave unverified
gates open. GitHub model releases are source distributions, not proof that every
scene or phone is supported.

Clean task-created disposable downloads and duplicate build copies before
handoff. Retain exact sources, licenses, necessary evidence and one recovery
copy. Commit only verified task changes; never include another task’s edits.
Do not push unless the user has authorized publication.
