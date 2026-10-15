plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "dev.pocketherd.data"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation(project(":domain"))
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:ssh"))
    implementation(project(":core:database"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
