import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

val releaseVersion: String = (project.findProperty("version") as String?)
    ?: (System.getenv("GITHUB_REF_NAME")?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*")) })
    ?: "0.1.0-SNAPSHOT"
version = releaseVersion

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

group = "pw.binom.db"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "pw.binom.db",
        artifactId = "kpostgres",
        version = project.version.toString(),
    )

    pom {
        name.set("kpostgres")
        description.set("Multiplatform Postgres client over Ktor CIO + kotlinx-io, supporting JVM, iOS, macOS, Linux and Windows native targets.")
        url.set("https://github.com/caffeine-mgn/kpostgres")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("subochev")
                name.set("Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/caffeine-mgn/kpostgres.git")
            developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/kpostgres.git")
            url.set("https://github.com/caffeine-mgn/kpostgres")
        }
    }
}

pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}")
        return@withPlugin
    }
    logger.lifecycle("[signing] No in-memory PGP key configured; publications will be signed by the publishing plugin only.")
}