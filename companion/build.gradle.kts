// One toolchain for every module (the PoC's proven stack).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.compose.screenshot) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Desktop JVM classpaths link JetBrains' Compose builds (D1,
// docs/PLAN-desktop-d1-renderers.md). The renderer modules declare Google's
// Compose coordinates in commonMain, whose desktop variants are compile-only
// stubs. On every configuration whose Kotlin platform type is `jvm`, which is
// the KMP jvm classpaths and the kotlin.jvm :desktop module, the Google UI
// groups resolve to JetBrains' builds instead. Android (androidJvm) and
// metadata configurations are untouched. androidx.compose.runtime is never
// substituted: Google's desktop runtime jar is the one JetBrains' own
// runtime coordinate redirects to. The platform attribute is read when the
// rule runs, because it is not yet set when the configuration is created.
val composeMultiplatformVersion = libs.versions.compose.multiplatform.get()
val desktopComposeGroups = mapOf(
    "androidx.compose.animation" to
        ("org.jetbrains.compose.animation" to composeMultiplatformVersion),
    "androidx.compose.foundation" to
        ("org.jetbrains.compose.foundation" to composeMultiplatformVersion),
    "androidx.compose.ui" to
        ("org.jetbrains.compose.ui" to composeMultiplatformVersion),
    "androidx.compose.material" to
        ("org.jetbrains.compose.material" to composeMultiplatformVersion),
    "androidx.compose.material3" to
        ("org.jetbrains.compose.material3" to libs.versions.jetbrains.material3.get()),
    "androidx.compose.material3.adaptive" to
        ("org.jetbrains.compose.material3.adaptive" to libs.versions.jetbrains.adaptive.get()),
)
val desktopIconsVersion = libs.versions.jetbrains.compose.icons.get()
// Google's navigation3-ui is also jvmStubs on desktop; its runtime is real
// and stays Google's (D0 found JetBrains' 1.1.2 requires Google's 1.1.7).
val desktopNavigation3UiVersion = libs.versions.jetbrains.navigation3.ui.get()

subprojects {
    configurations.configureEach {
        if (!isCanBeResolved) return@configureEach
        val configuration = this
        resolutionStrategy.dependencySubstitution.all {
            val selector = requested as? org.gradle.api.artifacts.component.ModuleComponentSelector
                ?: return@all
            val target = if (selector.group == "androidx.navigation3" && selector.module == "navigation3-ui") {
                "org.jetbrains.androidx.navigation3:navigation3-ui:$desktopNavigation3UiVersion"
            } else {
                val (group, version) = desktopComposeGroups[selector.group] ?: return@all
                val resolved =
                    if (selector.module.startsWith("material-icons-")) desktopIconsVersion else version
                "$group:${selector.module}:$resolved"
            }
            val platformKey = configuration.attributes.keySet()
                .firstOrNull { it.name == "org.jetbrains.kotlin.platform.type" }
                ?: return@all
            if (configuration.attributes.getAttribute(platformKey)?.toString() != "jvm") return@all
            useTarget(
                target,
                "desktop JVM classpaths link JetBrains Compose (PLAN-desktop-d1-renderers.md)",
            )
        }
    }
}
