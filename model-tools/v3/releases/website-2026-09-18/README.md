# Website candidate model inputs, 2026-09-18

These are new Commercial-channel model inputs, separate from historical Internal artifacts.
They preserve the three pretrained model/runtime families and exact artifact bytes. The reference
Manifest binds the 2026-09-18 publisher-license review; Android includes model attribution and
Apache-2.0 text. No training, conversion, model fallback or recognition-threshold change occurred.

Current version: `2026.09.18.1`. Initial rollback baseline: `2026.09.18.0` with identical model
bytes. Both use the existing `models.beyoureye.com` R2 custom domain, the existing Ed25519 key
rings, and new versioned manifests. The initial rollback is not a previously accepted production
release. The existing release builder signs `templates/release-build-input.json` and
`templates/rollback.release-build-input.json` with keys outside the repository; the commercial
freezer independently validates the public current and rollback descriptors and all package bytes.

Signed versioned outputs are on DevDisk under `be-your-eyes/repo-local/releases/website-models/`.
The retained verified model snapshots belong with the exact APK candidates under
`be-your-eyes/repo-local/releases/website/<commit>/<versionCode>/models/`.
The duplicate preflight downloads were removed. Never upload private keys or the local release environment.

Catalog freshness ends 2026-09-25T12:49:39Z (current) and one second earlier (rollback).
The website channel aliases under `https://models.beyoureye.com/commercial/website/`
initially contain these exact signed catalogs and descriptors. The APK uses the channel Catalog
so a later validated publication can refresh it without an APK reinstall; immutable originals
remain retained. See `release/website/README.md` for the refresh procedure. No scheduled signing
job or cloud copy of the private keys has been created. Ongoing paid distribution still requires
an operator to renew before expiry. Catalog freshness governs admission, not installed-model license expiry.
Model acceptance does not close the recorded natural-camera reference-recognition failure,
physical website APK acceptance, real email delivery, or real payment/refund evidence.
