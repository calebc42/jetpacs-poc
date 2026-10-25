// SPDX-License-Identifier: GPL-3.0-or-later
// :host is the platform-neutral Companion host (docs/PLAN-desktop-companion.md,
// D5): the protocol host core and the window composition that the Android
// and the desktop composition roots share. Kotlin Multiplatform with an
// Android and a JVM target, like :renderer:material3: commonMain declares
// Google's Compose coordinates, which Android resolves as before and the
// Companion build substitutes with JetBrains' desktop builds on every JVM
// classpath. Nothing here may import android.*; what a platform provides
// arrives through CompanionHostPlatform and HostUiPlatform.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.calebc42.jetpacs.companion.host"
        compileSdk = 37
        minSdk = 34
        // Plain JUnit tests in commonTest run on jvmTest and testAndroidHostTest.
        withHostTestBuilder {}
    }

    compilerOptions {
        // The Material renderer's catalog rows are foundation-style components.
        freeCompilerArgs.add(
            "-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.wire)
            api(projects.ebpKmp)
            api(projects.renderer.material3)
            api(projects.renderer.jetpacs)
            api(projects.core.navigation)
            api(projects.core.database)
            api(projects.core.ebpStore)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.material3)
            // The platform ripple the Material-free design renderer draws.
            implementation(libs.androidx.compose.material.ripple)
            implementation(libs.androidx.compose.foundation)
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.material.icons)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

tasks.named<Test>("jvmTest") {
    useJUnit()
}
