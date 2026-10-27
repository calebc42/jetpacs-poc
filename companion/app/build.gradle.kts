import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

// AGP 9 carries built-in Kotlin; only the compose compiler plugin is added.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The bundled flavor's engine: an AAR built by the sibling jetpacs-emacs-engine
// repository from the pristine Emacs release (never from a modified tree).
// gradle/engine.lock pins its identity and SHA-256; JETPACS_ENGINE_AAR
// overrides the location. Only bundled variants need it, and they refuse to
// build without it rather than failing later on an unresolved class.
val engineLock: Map<String, String> = rootProject.file("gradle/engine.lock").readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
val engineId: String = engineLock.getValue("engine")
val engineAar: File = providers.environmentVariable("JETPACS_ENGINE_AAR").orNull?.let(::file)
    ?: (gradle.extra["jetpacs.repositoriesRoot"] as File)
        .resolve("jetpacs-emacs-engine/dist/jetpacs-emacs-engine-$engineId.aar")
val verifyBundledEngine = tasks.register("verifyBundledEngine") {
    description = "Checks the pinned engine AAR exists and matches gradle/engine.lock"
    doLast {
        check(engineAar.isFile) {
            "The bundled flavor needs the engine AAR $engineAar: build " +
                "jetpacs-emacs-engine (tools/build-all.sh) or set JETPACS_ENGINE_AAR"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(engineAar.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == engineLock["sha256"]) {
            "Engine AAR $engineAar has SHA-256 $digest, gradle/engine.lock pins ${engineLock["sha256"]}"
        }
    }
}

android {
    namespace = "com.calebc42.jetpacs.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.calebc42.jetpacs.companion"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-w4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions {
        managedDevices {
            localDevices {
                create("pixel2Api34") {
                    device = "Pixel 2"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { buildConfig = true }

    // docs/PLAN-bundled-emacs.md: one codebase, two distributions. `standalone`
    // is today's app, paired with an Emacs the user installs; `bundled` carries
    // GNU Emacs inside the APK as an engine process. Distinct application IDs
    // let both sit on one device.
    flavorDimensions += "engine"
    productFlavors {
        create("standalone") {
            dimension = "engine"
            isDefault = true
        }
        create("bundled") {
            dimension = "engine"
            applicationIdSuffix = ".bundled"
            versionNameSuffix = "-bundled"
            // The engine ships arm64-v8a and x86_64 only; without the filter a
            // 32-bit device could install an APK whose engine never loads.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            // The engine identity the dump cache is keyed on (gradle/engine.lock).
            buildConfigField("String", "ENGINE_ID", "\"$engineId\"")
        }
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
    abstract val bloomDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputDirectory
    abstract val orgzlyDir: org.gradle.api.file.DirectoryProperty

    @get:InputDirectory
    abstract val harpDir: org.gradle.api.file.DirectoryProperty

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
        copyElispSources(bloomDir.get().asFile, File(apps, "bloom"))
        copyElispSources(orgzlyDir.get().asFile, File(apps, "orgzly"))
        copyElispSources(harpDir.get().asFile, File(apps, "harp"))
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

/**
 * E6 (docs/PLAN-bundled-emacs.md): the bundled engine loads the kit from its
 * own HOME, so the kit's Elisp is byte-compiled at build time by a host Emacs
 * of the engine's major version (`JETPACS_HOST_EMACS`, else `emacs` on PATH)
 * into a second generated asset root AGP merges beside the sources. A host
 * without such an Emacs skips the step with a warning and the device loads
 * the sources, slower but identical.
 */
abstract class ByteCompileOnboardingKit : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val stagedDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val script: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val emacs: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val engineMajor: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @get:javax.inject.Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @org.gradle.api.tasks.TaskAction
    fun compile() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val version = runCatching {
            val bytes = ByteArrayOutputStream()
            execOperations.exec {
                commandLine(emacs.get(), "--version")
                standardOutput = bytes
                isIgnoreExitValue = true
            }
            Regex("GNU Emacs (\\d+)").find(bytes.toString())?.groupValues?.get(1)
        }.getOrNull()
        if (version != engineMajor.get()) {
            logger.warn("kit byte-compile skipped: ${emacs.get()} is Emacs ${version ?: "absent"}, the engine is ${engineMajor.get()}")
            return
        }
        val payload = stagedDir.get().asFile.resolve("jetpacs-onboarding/payload")
        val target = out.resolve("jetpacs-onboarding/payload")
        execOperations.exec {
            commandLine(
                emacs.get(), "-Q", "--batch", "-l", script.get().asFile.path,
                "--", payload.path, target.path,
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val repo = rootProject.layout.projectDirectory.dir("..")
        val repositoriesRoot = gradle.extra["jetpacs.repositoriesRoot"] as File
        val stage = tasks.register(
            "stage${variant.name.replaceFirstChar(Char::uppercase)}OnboardingAssets",
            StageOnboardingAssets::class,
        ) {
            emacsDir.set(repo.dir("emacs"))
            ebpElDir.set(repositoriesRoot.resolve("ebp.el/lisp"))
            ebpOrgDir.set(repositoriesRoot.resolve("ebp-org/lisp"))
            jetpacsComponentsDir.set(
                rootProject.file("../jetpacs-components/lisp/jetpacs-components"),
            )
            jetpacsAuthoringDir.set(repositoriesRoot.resolve("jetpacs-authoring/lisp"))
            jetpacsAutomationsDir.set(rootProject.file("../jetpacs-automations/lisp"))
            jetpacsComponentCatalogDir.set(rootProject.file("../jetpacs-component-catalog/lisp"))
            glasspaneDir.set(repositoriesRoot.resolve("glasspane"))
            bloomDir.set(repositoriesRoot.resolve("bloom-native/elisp"))
            orgzlyDir.set(repositoriesRoot.resolve("orgzly-native/emacs/apps/orgzly"))
            harpDir.set(repositoriesRoot.resolve("harp-native/emacs/apps/harp"))
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
        if (variant.flavorName == "bundled") {
            val compile = tasks.register(
                "compile${variant.name.replaceFirstChar(Char::uppercase)}OnboardingKit",
                ByteCompileOnboardingKit::class,
            ) {
                stagedDir.set(stage.flatMap { it.outputDir })
                script.set(layout.projectDirectory.file("kit-byte-compile.el"))
                emacs.set(providers.environmentVariable("JETPACS_HOST_EMACS").orElse("emacs"))
                engineMajor.set(engineId.substringBefore("."))
                outputDir.set(layout.buildDirectory.dir("generated/onboardingKitElc/${variant.name}"))
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                compile, ByteCompileOnboardingKit::outputDir)
        }
    }
}

androidComponents {
    // Without the pinned AAR the bundled variants do not exist, so the
    // unflavored anchors (assembleDebug, lint, test) keep working on any
    // machine; with it present verifyBundledEngine still checks the digest.
    beforeVariants(selector().withFlavor("engine" to "bundled")) { variant ->
        if (!engineAar.isFile) {
            logger.lifecycle("engine AAR $engineAar absent: variant ${variant.name} disabled")
            variant.enable = false
        }
    }
    onVariants(selector().withFlavor("engine" to "bundled")) { variant ->
        // Emacs execs libexec1.so and libloader.so from nativeLibraryDir, so
        // the engine's libraries must be real files, not pages of the APK.
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
        val preBuild = "pre${variant.name.replaceFirstChar(Char::uppercase)}Build"
        tasks.configureEach { if (name == preBuild) dependsOn(verifyBundledEngine) }
    }
}

dependencies {
    implementation(projects.ebpKmp)
    implementation(projects.wire)
    implementation(projects.renderer.material3)
    implementation(projects.renderer.glance)
    implementation(projects.renderer.jetpacs)
    implementation(projects.core.navigation)
    implementation(projects.core.database)
    implementation(projects.core.ebpStore)
    implementation(projects.host)
    "bundledImplementation"(files(engineAar))
    implementation(libs.androidx.room3.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
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
    androidTestImplementation(projects.renderer.material3Testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose UI Test 1.12 still requests Espresso 3.5.0 transitively. Pin the
    // matching AndroidX Test 1.7 line so instrumentation also runs on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
