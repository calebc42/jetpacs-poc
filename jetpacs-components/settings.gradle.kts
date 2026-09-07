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
rootProject.name = "jetpacs-components"
val repositoriesRoot = providers.environmentVariable("JETPACS_REPOSITORIES_ROOT")
    .orNull?.let(::file) ?: file("../..")
include(":ebp-kmp")
project(":ebp-kmp").projectDir = repositoriesRoot.resolve("ebp-kmp/ebp-kmp")
include(":wire")
project(":wire").projectDir = repositoriesRoot.resolve("ebp-kmp/wire")
include(":renderer:model")
project(":renderer:model").projectDir = repositoriesRoot.resolve("ebp-compose/renderer/model")
include(":renderer:compose")
project(":renderer:compose").projectDir = repositoriesRoot.resolve("ebp-compose/renderer/compose")
include(":renderer:jetpacs")
