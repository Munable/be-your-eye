import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

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

val configuredVersionCode = optionalConfig("BEYOUREYES_VERSION_CODE").toIntOrNull() ?: 25
val configuredVersionName = optionalConfig("BEYOUREYES_VERSION_NAME").ifBlank { "0.2.24-dev" }
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
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("functionalTest")

    defaultConfig {
        applicationId = "app.beyoureyes.monitor"
        minSdk = 26
        targetSdk = 36
        resourceConfigurations += setOf(
            "en", "b+zh+Hans", "b+zh+Hant", "ja", "ko", "es", "fr", "de", "pt-rBR",
        )
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appName"] = "@string/app_name"
        buildConfigField("boolean", "COMMUNITY_BUILD", "false")
        buildConfigField("boolean", "WEBSITE_BILLING_ENABLED", "false")
        buildConfigField("String", "SUPABASE_URL", buildConfigString(optionalConfig("SUPABASE_URL")))
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(optionalConfig("SUPABASE_PUBLISHABLE_KEY")),
        )
        buildConfigField("String", "MODEL_CATALOG_URL", buildConfigString(optionalConfig("MODEL_CATALOG_URL")))
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(optionalConfig("PRIVACY_POLICY_URL")))
        buildConfigField("String", "AUTH_REDIRECT_URL", buildConfigString(optionalConfig("AUTH_REDIRECT_URL")))
        val authRedirect = runCatching { URI(optionalConfig("AUTH_REDIRECT_URL")) }.getOrNull()
        manifestPlaceholders["authRedirectScheme"] =
            authRedirect?.scheme?.takeIf(String::isNotBlank) ?: "https"
        manifestPlaceholders["authRedirectHost"] =
            authRedirect?.host?.takeIf(String::isNotBlank) ?: "auth.invalid"
        manifestPlaceholders["authRedirectPath"] =
            authRedirect?.path?.takeIf(String::isNotBlank) ?: "/auth/callback"
        buildConfigField("String", "FIREBASE_API_KEY", buildConfigString(optionalConfig("FIREBASE_API_KEY")))
        buildConfigField(
            "String",
            "FIREBASE_APPLICATION_ID",
            buildConfigString(optionalConfig("FIREBASE_APPLICATION_ID")),
        )
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(optionalConfig("FIREBASE_PROJECT_ID")))
        buildConfigField(
            "String",
            "FIREBASE_GCM_SENDER_ID",
            buildConfigString(optionalConfig("FIREBASE_GCM_SENDER_ID")),
        )
    }

    signingConfigs {
        create("commercialRelease") {
            if (releaseSigningComplete) {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appName"] = "@string/app_name_ui_test"
            buildConfigField("String", "BUILD_CHANNEL", "\"development-no-model\"")
            buildConfigField("String", "BUILD_IDENTITY", "\"ui-unit-test-only\"")
            buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "false")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
            buildConfigField("String", "FIREBASE_API_KEY", buildConfigString(optionalConfig("FIREBASE_DEBUG_API_KEY")))
            buildConfigField(
                "String",
                "FIREBASE_APPLICATION_ID",
                buildConfigString(optionalConfig("FIREBASE_DEBUG_APPLICATION_ID")),
            )
            buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(optionalConfig("FIREBASE_DEBUG_PROJECT_ID")))
            buildConfigField(
                "String",
                "FIREBASE_GCM_SENDER_ID",
                buildConfigString(optionalConfig("FIREBASE_DEBUG_GCM_SENDER_ID")),
            )
            ndk { abiFilters += "x86_64" }
        }
        create("functionalTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".functional"
            versionNameSuffix = "-functional"
            manifestPlaceholders["appName"] = "@string/app_name_functional_test"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "BUILD_CHANNEL", "\"internal-evaluation\"")
            buildConfigField("String", "BUILD_IDENTITY", "\"functional-test\"")
            buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "true")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
            ndk {
                abiFilters.clear()
                // Linux CI uses x86_64; Apple Silicon local CI uses arm64.
                // This test-only build carries both so the same functional
                // suite runs locally without changing product binaries.
                abiFilters += setOf("x86_64", "arm64-v8a")
            }
        }
        create("internal") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            manifestPlaceholders["appName"] = "@string/app_name_internal"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "BUILD_CHANNEL", "\"internal-evaluation\"")
            buildConfigField("String", "BUILD_IDENTITY", "\"internal-evaluation\"")
            buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "true")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningComplete) signingConfig = signingConfigs.getByName("commercialRelease")
            buildConfigField("String", "BUILD_CHANNEL", "\"commercial\"")
            buildConfigField("String", "BUILD_IDENTITY", "\"commercial\"")
            buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "true")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "true")
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("website") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("String", "BUILD_IDENTITY", "\"commercial-website\"")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
            buildConfigField("boolean", "WEBSITE_BILLING_ENABLED", "true")
        }
    }

    buildTypes {
        listOf("communityDebug", "communityRelease").forEach { variant ->
            create(variant) {
                initWith(getByName(if (variant == "communityDebug") "debug" else "release"))
                if (variant == "communityRelease" && optionalConfig("COMMUNITY_SIGNED") == "true") {
                    require(releaseSigningComplete) { "COMMUNITY_SIGNED requires all release signing inputs" }
                }
                if (variant == "communityRelease" && optionalConfig("COMMUNITY_SIGNED") != "true") signingConfig = null
                applicationIdSuffix = ".community" + if (variant == "communityDebug") ".debug" else ""
                versionNameSuffix = "-community-preview" + if (variant == "communityDebug") "-debug" else ""
                manifestPlaceholders["appName"] = "Be Your Eye Community"
                matchingFallbacks += listOf(if (variant == "communityDebug") "debug" else "release")
                buildConfigField("boolean", "COMMUNITY_BUILD", "true")
                buildConfigField("boolean", "PRODUCT_RUNTIME_ENABLED", "true")
                buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
                buildConfigField("boolean", "WEBSITE_BILLING_ENABLED", "false")
                buildConfigField("String", "MODEL_CATALOG_URL", buildConfigString(
                    optionalConfig("COMMUNITY_MODEL_CATALOG_URL").ifBlank {
                        "https://models.beyoureye.com/community/2026.09.21.1/catalog.json"
                    }))
                buildConfigField("String", "BUILD_CHANNEL", "\"community\"")
                buildConfigField("String", "BUILD_IDENTITY", "\"community\"")
                listOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "AUTH_REDIRECT_URL",
                    "FIREBASE_API_KEY", "FIREBASE_APPLICATION_ID", "FIREBASE_PROJECT_ID", "FIREBASE_GCM_SENDER_ID"
                ).forEach { buildConfigField("String", it, "\"\"") }
                ndk { abiFilters.clear(); abiFilters += "arm64-v8a" }
            }
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets {
        if (testBuildType == "communityDebug") {
            getByName("androidTest").kotlin.directories.clear()
            getByName("androidTest").kotlin.directories.add("src/communityAndroidTest/java")
        }
        listOf("debug", "functionalTest", "internal", "release", "website").forEach { variant ->
            getByName(variant) {
                kotlin.directories.add("src/connected/java")
                manifest.srcFile("src/connected/AndroidManifest.xml")
            }
            getByName("test" + variant.replaceFirstChar(Char::uppercase)) {
                kotlin.directories.add("src/testConnected/java")
            }
        }
        listOf("communityDebug", "communityRelease").forEach { variant ->
            getByName(variant) {
                kotlin.directories.add("src/community/java")
                assets.srcDir("src/community/assets")
                manifest.srcFile("src/community/AndroidManifest.xml")
            }
        }
        getByName("androidTest").assets.directories.add(
            generatedInternalReferenceAssets.get().asFile.absolutePath,
        )
        getByName("androidTest").assets.directories.add(
            generatedInternalReadingAssets.get().asFile.absolutePath,
        )
        getByName("androidTest").assets.directories.add(
            generatedInternalObjectAssets.get().asFile.absolutePath,
        )
        getByName("androidTest").assets.directories.add(
            generatedExternalVisionReplayAssets.get().asFile.absolutePath,
        )
    }
}

