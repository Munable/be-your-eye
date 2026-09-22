# 构建 Community

[English](BUILD.md) · [项目首页](../../README.zh-CN.md) · [使用说明](../../USER_MANUAL.zh-CN.md)

**当前为开发预览版，暂无公开 APK。** 可以按下面的步骤自行构建调试版。[Releases](https://github.com/Munable/be-your-eye/releases/tag/models-v1) 中只有模型资源，没有应用安装包。监控端要求 Android 8+、arm64 和 8 GB 内存；[真机验收仍未完成](DEVICE_SUPPORT.zh-CN.md)。

## 构建并安装到自己的手机

需要 JDK 25（已测试 Temurin 25.0.4）、Android SDK platform 36 / build-tools 36.0.0，以及 Node 24.16 或更新的 24.x。Gradle 9.5 已由项目 wrapper 固定，不需要部署后端、服务账号或 Deno 环境。

配置标准的 `JAVA_HOME` 与 `ANDROID_HOME`，安装以上 SDK 组件和 platform-tools。克隆源码（已有工作目录则直接进入），在仓库根目录执行：

```sh
git clone https://github.com/Munable/be-your-eye.git
cd be-your-eye
bash tools/ci/run-community.sh
# 可安装的 arm64 调试版，包名 app.beyoureyes.monitor.community.debug
# android/app/build/outputs/apk/communityDebug/app-communityDebug.apk
```

用 USB 连接一台真机，开启 USB 调试，并在手机上授权这台电脑。把 Android SDK platform-tools 中的 `adb` 加入 `PATH`，然后安装刚构建的调试版：

```sh
adb -d install -r android/app/build/outputs/apk/communityDebug/app-communityDebug.apk
```

在手机上打开帮你盯，按提示允许相机权限，再按[使用说明](../../USER_MANUAL.zh-CN.md)创建监控。监控时保持应用可见。确认首次模型下载后，在可以安全试错的场景中检查触发条件、本机记录和通知。可选的[跨手机提醒](PAIRING.zh-CN.md)需要两端联网。

如果安装被拒绝，先检查手机上的授权提示和开发者安装设置。不要为了绕过拒绝而卸载已有应用或清除数据。已有应用的签名证书若不兼容，需要先决定如何保留资料；`-r` 不会绕过签名检查。

以上步骤不需要 `.env.local`、Supabase 项目、Firebase 注册、付费、API Key 或维护者的私有签名材料。首次下载依赖与模型需要联网。这是可独立构建的路径，不代表逐字节可复现构建。在当前开发 Mac 上，既有构建与缓存链接继续使用 DevDisk；新克隆按标准 Gradle 路径或构建者明确配置的缓存位置运行。

## 发布候选与签名

只冻结已提交的源码，选择空间足够的外置输出目录：

```sh
COMMUNITY_OUTPUT_DIR=/your/release/directory bash tools/release/build-community.sh --unsigned
```

未签名发布包不能直接安装。正式候选使用 `--signed`，并让 `BEYOUREYES_COMMUNITY_SIGNING_ENV` 指向仓库外权限为 600 的文件，导出 `BEYOUREYES_RELEASE_STORE_FILE`、`BEYOUREYES_RELEASE_STORE_PASSWORD`、`BEYOUREYES_RELEASE_KEY_ALIAS` 和 `BEYOUREYES_RELEASE_KEY_PASSWORD`。稳定签名密钥及其恢复副本都应保存在 Git 外，不能把调试签名当成正式发布。

发布包名为 `app.beyoureyes.monitor.community`，不会替换包名不同的旧应用。升级测试必须使用同一证书；不会自动迁移旧应用数据。

## 模型与独立分发

APK 包含公开签名元数据，模型权重位于[本仓库的 models-v1 发布](https://github.com/Munable/be-your-eye/releases/tag/models-v1)。应用在使用前检查签名、精确哈希、大小、许可、运行时合同和设备兼容性。下载前需要用户确认。Community 目录没有需要续签的有效期，已安装模型可离线使用；真实许可到期限制仍然生效。[模型说明（英文）](MODELS.md)记录来源、训练数据披露与许可。

独立分发者可生成自己的 Ed25519 密钥，更新 `StrictSignedJson.kt` 和 `release-public-keys.mjs` 的公钥注册表，使用相同的 manifest / catalog schema，并通过 `release-builder-cli.mjs` 签名。按 `model-tools/v3/releases/community/templates/release-build-input.json` 的输入路径提供精确资源，或生成包含自己路径的输入文件，同时保留许可与审核证据。签名前改成自己核实过的公开托管地址；签名后不能修改字节。`COMMUNITY_MODEL_CATALOG_URL` 可选择单独的 HTTPS Catalog，代替内置候选。独立分发不需要维护者私钥，也不应关闭签名验证。

Community 仪器化检查使用 `-PtestBuildType=communityDebug`；仅摄像头 fixture 检查可明确选择 `functionalTest`。自然物体、长时间运行、不同网络地区和真实参与者验收仍是独立的发布条件。

下载并核验重新打包需要的精确公开资源：

```sh
node tools/release/prepare-community-model-input.mjs /absolute/external/cache /absolute/external/input.json
node model-tools/catalog-validator/src/release-builder-cli.mjs /absolute/external/input.json /absolute/external/release CATALOG_KEY_ID /private/catalog.pem MANIFEST_KEY_ID /private/manifest.pem
```

下载步骤不依赖维护者缓存或凭据。构建器会检查引用的许可和审核文件；独立分发时使用自己的公钥注册表和已审核 HTTPS 托管配置。

冻结已签名 APK 后，生成并验证依赖清单：

```sh
BEYOUREYES_SBOM_ANDROID_VARIANT=communityRelease BEYOUREYES_VERSION_NAME=0.3.1 bash tools/sbom/generate.sh /absolute/release/sbom /absolute/release/be-your-eye-community.apk
```

SBOM 记录精确 APK 哈希，但不能代替依赖漏洞扫描或真机验收。

可选的物体摄像头回放需要 `BEYOUREYES_OBJECT_CAMERA_IMAGE` 指向你有权使用、中央为苹果的 PNG。仓库不分发来源不明的测试照片。该回放独立于 Community CI，也不是自然场景验收。
