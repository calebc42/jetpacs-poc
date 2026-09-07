plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.calebc42.jetpacs.renderer.glance"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(projects.renderer.model)
    implementation(projects.wire)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    systemProperty("ebp.dir", (gradle.extra["jetpacs.repositoriesRoot"] as File).resolve("ebp").absolutePath)
}
