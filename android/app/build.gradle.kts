import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun optionalConfig(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .getOrElse("")

fun buildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
    append('"')
}

val configuredVersionCode = optionalConfig("BEYOUREYES_VERSION_CODE").toIntOrNull() ?: 27
val configuredVersionName = optionalConfig("BEYOUREYES_VERSION_NAME").ifBlank { "0.3.1" }
val releaseStoreFilePath = optionalConfig("BEYOUREYES_RELEASE_STORE_FILE")
val releaseStorePassword = optionalConfig("BEYOUREYES_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = optionalConfig("BEYOUREYES_RELEASE_KEY_ALIAS")
val releaseKeyPassword = optionalConfig("BEYOUREYES_RELEASE_KEY_PASSWORD")
val releaseSigningComplete = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all(String::isNotBlank)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val internalReferenceReleaseDir = providers.gradleProperty("internalReferenceReleaseDir")
val internalReferencePrimaryArtifact = providers.gradleProperty("internalReferencePrimaryArtifact")
val internalReferenceObjectCropArtifact = providers.gradleProperty("internalReferenceObjectCropArtifact")
val internalReferenceEmbedderArtifact = providers.gradleProperty("internalReferenceEmbedderArtifact")
val internalReferenceSimilarityHead = providers.gradleProperty("internalReferenceSimilarityHead")
val generatedInternalReferenceAssets = layout.buildDirectory.dir(
    "generated/androidTestAssets/internalReferencePackage",
)
val prepareInternalReferenceAndroidTestAssets by tasks.registering(Sync::class) {
    into(generatedInternalReferenceAssets)
    internalReferenceReleaseDir.orNull?.takeIf(String::isNotBlank)?.let { releasePath ->
        val releaseDirectory = file(releasePath)
        val primary = file(checkNotNull(internalReferencePrimaryArtifact.orNull))
        val objectCrop = file(checkNotNull(internalReferenceObjectCropArtifact.orNull))
        val embedder = file(checkNotNull(internalReferenceEmbedderArtifact.orNull))
        val head = file(checkNotNull(internalReferenceSimilarityHead.orNull))
        doFirst {
            listOf(
                releaseDirectory.resolve("catalog.json"),
                releaseDirectory.resolve("manifests/similarity_mediapipe_mobilenet_v3_large_v1.json"),
                primary,
                objectCrop,
                embedder,
                head,
            ).forEach { input -> require(input.isFile) { "missing reference test input: $input" } }
        }
        from(releaseDirectory.resolve("catalog.json")) {
            into("real-signed-reference"); rename { "catalog.json" }
        }
        from(releaseDirectory.resolve("manifests/similarity_mediapipe_mobilenet_v3_large_v1.json")) {
            into("real-signed-reference"); rename { "manifest.json" }
        }
        from(primary) { into("real-signed-reference"); rename { "primary.tflite" } }
        from(objectCrop) { into("real-signed-reference"); rename { "object-crop.json" } }
        from(embedder) { into("real-signed-reference"); rename { "embedder.tflite" } }
        from(head) { into("real-signed-reference"); rename { "similarity-head.json" } }
    }
}

val internalReadingReleaseDir = providers.gradleProperty("internalReadingReleaseDir")
val internalReadingPrimaryArtifact = providers.gradleProperty("internalReadingPrimaryArtifact")
val internalReadingLocatorArtifact = providers.gradleProperty("internalReadingLocatorArtifact")
val internalReadingVocabularyArtifact = providers.gradleProperty("internalReadingVocabularyArtifact")
val generatedInternalReadingAssets = layout.buildDirectory.dir(
    "generated/androidTestAssets/internalReadingPackage",
)
val prepareInternalReadingAndroidTestAssets by tasks.registering(Sync::class) {
    into(generatedInternalReadingAssets)
    internalReadingReleaseDir.orNull?.takeIf(String::isNotBlank)?.let { releasePath ->
        val releaseDirectory = file(releasePath)
        val primary = file(checkNotNull(internalReadingPrimaryArtifact.orNull))
        val locator = file(checkNotNull(internalReadingLocatorArtifact.orNull))
        val vocabulary = file(checkNotNull(internalReadingVocabularyArtifact.orNull))
        doFirst {
            listOf(
                releaseDirectory.resolve("catalog.json"),
                releaseDirectory.resolve("manifests/numeric_reader_ppocrv6_medium_v1.json"),
                primary,
                locator,
                vocabulary,
            ).forEach { input -> require(input.isFile) { "missing reading test input: $input" } }
        }
        from(releaseDirectory.resolve("catalog.json")) {
            into("real-signed-reading"); rename { "catalog.json" }
        }
        from(releaseDirectory.resolve("manifests/numeric_reader_ppocrv6_medium_v1.json")) {
            into("real-signed-reading"); rename { "manifest.json" }
        }
        from(primary) { into("real-signed-reading"); rename { "primary.onnx" } }
        from(locator) { into("real-signed-reading"); rename { "locator.onnx" } }
        from(vocabulary) { into("real-signed-reading"); rename { "vocabulary.json" } }
    }
}

val internalObjectReleaseDir = providers.gradleProperty("internalObjectReleaseDir")
val internalObjectPrimaryArtifact = providers.gradleProperty("internalObjectPrimaryArtifact")
val internalObjectFrameArtifact = providers.gradleProperty("internalObjectFrameArtifact")
val internalObjectPackageId = providers.gradleProperty("internalObjectPackageId")
    .orElse("efficientdet_lite2_object_v1")
val generatedInternalObjectAssets = layout.buildDirectory.dir(
    "generated/androidTestAssets/internalObjectPackage",
)
val prepareInternalObjectAndroidTestAssets by tasks.registering {
    inputs.property("internalObjectReleaseDir", internalObjectReleaseDir).optional(true)
    inputs.property("internalObjectPrimaryArtifact", internalObjectPrimaryArtifact).optional(true)
    inputs.property("internalObjectFrameArtifact", internalObjectFrameArtifact).optional(true)
    inputs.property("internalObjectPackageId", internalObjectPackageId)
    inputs.files(
        internalObjectReleaseDir.zip(internalObjectPackageId) { releasePath, packageId ->
            val releaseDirectory = file(releasePath)
            listOf(
                releaseDirectory.resolve("catalog.json"),
                releaseDirectory.resolve("manifests/$packageId.json"),
            )
        },
    ).optional()
    inputs.files(internalObjectPrimaryArtifact.map(::file)).optional()
    inputs.files(internalObjectFrameArtifact.map(::file)).optional()
    outputs.dir(generatedInternalObjectAssets)
    doLast {
        val output = generatedInternalObjectAssets.get().asFile
        check(!output.exists() || output.deleteRecursively()) {
            "failed to clear generated object detection test assets: $output"
        }
        val releasePath = internalObjectReleaseDir.orNull?.takeIf(String::isNotBlank)
            ?: return@doLast
        val releaseDirectory = file(releasePath)
        val primary = file(checkNotNull(internalObjectPrimaryArtifact.orNull))
        val packageId = internalObjectPackageId.get()
        listOf(
            releaseDirectory.resolve("catalog.json"),
            releaseDirectory.resolve("manifests/$packageId.json"),
            primary,
        ).forEach { input -> require(input.isFile) { "missing object detection test input: $input" } }
        val destination = output.resolve("real-signed-object")
        check(destination.mkdirs()) { "failed to create object detection test assets: $destination" }
        releaseDirectory.resolve("catalog.json").copyTo(destination.resolve("catalog.json"))
        releaseDirectory.resolve("manifests/$packageId.json")
            .copyTo(destination.resolve("manifest.json"))
        primary.copyTo(destination.resolve("primary.tflite"))
        internalObjectFrameArtifact.orNull?.takeIf(String::isNotBlank)?.let { framePath ->
            val frame = file(framePath)
            require(frame.isFile) { "missing object detection test frame: $frame" }
            frame.copyTo(destination.resolve("frame.jpg"))
        }
    }
}

// External replay inputs stay outside Git and are copied into the test APK only for an explicit
// evaluation run. The package directory is already assembled and immutable:
// catalog.json plus packages/<package_id>/manifest.json and artifacts/<role>.
val externalVisionReplayBundleDir = providers.gradleProperty("externalVisionReplayBundleDir")
val externalVisionReplayPackagesDir = providers.gradleProperty("externalVisionReplayPackagesDir")
val generatedExternalVisionReplayAssets = layout.buildDirectory.dir(
    "generated/androidTestAssets/externalVisionReplay",
)
val prepareExternalVisionReplayAndroidTestAssets by tasks.registering {
    inputs.property("externalVisionReplayBundleDir", externalVisionReplayBundleDir).optional(true)
    inputs.property("externalVisionReplayPackagesDir", externalVisionReplayPackagesDir).optional(true)
    outputs.dir(generatedExternalVisionReplayAssets)
    outputs.upToDateWhen { false }
    doLast {
        val output = generatedExternalVisionReplayAssets.get().asFile
        check(!output.exists() || output.deleteRecursively()) {
            "failed to clear generated external replay test assets: $output"
        }
        val bundlePath = externalVisionReplayBundleDir.orNull?.takeIf(String::isNotBlank)
        val packagesPath = externalVisionReplayPackagesDir.orNull?.takeIf(String::isNotBlank)
        if (bundlePath == null && packagesPath == null) return@doLast
        require(bundlePath != null && packagesPath != null) {
            "externalVisionReplayBundleDir and externalVisionReplayPackagesDir must be set together"
        }
        val bundleDirectory = file(bundlePath)
        val packagesDirectory = file(packagesPath)
        require(bundleDirectory.resolve("manifest.json").isFile) {
            "missing external replay bundle manifest: $bundleDirectory/manifest.json"
        }
        require(packagesDirectory.resolve("catalog.json").isFile) {
            "missing external replay signed Catalog: $packagesDirectory/catalog.json"
        }
        val destination = output.resolve("external-vision-replay")
        check(bundleDirectory.copyRecursively(destination.resolve("bundle"), overwrite = true)) {
            "failed to copy external replay bundle into test assets"
        }
        check(packagesDirectory.copyRecursively(destination.resolve("packages"), overwrite = true)) {
            "failed to copy external replay packages into test assets"
        }
    }
}

android {
    namespace = "app.beyoureyes.monitor"
    compileSdk = 36
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("communityDebug")
    defaultConfig {
        applicationId = "app.beyoureyes.monitor"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        resourceConfigurations += setOf("en", "b+zh+Hans", "b+zh+Hant", "ja", "ko", "es", "fr", "de", "pt-rBR")
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appName"] = "@string/app_name"
        buildConfigField("boolean", "COMMUNITY_BUILD", "true")
        buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "true")
        buildConfigField("String", "BUILD_CHANNEL", "\"community\"")
        buildConfigField("String", "BUILD_IDENTITY", "\"community\"")
        buildConfigField("String", "MODEL_CATALOG_URL", buildConfigString(
            optionalConfig("COMMUNITY_MODEL_CATALOG_URL").ifBlank {
                "https://github.com/Munable/be-your-eye/releases/download/models-v1/catalog.json"
            }))
    }
    signingConfigs {
        create("community") {
            if (releaseSigningComplete) {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".community.debug"; versionNameSuffix = "-debug" }
        release {
            applicationIdSuffix = ".community"
            isMinifyEnabled = false
            if (optionalConfig("COMMUNITY_SIGNED") == "true") {
                require(releaseSigningComplete) { "Signed release requires external signing inputs" }
                signingConfig = signingConfigs.getByName("community")
            }
        }
        create("communityDebug") { initWith(getByName("debug")); matchingFallbacks += "debug" }
        create("communityRelease") { initWith(getByName("release")); matchingFallbacks += "release" }
        create("functionalTest") {
            initWith(getByName("debug")); applicationIdSuffix = ".functional"
            matchingFallbacks += "debug"
            buildConfigField("String", "BUILD_IDENTITY", "\"functional-test\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets {
        getByName("main").assets.srcDir("src/community/assets")
        if (testBuildType == "communityDebug") {
            getByName("androidTest").kotlin.directories.clear()
            getByName("androidTest").kotlin.directories.add("src/communityAndroidTest/java")
        }
        getByName("androidTest").assets.directories.add(generatedInternalReferenceAssets.get().asFile.absolutePath)
        getByName("androidTest").assets.directories.add(generatedInternalReadingAssets.get().asFile.absolutePath)
        getByName("androidTest").assets.directories.add(generatedInternalObjectAssets.get().asFile.absolutePath)
        getByName("androidTest").assets.directories.add(generatedExternalVisionReplayAssets.get().asFile.absolutePath)
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnit()
            it.systemProperty("beYourEyes.repoRoot", rootProject.projectDir.parentFile.absolutePath)
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:vision"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("functionalTestImplementation", "androidx.compose.ui:ui-test-manifest")
    add("communityDebugImplementation", "androidx.compose.ui:ui-test-manifest")
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gson)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.gson)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
