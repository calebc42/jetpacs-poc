// :renderer:material3 is Kotlin Multiplatform with an Android and a JVM target
// (docs/PLAN-desktop-d1-renderers.md). commonMain keeps Google's Compose
// coordinates; this module is compiled only by the Companion build, which
// substitutes JetBrains' desktop builds on every JVM classpath, so jvmMain may
// use desktop API and `jvmTest` runs the plain tests in commonTest against
// JetBrains' jars. Compose UI tests on the JVM live in :desktop, which carries
// the Skiko runtime. The screenshot suite lives in ../material3-screenshots.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.calebc42.jetpacs.material3"
        compileSdk = 37
        minSdk = 34
        // Robolectric: the JVM golden replay and component tests compose the
        // real Material renderer without a device (companion/TESTING.md).
        androidResources { enable = true }
        withHostTestBuilder {}.configure { isIncludeAndroidResources = true }
        // The device tests keep their own source-set tree: commonTest holds
        // the plain JVM tests that read this checkout's files, which belong
        // on jvmTest and testAndroidHostTest, never on a phone.
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    compilerOptions {
        freeCompilerArgs.add(
            "-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.renderer.compose)
            implementation(projects.wire)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.compose.material3)
            implementation(libs.androidx.compose.foundation)
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.material.icons)
            implementation(libs.androidx.adaptive)
            implementation(libs.androidx.adaptive.layout)
            implementation(libs.androidx.adaptive.navigation)
            implementation(libs.kotlinx.serialization.json)
        }
        // Android-only dependencies stay on the Android classpath exactly as
        // the Android library declared them.
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.compose.ui.tooling.preview)
        }
        // Plain JUnit tests run on both `jvmTest` and `testAndroidHostTest`;
        // androidHostTest keeps the Robolectric ones.
        commonTest.dependencies {
            implementation(libs.junit)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(projects.renderer.material3Testing)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.robolectric)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(projects.renderer.material3Testing)
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.androidx.test.espresso.core)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
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

tasks.withType<Test>().configureEach {
    // The golden corpus (`goldens/widgets.golden`) is read from the sibling
    // `ebp` checkout, exactly as `:renderer:glance` and `:wire` do.
    systemProperty("ebp.dir", (gradle.extra["jetpacs.repositoriesRoot"] as File).resolve("ebp").absolutePath)
}
