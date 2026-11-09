plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.werlang.whistl"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.werlang.whistl"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is shown in Settings > About (phase 4 r2).
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // SPEC §8: severities for the security, accessibility and i18n IDs live in lint.xml (one place).
        lintConfig = file("lint.xml")
    }

    packaging {
        resources {
            // bcprov, bcutil and bcpkix each ship this manifest for Java 9+; the APK needs none of them.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            // Robolectric renders the merged resources (strings, theme, fonts) in the screenshot tests.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

roborazzi {
    // Reference screenshots are committed here. `verifyRoborazziDebug` compares against them; `recordRoborazziDebug`
    // rewrites them (SPEC §8, phase 4 r1).
    outputDir.set(file("src/test/screenshots"))
}

// Screenshot gate (phase 6 r1, P0-1). Roborazzi 1.40.1 reads roborazzi.test.{record,compare,verify} each time captureRoboImage
// runs (RoborazziPropertiesKt.roborazziSystemPropertyTaskType); with none set it does nothing, so a plain test run compared
// nothing. Now every test run verifies against the committed goldens, and a golden that differs fails the run.
// `recordRoborazziDebug` is the only way to rewrite them: record together with verify resolves to VerifyAndRecord
// (RoborazziTaskType.Companion.of), so verify is left off for a recording run.
// `-Pwhistl.screenshots=skip` (the Linux CI job) leaves the screenshot classes out: Robolectric rasterises fonts per platform,
// so the comparison runs on macOS only (.github/workflows/ci.yml, job `screenshots`).
val recordingScreenshots = gradle.startParameter.taskNames.any { it.contains("recordRoborazzi", ignoreCase = true) }
val skipScreenshots = providers.gradleProperty("whistl.screenshots").orNull == "skip"

tasks.withType<Test>().configureEach {
    // Screenshot file names are relative to roborazzi.outputDir above, not to the module folder (Roborazzi 1.40 asks for
    // this setting in gradle.properties; it is set here so the build file holds the whole screenshot setup).
    systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
    // The goldens a verify run compares against live in the same folder the record task writes to. Without this property the
    // test JVM looks in the default build/outputs/roborazzi, where no golden is.
    systemProperty("roborazzi.output.dir", file("src/test/screenshots").absolutePath)
    if (!recordingScreenshots) systemProperty("roborazzi.test.verify", "true")
    if (skipScreenshots) exclude("**/*ScreenshotTest*")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)

    // Protocol core (phase 2): Android-free JSON-RPC over line transports.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // SSH: sshj for the transport. The bundled BouncyCastle is sshj's crypto provider on every API level (SPEC §6; phase 7
    // r1c): the platform's own BC has no X25519, which sshj's key exchange needs. core/ssh/CryptoProviders registers it.
    // bcpkix is back on the classpath for that: it was excluded while the app needed no PEM or EST code, and it carries the EST trust-all
    // manager that lint reports as TrustAllX509TrustManager. app/lint.xml ignores that issue for the bcpkix jar only.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)

    // Screenshot tests (phase 4 r1): Robolectric runs the Compose shell on the JVM and Roborazzi records the PNGs.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The test activity the Compose rule launches. Only the debug variant runs the screenshot tests (src/testDebug).
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
