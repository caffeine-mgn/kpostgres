import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // @Serializable в тестах: сущности объявлены здесь же, без плагина
    // компилятор не сгенерирует сериализаторы, и .serializer() не найдётся.
    alias(libs.plugins.kotlin.serialization)
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
            // Адаптер пула: ORM работает поверх Connection из kdatabase.
            api(project(":kpostgres-pool"))
            // ORM: аннотации, генерация SQL, Orm.
            api("pw.binom.db:korm:0.1.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Сквозной тест: корутины и kotlin-test для runTest.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

publishing {
    repositories { mavenLocal() }
}
