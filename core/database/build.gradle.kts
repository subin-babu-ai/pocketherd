plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "dev.pocketherd.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
