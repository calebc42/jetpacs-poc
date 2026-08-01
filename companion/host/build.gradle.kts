// RF-2.6: the headless JVM loopback host — CompanionEngine + Memory stores +
// a ServerSocket, no Android. Reuses the multiplatform plugin with a single
// jvm() target (per the ratified decision: no new catalog plugin alias), so
// this module and :wire share one toolchain story.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(projects.wire)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

private val mainClassFqn = "com.calebc42.ebp.host.HostKt"

// `./gradlew :host:run --args='--port 0 --kat'` for interactive use. The ERT
// launcher does NOT use this: a JavaExec child belongs to the Gradle daemon's
// process tree, so killing the launcher would orphan the host. Tests launch
// the fat jar instead (a direct child Emacs can signal).
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the headless loopback host"
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    mainClass.set(mainClassFqn)
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
}

// Self-contained jar for the EBP_HOST_LAUNCH contract:
//   java -jar companion/host/build/libs/host-all.jar --port 0 --kat
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Self-contained host jar (host + :wire + kotlinx runtime)"
    archiveBaseName.set("host")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to mainClassFqn) }
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    from(main.output.allOutputs)
    from(project.provider {
        main.runtimeDependencyFiles.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named<Test>("jvmTest") { useJUnit() }
tasks.register("test") { dependsOn("jvmTest") }
