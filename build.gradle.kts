// Root build script: plugin versions come from gradle/libs.versions.toml and are
// applied per module (app/build.gradle.kts), not here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
