# Runtime contracts

These JSON schemas describe local task configuration, observations, events, signed model Manifests and the signed Catalog. The app has no assistant, account, billing or cloud-sync API.

| Contract | Purpose |
| --- | --- |
| Task configuration | Reference images, numeric readings or an exact Catalog target, plus its trigger rule |
| Observation | A local recognition result from an admitted runtime |
| Event | A locally stored trigger result; camera media stays in app-private files |
| Model Manifest | Exact artifacts, hashes, license review, runtime, inputs/outputs, supported devices and signed labels |
| Capability Catalog | The admitted packages and exact targets available to the app |

The three runtime families are `object_detection_v1`, `similarity_match_v1` and `reading_pipeline_v1`. Unknown targets fail closed. No nearby class or generic model may substitute for missing Catalog coverage.

Model acceptance still requires signature and byte verification, redistribution permission, compatible hardware and a runtime self-test. Community Catalog metadata is bundled and does not require a periodically renewed service lease. A real license expiry is still enforced.

Paired-phone alerts have a separate, small encrypted text envelope implemented in `PeerProtocol.kt`. They never carry a TaskConfig, camera command, model package, image or database replica. See [pairing](../docs/community/PAIRING.md).

Schema and self-test success do not establish camera accuracy, real-device acceptance or guaranteed delivery. Historical test fixtures retain their original release context.
