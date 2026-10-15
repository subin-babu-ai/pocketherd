plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.pocketherd.core.ui"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.ui.graphics)
}
