plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
