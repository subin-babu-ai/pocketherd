plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.pocketherd.core.ssh"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    packaging {
        resources.excludes += setOf("META-INF/*", "**/module-info.class")
    }
}
dependencies {
    api(libs.sshj)
    implementation(libs.bouncycastle)
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
