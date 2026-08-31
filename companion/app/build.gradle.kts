import java.io.File

// AGP 9 carries built-in Kotlin; only the compose compiler plugin is added.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.calebc42.ebp.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.calebc42.ebp.companion"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-w4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

/**
 * Package the same onboarding payload used by tools/onboard-tablet.sh.
 *
 * The Companion has no ambient access to Emacs's or Termux's private sandbox.
 * It stages a one-time handoff through MediaStore under Documents. Recommended
 * copies the init entry for the user to paste in Emacs, after which Emacs owns
 * the bootstrap that consumes the payload; Advanced can run the same payload
 * through the device-side shell installer. Keeping the payload as generated
 * assets makes both paths consume the same module and Org trees.
 */
abstract class StageOnboardingAssets : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val emacsDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val ebpElDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val ebpOrgDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val glasspaneMaterial3Dir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val jetpacsComponentsDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val jetpacsAuthoringDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val jetpacsAutomationsDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val jetpacsComponentCatalogDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val glasspaneDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val orgDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val examplesDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val initFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val localEarlyInit: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val termuxEarlyInit: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val initSeam: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val recommendedInstaller: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val installer: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    private fun copyDistribution(source: File, destination: File) {
        source.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf(".git", "__pycache__")
            }
            .filter { file ->
                file.isDirectory ||
                    (!file.name.endsWith(".elc") &&
                        !file.name.startsWith(".#") &&
                        !file.name.endsWith("~") &&
                        !(file.name.startsWith("#") && file.name.endsWith("#")))
            }
            .forEach { file ->
                val target = File(destination, file.relativeTo(source).path)
                if (file.isDirectory) target.mkdirs()
                else {
                    target.parentFile.mkdirs()
                    file.copyTo(target, overwrite = true)
                }
            }
    }

    private fun copyElispSources(source: File, destination: File) {
        source.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf(".git", "docs", "test", "__pycache__")
            }
            .filter { file -> file.isFile && file.extension == "el" }
            .forEach { file ->
                val target = File(destination, file.relativeTo(source).path)
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
    }

    @org.gradle.api.tasks.TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val kit = File(out, "jetpacs-onboarding")
        val payload = File(kit, "payload")
        copyDistribution(emacsDir.get().asFile, File(payload, "emacs"))
        copyElispSources(ebpElDir.get().asFile, File(payload, "emacs"))
        copyElispSources(ebpOrgDir.get().asFile, File(payload, "emacs"))
        val apps = File(payload, "emacs/apps")
        val material = glasspaneMaterial3Dir.get().asFile
        copyElispSources(
            File(material, "glasspane-material3"),
            File(apps, "glasspane-material3"),
        )
        copyElispSources(File(material, "m3-catalog"), File(apps, "m3-catalog"))
        File(material, "jetpacs-m3-catalog.el").copyTo(
            File(apps, "m3-catalog/jetpacs-m3-catalog.el"),
            overwrite = true,
        )
        copyElispSources(
            jetpacsComponentsDir.get().asFile,
            File(apps, "jetpacs-components"),
        )
        copyElispSources(
            jetpacsAuthoringDir.get().asFile,
            File(apps, "jetpacs-authoring"),
        )
        copyElispSources(
            jetpacsAutomationsDir.get().asFile,
            File(apps, "jetpacs-automations"),
        )
        copyElispSources(
            jetpacsComponentCatalogDir.get().asFile,
            File(apps, "jetpacs-component-catalog"),
        )
        copyElispSources(glasspaneDir.get().asFile, File(apps, "glasspane"))
        copyDistribution(orgDir.get().asFile, File(payload, "org"))
        copyDistribution(examplesDir.get().asFile, File(payload, "examples/python"))
        initFile.get().asFile.copyTo(File(payload, "init.el"), overwrite = true)
        val bootstrap = File(payload, "bootstrap").also { it.mkdirs() }
        localEarlyInit.get().asFile.copyTo(
            File(bootstrap, "early-init-local.el"), overwrite = true)
        termuxEarlyInit.get().asFile.copyTo(
            File(bootstrap, "early-init-termux.el"), overwrite = true)
        initSeam.get().asFile.copyTo(File(bootstrap, "init-seam.el"), overwrite = true)
        recommendedInstaller.get().asFile.copyTo(
            File(kit, "install-recommended.el"), overwrite = true)
        installer.get().asFile.copyTo(File(kit, "install-jetpacs.sh"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val repo = rootProject.layout.projectDirectory.dir("..")
        val stage = tasks.register(
            "stage${variant.name.replaceFirstChar(Char::uppercase)}OnboardingAssets",
            StageOnboardingAssets::class,
        ) {
            emacsDir.set(repo.dir("emacs"))
            ebpElDir.set(repo.dir("../ebp-poc/ebp.el/lisp"))
            ebpOrgDir.set(repo.dir("../ebp-poc/ebp-org/lisp"))
            glasspaneMaterial3Dir.set(repo.dir("../glasspane-material3/lisp"))
            jetpacsComponentsDir.set(
                repo.dir("../jetpacs-components/lisp/jetpacs-components"),
            )
            jetpacsAuthoringDir.set(repo.dir("../jetpacs-authoring/lisp"))
            jetpacsAutomationsDir.set(repo.dir("../jetpacs-automations/lisp"))
            jetpacsComponentCatalogDir.set(repo.dir("../jetpacs-component-catalog/lisp"))
            glasspaneDir.set(repo.dir("../glasspane"))
            orgDir.set(repo.dir("org"))
            examplesDir.set(repo.dir("device/py"))
            initFile.set(repo.file("device/init.el"))
            localEarlyInit.set(repo.file("device/early-init-local.el"))
            termuxEarlyInit.set(repo.file("device/early-init-termux.el"))
            initSeam.set(repo.file("device/init-seam.el"))
            recommendedInstaller.set(repo.file("device/install-recommended.el"))
            installer.set(repo.file("tools/onboard-provision-remote.sh"))
            outputDir.set(layout.buildDirectory.dir("generated/onboardingAssets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            stage, StageOnboardingAssets::outputDir)
    }
}

dependencies {
    implementation(projects.wire)
    implementation(projects.renderer.material3)
    implementation(projects.renderer.jetpacs)
    implementation(projects.core.navigation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    implementation(libs.androidx.adaptive.navigation3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose UI Test 1.12 still requests Espresso 3.5.0 transitively. Pin the
    // matching AndroidX Test 1.7 line so instrumentation also runs on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
