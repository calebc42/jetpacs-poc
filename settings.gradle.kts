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
include(":ebp-kmp")
project(":ebp-kmp").projectDir = file("../ebp-poc/ebp-kmp/ebp-kmp")
include(":wire")
project(":wire").projectDir = file("../ebp-poc/ebp-kmp/wire")
include(":renderer:model")
project(":renderer:model").projectDir = file("../ebp-poc/ebp-compose/renderer/model")
include(":renderer:compose")
project(":renderer:compose").projectDir = file("../ebp-poc/ebp-compose/renderer/compose")
include(":renderer:jetpacs")
