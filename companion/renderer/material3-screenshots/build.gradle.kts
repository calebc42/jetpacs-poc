// The Compose Preview screenshot suite for the Material renderer. It lives in
// its own Android library because the screenshot plugin configures only the
// classic Android plugins, and :renderer:material3 is Kotlin Multiplatform
// (docs/PLAN-desktop-d1-renderers.md). The test file and its reference images
// moved here unchanged; references are keyed by package and file, so
// validation compares the same images as before.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.calebc42.jetpacs.material3.screenshots"
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
        freeCompilerArgs.add(
            "-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi",
        )
    }
}

dependencies {
    implementation(projects.renderer.material3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
