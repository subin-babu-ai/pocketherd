plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "dev.pocketherd"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pocketherd"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/*", "**/module-info.class")
    }
}
dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:machines"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:pane"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
