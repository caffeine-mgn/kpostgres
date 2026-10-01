plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "com.subochev"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm()

    iosArm64()
    iosSimulatorArm64()

    macosArm64()

    linuxX64()
    linuxArm64()

    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.whyoleg.cryptography.core)
            implementation(libs.whyoleg.cryptography.optimal)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.testcontainers.postgresql)
            implementation(libs.testcontainers.junit)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}