plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.calebc42.jetpacs.renderer.model"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(projects.wire)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
