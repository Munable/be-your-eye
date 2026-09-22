# Community release review

The public repository is the active source. Source publication, a downloadable developer build and a stable app release have separate acceptance criteria. Record current results in `evidence/current/05-release.json`.

## Source and models

- First-party code is Apache-2.0. Model publishers and demo creators retain their own licenses; no endorsement or trademark permission is implied.
- No private repository history, credentials, account exports, camera data or signing keys belong in public Git.
- Cloud AI, voice transcription, accounts, subscriptions and maintainer-hosted services are retired. Community is the only shipped product path.
- GitHub `models-v1` contains the exact original weights, signed metadata, license reviews, source/data disclosures and checksums. Never overwrite a published signed release; publish a new version for changed bytes.
- Every Catalog target has labels for all nine supported locales. Unknown targets fail closed.
- Public CI uses pinned actions, read-only permissions and no maintainer secrets. Check Android units, lint/build, Catalog contracts, i18n, secret hygiene and source cleanliness before tagging a candidate.

## App acceptance

Keep a gate open until the exact build has evidence:

- Fresh install and same-signature saved-data upgrade on a supported real phone.
- Explicit model-download consent, interruption/resume and offline reuse.
- Real camera readings, uncertain inputs, foreground transitions and sustained runs.
- Two separate phones pairing, receiving an actual trigger and retaining receipt history after restart.
- Receiver notification permission, visible receiving service and battery restrictions. Public relay delivery is best effort; successful publication is not proof of reception.
- Source commit, version, APK hash/signature, model identities, dependency inventories and notices tied to the same candidate.

Emulator replay is useful evidence of that replay only. It does not establish real-camera accuracy, hardware speed or long-running reliability. Generated scenes must be identified as generated. No face-identity recognition is claimed.

## Retired infrastructure

The project does not provide a backend, model mirror, login website, billing service or cloud assistant. Record verified provider retirement separately from source deletion. Keep recovery archives private and preserve unrelated projects in shared provider accounts. New deployments and paid resources are outside the Community release process.
