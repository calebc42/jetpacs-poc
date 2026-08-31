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
include(":renderer:jetpacs")
include(":renderer:material3")

// This POC is a workspace composition root.  The stable project paths keep
// existing type-safe accessors intact while source authority lives in the
// neighboring repositories named here.
project(":ebp-kmp").projectDir = file("../../ebp-poc/ebp-kmp/ebp-kmp")
project(":wire").projectDir = file("../../ebp-poc/ebp-kmp/wire")
project(":renderer").projectDir = file("../../ebp-poc/ebp-compose/renderer")
project(":renderer:model").projectDir = file("../../ebp-poc/ebp-compose/renderer/model")
project(":renderer:compose").projectDir = file("../../ebp-poc/ebp-compose/renderer/compose")
project(":renderer:jetpacs").projectDir =
    file("../../jetpacs-components/renderer/jetpacs")
project(":renderer:material3").projectDir =
    file("../../glasspane-material3/renderer/material3")
