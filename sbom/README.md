# SBOM 规则

`tools/ci/run-local.sh` 在 Android、Supabase、合同/模型政策测试全部通过后生成五份 CycloneDX JSON：Android 使用已解析的 Gradle 运行时依赖，Supabase 本地测试使用 npm lock，通用 Edge Functions 和官网计费分别使用 Deno lock，Catalog Validator 使用自身 npm lock。GitHub Actions 当前关闭，不参与构建或 SBOM 生成。

`tools/sbom/generate.sh OUTPUT_DIR [ANDROID_ARTIFACT]` 固定使用 `org.cyclonedx.bom` 3.3.0 和 `@cyclonedx/cyclonedx-npm` 4.0.3。默认解析 `releaseRuntimeClasspath` 并绑定 Play AAB；官网发布设置 `BEYOUREYES_SBOM_ANDROID_VARIANT=website`，解析 App 的 `websiteRuntimeClasspath` 及其 release 库依赖，并绑定官网 APK。正式发布脚本必须传入精确产物；文件名、大小和 SHA-256 同时写入 CycloneDX application component 与 provenance，并由同一 variant 配置下的 `verify.sh` 对原文件复核。未传产物时只生成对应 variant 的依赖清单。每份 SBOM 都附带 SHA-256 sidecar 和 provenance JSON。

每个 Beta 和商业候选构建必须同时归档：

- 仓库依赖 SBOM；
- APK/AAB 的实际二进制 SBOM；
- 活动 Model Catalog 中每包来源、许可、Manifest 和模型 SHA-256；
- 构建提交、渠道、产物 SHA-256 和生成工具版本；
- 与 `THIRD_PARTY_NOTICES.md` 的差异审核。

本地生成文件默认位于 `.local/local-ci-sbom/`，不提交重复产物。候选构建只在 `evidence/releases/`写入最终哈希摘要。SBOM 生成成功只证明清单产出，不等于许可审核通过或生产发布完成。
