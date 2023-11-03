import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform") version "1.7.10"
    id("org.jetbrains.dokka") version "1.9.10"
}

group = "org.appliedtopology"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}


tasks.withType<DokkaTask>().configureEach {
    dependencies {
        dokkaPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:1.9.10")
        dokkaPlugin("org.jetbrains.dokka:mathjax-plugin:1.9.10")
        dokkaPlugin("com.glureau:html-mermaid-dokka-plugin:0.4.5")
    }
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    js(BOTH) {
        browser {
            commonWebpackConfig {
                cssSupport.enabled = true
            }
        }
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    sourceSets {
        val commonMain by getting {

        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
