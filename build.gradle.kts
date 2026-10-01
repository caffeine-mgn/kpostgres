plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.nexus.publish)
    `maven-publish`
    signing
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

group = "com.subochev"

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "kpostgres"
        pom {
            name = "kpostgres"
            description = "Multiplatform Postgres client over Ktor CIO + kotlinx-io, supporting JVM, iOS, macOS, Linux and Windows native targets."
            url = "https://github.com/subochev/kpostgres"
            inceptionYear = "2026"

            licenses {
                license {
                    name = "Apache License 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0"
                }
            }

            developers {
                developer {
                    id = "subochev"
                    name = "Subochev"
                }
            }

            scm {
                connection = "scm:git:git://github.com/subochev/kpostgres.git"
                developerConnection = "scm:git:ssh://git@github.com/subochev/kpostgres.git"
                url = "https://github.com/subochev/kpostgres"
            }
        }
    }
}

if (project.findProperty("signingUseGpg") == "true") {
    signing {
        useGpgCmd()
    }
}

nexusPublishing {
    packageGroup = "com.subochev"
    repositories {
        sonatype {
            nexusUrl.set(uri("https://oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://oss.sonatype.org/content/repositories/snapshots/"))
            username.set((project.findProperty("mavenCentralUsername") as String?) ?: "")
            password.set((project.findProperty("mavenCentralPassword") as String?) ?: "")
        }
    }
}