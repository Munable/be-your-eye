plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val productVersion = providers.gradleProperty("BEYOUREYES_VERSION_NAME")
    .orElse(providers.environmentVariable("BEYOUREYES_VERSION_NAME"))
    .getOrElse("0.2.24-dev")

allprojects {
    group = "app.beyoureyes"
    version = productVersion
}