val verifyCommercialReleaseInputs by tasks.registering {
    group = "verification"
    doLast {
        val required = linkedMapOf(
            "MODEL_CATALOG_URL" to optionalConfig("MODEL_CATALOG_URL"),
            "PRIVACY_POLICY_URL" to optionalConfig("PRIVACY_POLICY_URL"),
            "SUPABASE_URL" to optionalConfig("SUPABASE_URL"),
            "SUPABASE_PUBLISHABLE_KEY" to optionalConfig("SUPABASE_PUBLISHABLE_KEY"),
            "AUTH_REDIRECT_URL" to optionalConfig("AUTH_REDIRECT_URL"),
            "FIREBASE_API_KEY" to optionalConfig("FIREBASE_API_KEY"),
            "FIREBASE_APPLICATION_ID" to optionalConfig("FIREBASE_APPLICATION_ID"),
            "FIREBASE_PROJECT_ID" to optionalConfig("FIREBASE_PROJECT_ID"),
            "FIREBASE_GCM_SENDER_ID" to optionalConfig("FIREBASE_GCM_SENDER_ID"),
        )
        val missing = required.filterValues(String::isBlank).keys +
            listOf(
                "BEYOUREYES_VERSION_CODE" to optionalConfig("BEYOUREYES_VERSION_CODE"),
                "BEYOUREYES_VERSION_NAME" to optionalConfig("BEYOUREYES_VERSION_NAME"),
                "BEYOUREYES_RELEASE_STORE_FILE" to releaseStoreFilePath,
                "BEYOUREYES_RELEASE_STORE_PASSWORD" to releaseStorePassword,
                "BEYOUREYES_RELEASE_KEY_ALIAS" to releaseKeyAlias,
                "BEYOUREYES_RELEASE_KEY_PASSWORD" to releaseKeyPassword,
            ).filter { it.second.isBlank() }.map { it.first }
        require(missing.isEmpty()) { "Commercial release inputs are missing: ${missing.sorted()}" }
        listOf("MODEL_CATALOG_URL", "PRIVACY_POLICY_URL", "SUPABASE_URL", "AUTH_REDIRECT_URL").forEach { name ->
            require(required.getValue(name).startsWith("https://")) { "$name must use HTTPS" }
        }
        val authRedirect = runCatching { URI(required.getValue("AUTH_REDIRECT_URL")) }.getOrNull()
        require(
            authRedirect != null && authRedirect.scheme == "https" &&
                !authRedirect.host.isNullOrBlank() && authRedirect.rawUserInfo == null &&
                authRedirect.port == -1 && authRedirect.rawQuery == null &&
                authRedirect.rawFragment == null && authRedirect.rawPath == "/auth/callback"
        ) { "AUTH_REDIRECT_URL must be a canonical HTTPS /auth/callback URL" }
        val store = file(releaseStoreFilePath).canonicalFile
        require(store.isFile && store.canRead()) { "Commercial signing keystore is not readable" }
        require(!store.toPath().startsWith(rootProject.projectDir.parentFile.canonicalFile.toPath()))
        require(releaseSigningComplete)
    }
}

