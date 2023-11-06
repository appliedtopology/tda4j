import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform") version "1.7.10"
    id("org.jetbrains.dokka") version "1.9.10"
    id("io.kotest.multiplatform") version "5.7.2"
    id("com.google.devtools.ksp") version "1.8.21-1.0.11"
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
}

group = "org.appliedtopology"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/mipt-npm/p/sci/maven")
}

tasks.withType<DokkaTask>().configureEach {
    dependencies {
        dokkaPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:1.9.10")
        dokkaPlugin("org.jetbrains.dokka:mathjax-plugin:1.9.10")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    js(IR) {
        browser {
            commonWebpackConfig {
                // cssSupport.enabled = true
            }
        }
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        // hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> println("Host OS is not supported in Kotlin/Native.")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("script-runtime"))
                implementation(platform("io.arrow-kt:arrow-stack:1.2.0"))
                implementation("io.arrow-kt:arrow-core")
                implementation("io.arrow-kt:arrow-fx-coroutines")
                implementation("io.arrow-kt:arrow-optics")
                api("space.kscience:kmath-core:0.3.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation("io.kotest:kotest-framework-engine:5.7.2")
                implementation("io.kotest:kotest-assertions-core:5.7.2")
                implementation("io.kotest:kotest-property:5.7.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("script-runtime"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("io.kotest:kotest-runner-junit5:5.7.2")
            }
        }
    }
}
