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
