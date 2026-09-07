// POC 3 keeps the proven wire/app pair intact and grows architecture-template
// style core modules around the future standalone kotlin-ebp library.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "jetpacs-companion"
include(":ebp-kmp")
include(":wire")
include(":app")
include(":core:model")
include(":core:database")
include(":core:ebp-store")
include(":core:data")
include(":core:navigation")
include(":core:testing")
include(":renderer:model")
include(":renderer:compose")
include(":renderer:glance")
include(":renderer:jetpacs")
include(":renderer:material3")

// External checkouts live under one explicitly configured collection. Only
// the named modules below enter the build; the parent directory is not a module.
val repositoriesRoot = providers.environmentVariable("JETPACS_REPOSITORIES_ROOT")
    .orNull?.let(::file)
    ?: listOf(file("../.."), file("../../..")).firstOrNull {
        it.resolve("ebp/SPEC.md").isFile
    }
    ?: error("Set JETPACS_REPOSITORIES_ROOT to the directory containing the EBP checkouts")
gradle.extra["jetpacs.repositoriesRoot"] = repositoriesRoot.canonicalFile

// This POC is a workspace composition root.  The stable project paths keep
// existing type-safe accessors intact while source authority lives in local
// modules or the neighboring repositories named here.
project(":ebp-kmp").projectDir = repositoriesRoot.resolve("ebp-kmp/ebp-kmp")
project(":wire").projectDir = repositoriesRoot.resolve("ebp-kmp/wire")
project(":renderer").projectDir = repositoriesRoot.resolve("ebp-compose/renderer")
project(":renderer:model").projectDir = repositoriesRoot.resolve("ebp-compose/renderer/model")
project(":renderer:compose").projectDir = repositoriesRoot.resolve("ebp-compose/renderer/compose")
project(":renderer:glance").projectDir = file("renderer/glance")
project(":renderer:jetpacs").projectDir =
    file("../jetpacs-components/renderer/jetpacs")
project(":renderer:material3").projectDir =
    file("renderer/material3")