val verifyInternalProductInputs by tasks.registering {
    group = "verification"
    doLast {
        val catalogUrl = optionalConfig("MODEL_CATALOG_URL")
        val catalogUri = runCatching { URI(catalogUrl) }.getOrNull()
        require(
            catalogUri != null &&
                catalogUri.scheme == "https" &&
                !catalogUri.host.isNullOrBlank() &&
                catalogUri.userInfo == null &&
                catalogUri.fragment == null &&
                catalogUri.query == null &&
                catalogUri.path.endsWith("/catalog.json") &&
                "/internal-evaluation/" in catalogUri.path
        ) {
            "Internal product APK requires a canonical HTTPS internal-evaluation Catalog URL"
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "bundleRelease", "packageWebsite", "bundleWebsite")) dependsOn(verifyCommercialReleaseInputs)
    if (name in setOf("packageInternal", "bundleInternal")) dependsOn(verifyInternalProductInputs)
    if (name.contains("AndroidTest", ignoreCase = true) &&
        !name.startsWith("prepareInternal") &&
        !name.startsWith("prepareExternal")
    ) {
        dependsOn(prepareInternalReferenceAndroidTestAssets)
        dependsOn(prepareInternalReadingAndroidTestAssets)
        dependsOn(prepareInternalObjectAndroidTestAssets)
        dependsOn(prepareExternalVisionReplayAndroidTestAssets)
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
    implementation(libs.supabase.auth)
    listOf("debug", "functionalTest", "internal", "release", "website").forEach { variant ->
        add("${variant}Implementation", platform(libs.firebase.bom))
        add("${variant}Implementation", libs.firebase.messaging)
        add("${variant}Implementation", libs.play.billing)
    }
    testImplementation(libs.junit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.gson)
    androidTestImplementation(libs.supabase.postgrest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
