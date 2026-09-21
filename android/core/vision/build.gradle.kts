import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.PathSensitivity

val deviceTestAbi = providers.gradleProperty("beYourEyes.deviceTestAbi").orNull

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.beyoureyes.core.vision"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += deviceTestAbi?.let(::setOf) ?: setOf("arm64-v8a", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
            it.inputs
                .dir(rootProject.projectDir.parentFile.resolve("test-vectors"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.systemProperty(
                "beYourEyes.repoRoot",
                rootProject.projectDir.parentFile.absolutePath,
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add(
            rootProject.projectDir.parentFile.resolve(
                "model-tools/v3/ppocr_official/android-test-assets",
            ).absolutePath,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.google.litert)
    implementation(libs.microsoft.onnxruntime.android)
    implementation(libs.gson)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit4)
}
