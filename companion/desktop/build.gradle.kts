// SPDX-License-Identifier: GPL-3.0-or-later
// Compose for Desktop Companion host (docs/PLAN-desktop-companion.md).
// D0: a toolchain spike. The module stays and grows into the D5 composition
// root; the SpikeApp screen is throwaway. Google's material3, adaptive and
// navigation3-ui are jvmStubs on desktop, so this JVM-only module takes the
// JetBrains builds; Google's Navigation 3 runtime and lifecycle are real
// desktop artifacts and are shared with the Android host.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

val ebpDir: File =
    (gradle.extra["jetpacs.repositoriesRoot"] as File).resolve("ebp")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.adaptive)
    implementation(libs.jetbrains.adaptive.layout)
    implementation(libs.jetbrains.adaptive.navigation)
    implementation(libs.jetbrains.adaptive.navigation3)
    implementation(libs.jetbrains.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jetbrains.compose.material.icons)
    implementation(projects.core.navigation)
    implementation(projects.wire)
    implementation(projects.ebpKmp)
    // D1: the multiplatform renderers, linked here against JetBrains' Compose
    // through the root build's substitution rule.
    implementation(projects.renderer.material3)
    implementation(projects.renderer.jetpacs)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.calebc42.jetpacs.desktop.MainKt"
        jvmArgs += listOf("-Debp.dir=${ebpDir.path}")
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    systemProperty("ebp.dir", ebpDir.path)
    systemProperty(
        "jetpacs.spike.out",
        layout.buildDirectory.dir("spike").get().asFile.path,
    )
}
