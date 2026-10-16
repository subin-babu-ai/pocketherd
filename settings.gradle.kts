pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocketherd"

include(":app")
include(":core:model")
include(":core:ui")
include(":core:database")
include(":core:ssh")
include(":core:protocol")
include(":domain")
include(":data")
include(":feature:machines")
include(":feature:agents")
include(":feature:pane")
