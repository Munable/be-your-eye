import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val deviceTestAbi = providers.gradleProperty("beYourEyes.deviceTestAbi").orNull

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.beyoureyes.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += deviceTestAbi?.let(::setOf) ?: setOf("arm64-v8a", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.incremental"] = "true"
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
            it.inputs.dir(rootProject.projectDir.parentFile.resolve("test-vectors"))
            it.systemProperty(
                "beYourEyes.repoRoot",
                rootProject.projectDir.parentFile.absolutePath,
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:vision"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gson)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.java.json.canonicalization)
    implementation(libs.google.tink.android)
    testImplementation(libs.junit4)
    testImplementation(libs.orgjson)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
