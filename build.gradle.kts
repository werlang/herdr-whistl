// Root build script: plugin versions come from gradle/libs.versions.toml and are
// applied per module (app/build.gradle.kts), not here.
buildscript {
    dependencies {
        // Built-in Kotlin (AGP 9) compiles with the kotlin-gradle-plugin on this classpath. AGP 9.5.1 bundles 2.2.10 as
        // its floor, and the 2026.09 Compose BOM / kotlinx 1.12 need Kotlin 2.4 metadata, so the plugin is raised here.
        // A buildscript block cannot read the version catalog: keep this in step with `kotlin` in libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
