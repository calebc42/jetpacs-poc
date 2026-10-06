plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.calebc42.jetpacs.material3.testing"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // A test-support library for the Material renderer: consumers add it to
    // their test or androidTest configurations (companion/TESTING.md).
    api(projects.renderer.material3)
    api(projects.renderer.composeTesting)
    implementation(projects.wire)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
