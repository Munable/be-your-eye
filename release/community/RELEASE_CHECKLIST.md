# Community release review

Source publication and an installable app release have different completion criteria. Actual results belong to `evidence/current/05-release.json`.

## Source publication

- The project owner authorized first-party Apache-2.0 licensing and public source publication on 2026-09-21. Third-party works keep their licenses; the code license does not grant trademark permission or imply publisher endorsement.
- Publish a reviewed snapshot to `Munable/be-your-eye`, without importing the private repository’s history. The old private repository remains private. Future development belongs in the public repository.
- The export excludes private execution evidence, the supplied internal handoff, store media and connected production configuration. Current Community results replace historical evidence.
- Launcher vectors and geometric fixtures are project-authored. Two retained home illustrations have project-generation provenance. The untraced object illustration and apple photo are removed; object replay requires a tester-supplied image. Test-source attribution remains in the repository.
- Check the exported tree with repository policy, model/service contracts, Android units, lint/build and a secret scanner. Review findings individually; a scanner pass is bounded evidence, not a guarantee.
- Public CI uses pinned actions, read-only permissions and no maintainer secrets. Security reports use GitHub private vulnerability reporting.

## APK release — open

The source preview is not a stable binary release. Keep these gates open until the exact distribution has evidence:

- Real-phone installation, model consent/cancel/resume, offline restart, foreground transitions, same-signature saved-data upgrade.
- Natural numeric displays, uncertainty behavior, representative sustained runs and independent use.
- A refreshed signed model catalog and durable metadata delivery. Never silently bypass freshness, signature or license checks.
- Source tag, version, signed APK hash/certificate, model identities, SBOM, notices and download bound to one candidate.
- Real camera/alert demonstration. Generated art or emulator screenshots are not substitutes.

The preceding local candidate (`0.3.0-community-preview`, versionCode 26) passed 560 Android unit tests, clean-source debug/unsigned-release builds, both lints, policy/model/service checks and a signed emulator launch. Five SBOMs were generated; Android had 256 components and the scanned inventories had no known OSV findings. The eight model artifacts (98,762,965 bytes) matched their signed sizes and hashes after independent public download.

Physical installation returned -99; natural scenes, long runs and independent users remain unverified. Later source presentation and asset cleanup do not retroactively change that signed APK or its evidence. No APK is published as part of source opening.

Website deployment, promotional posts, spending and optional remote-service experiments are outside this source-publication batch. Keep the existing legal/auth routes intact.
