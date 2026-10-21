// :renderer:jetpacs is Kotlin Multiplatform with an Android and a JVM target
// (jetpacs-poc docs/PLAN-desktop-d1-renderers.md). commonMain keeps Google's
// Compose coordinates: Android resolves them as before, and a standalone JVM
// target compiles against their published stubs. Only a build that hosts the
// desktop (jetpacs-poc/companion) substitutes JetBrains' desktop builds on its
// JVM classpaths, so Compose must not run on this module's own `jvmTest`. The
// screenshot suite lives in ../jetpacs-screenshots.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.calebc42.jetpacs.renderer.jetpacs"
        compileSdk = 37
        minSdk = 34
        // The IBM Plex families under androidMain/res.
        androidResources { enable = true }
        // The golden replay composes the renderer under Robolectric.
        withHostTestBuilder {}.configure { isIncludeAndroidResources = true }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi",
            "-opt-in=com.calebc42.jetpacs.renderer.jetpacs.InternalJetpacsRendererApi",
            // PlexFonts is an expect object with per-platform font sources.
            "-Xexpect-actual-classes",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.renderer.compose)
            api(project.dependencies.platform(libs.androidx.compose.bom))
            api(libs.androidx.compose.foundation)
            api(libs.androidx.compose.ui)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(projects.renderer.composeTesting)
            implementation(libs.robolectric)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.androidx.test.espresso.core)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
