# Contributing

[中文](CONTRIBUTING.zh-CN.md) · [Project home](README.md)

## Pick one small contribution

| Task | What to report | Start here |
| --- | --- | --- |
| Describe a recurring check | The display or object, condition, current workaround and next opportunity to try it | [Monitoring task](https://github.com/Munable/be-your-eye/issues/new?template=task.yml) |
| Try one compatible phone and scene | Build/commit, Android version, model/RAM, setup, duration, expected events, actual events, misses and false events; keep physical results separate from emulator replays | [Bug report](https://github.com/Munable/be-your-eye/issues/new?template=bug.yml) for a failure, or a [monitoring task](https://github.com/Munable/be-your-eye/issues/new?template=task.yml) for a field report |
| Review one supported language | App language, screen/action, current wording or clipping, and a suggested correction; start with one setup flow and its notification | [Bug report](https://github.com/Munable/be-your-eye/issues/new?template=bug.yml) |

There is no public APK yet. Phone testing starts with a [local Community build](docs/community/BUILD.md), a fixed compatible phone and a scene you can try safely. A continuous physical-camera event reaching a second phone is useful missing evidence; label a test-message check separately. No private footage or pairing codes are needed in a public report.

## Code and copy changes

Start with `bash tools/ci/run-community.sh` and [BUILD](docs/community/BUILD.md). Keep the four modules and signed model contracts. Propose a concrete user task before adding a model or new abstraction. Do not add training, background monitoring, advertising SDKs or a plugin platform.

User-visible copy follows the nine-locale contract in [DEVELOPMENT](docs/DEVELOPMENT.md): `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `es`, `fr`, `de` and `pt-BR`. Add resource or dictionary keys instead of hard-coded display text, keep placeholders and plurals identical, and run `node tools/ci/check-i18n.mjs` plus its test. Relay and network failures must be localized on the client. User-entered text and historical content are not translated automatically.

Report the app version, Android version/model (never serial), steps, expected/actual behavior and whether the failure involved download, setup, recognition or an alert. Photos and logs are optional: remove personal content and credentials before sharing. Tell us what you previously had to check manually and when the next real opportunity occurs; stars/downloads are not evidence of use.

First-party source is available under Apache-2.0. Intentional contributions use the same license; contributors must have the right to submit their work. Preserve original third-party notices. No complex CLA is introduced. Names/logos identify origin and must not imply official endorsement of forks.

The Apache-2.0 code license does not grant trademark permission. Keep required notices, and describe modified distributions accurately; do not present them as official Be Your Eye releases or imply publisher endorsement.
