plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val productVersion = providers.gradleProperty("BEYOUREYES_VERSION_NAME")
    .orElse(providers.environmentVariable("BEYOUREYES_VERSION_NAME"))
    .getOrElse("0.3.1")

allprojects {
    group = "app.beyoureyes"
    version = productVersion
}
