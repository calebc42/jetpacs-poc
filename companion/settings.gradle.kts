// The companion's Gradle root: the pure-JVM :wire protocol module, the
// :app Android module that hosts it on the device, and the :host headless
// JVM loopback host (RF-2.6) that hosts it for tests and desktop rungs.
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
rootProject.name = "ebp-companion"
include(":wire")
include(":app")
include(":host")
