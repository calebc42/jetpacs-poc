// The Compose Preview screenshot suite for the Jetpacs Components renderer.
// It lives in its own Android library because the screenshot plugin configures
// only the classic Android plugins, and :renderer:jetpacs is Kotlin
// Multiplatform (jetpacs-poc docs/PLAN-desktop-d1-renderers.md). The test file
// and its reference images moved here unchanged; references are keyed by
// package and file, so validation compares the same images as before.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.calebc42.jetpacs.renderer.jetpacs.screenshots"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    buildFeatures { compose = true }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    testOptions {
        screenshotTests { imageDifferenceThreshold = 0.001f }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi",
            "-opt-in=com.calebc42.jetpacs.renderer.jetpacs.InternalJetpacsRendererApi",
        )
    }
}

dependencies {
    implementation(projects.renderer.jetpacs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
