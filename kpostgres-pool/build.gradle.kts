import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "pw.binom.db"
version = rootProject.version.toString()

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            // Ядро драйвера: PgConnectionAdapter оборачивает PostgresClient.
            api(project(":"))
            // Контракты kdatabase: Connection, PreparedStatement, ResultSet, пул.
            api("pw.binom.db:kdatabase:0.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

publishing {
    repositories { mavenLocal() }
}
