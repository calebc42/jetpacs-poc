// AGP 9 carries built-in Kotlin; only the compose compiler plugin is added.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.calebc42.ebp.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.calebc42.ebp.companion"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-w4"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // RF-1b. isIncludeAndroidResources is MANDATORY and its absence does
    // not fail — Robolectric silently falls back to Android 6.0.1 / SDK 23
    // with the wrong package name and no warning at all, which would make
    // every renderer test a test of a platform this app cannot run on.
    // RobolectricEnvironmentTest asserts SDK_INT == 36 so that degradation
    // can never pass unnoticed.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(projects.wire)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons)
    testImplementation(libs.junit)
    // RF-1b: the renderer test stack. The test configuration does NOT
    // inherit the BOM the implementation configuration gets, so it is
    // declared again here — that is what keeps the Compose test artifacts
    // pinned to the same 1.10.4 the app renders with.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // debugImplementation, NOT testImplementation: with android resources
    // enabled Robolectric reads the BINARY manifest out of the debug
    // variant's apk-for-local-test.ap_, and ComponentActivity is absent
    // from that unless the dependency is in the variant. The cost is
    // recorded in LIBRARY-LEDGER — it adds an exported ComponentActivity
    // (+247 B) to the DEBUG manifest, which is the APK the device smoke
    // drives. Release is untouched.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
