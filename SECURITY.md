# Security policy

[中文](SECURITY.zh-CN.md) · [Project home](README.md)

## Current status

Community source is public and remains a developer preview. Report vulnerabilities, keys or user-data problems through [GitHub private vulnerability reporting](https://github.com/Munable/be-your-eye/security/advisories/new). Do not include keys, verification codes, account information, device identifiers, user events or camera content in public issues.

## Security boundaries

- Catalogs and manifests use RFC 8785 canonical JSON, Ed25519 signatures and pinned public keys. Model installation and use verify SHA-256, size, licenses, runtime contracts and device compatibility. Community metadata has no renewable service lease; actual license deadlines still apply.
- Private keys, pairing codes, group keys, images, readings and user content must not enter Git, public logs or issue reports.
- Paired messages are encrypted and authenticated on the phone with AES-256-GCM. The relay can observe IP addresses, random topics, timing, sizes and ciphertext. Every group member has the same authority; removing a member requires a new group and key.
- The receiver rejects tampered, stale, future and duplicate messages. It does not accept images or remote commands. Pairing QR codes contain keys and must be shared only with trusted people.
- Model downloads allow one HTTPS redirect to the designated GitHub Release asset host, followed by exact artifact verification. Relay messaging does not automatically follow redirects.
- There are no accounts, billing, FCM, cloud AI, microphone capture or maintainer backend. Public relay delivery is not guaranteed and is unsuitable as a safety alarm.

## Dependencies and supply chain

Dependencies use lockfiles or exact versions; release builds produce an SBOM. Security updates must not bypass contract fixtures, model license review, signature verification, deterministic model selection or rollback tests. A tampered active model or Catalog must be rejected with a clear error, never silently replaced by an unreviewed resource.

## Supported scope

Community is a developer preview with no production security support commitment. Future production support covers only the latest Beta or official release recorded under `evidence/releases/` according to [the evidence policy](evidence/README.md). Old builds, development signatures, emulators and unsigned Catalogs do not establish production security support. [Release gates](docs/RELEASE.md) remain the authority.
