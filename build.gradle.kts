import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("multiplatform") version "1.9.20"
    java
    `java-library`
    `java-library-distribution`
    id("org.jetbrains.dokka") version "1.9.10"
    id("io.kotest.multiplatform") version "5.7.2"
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.appliedtopology"
version = "0.1-DEV"

distributions {
    main {
        distributionBaseName = "tda4j"
    }
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/mipt-npm/p/sci/maven")
}

kotlin {
    jvmToolchain(17)

    jvm {
        // We have to configure the shadowJar task explicitly, because
        // we are doing a multi-platform build
        // https://stackoverflow.com/questions/63426211/kotlin-multiplatform-shadowjar-gradle-plugin-creates-empty-jar
        compilations.named("main") {
            tasks {
                val shadowJar =
                    register<ShadowJar>("ShadowJar") {
                        group = "distribution"
                        from(output)
                        configurations = listOf(runtimeDependencyFiles)
                        archiveClassifier.set("all")
                        dependsOn("jvmJar")
                        dependsOn("jvmMainClasses")
                    }
            }
        }
    }

    js(IR) {
        browser()
        nodejs()
    }

    val hostOS = System.getProperty("os.name")
    when {
        hostOS.startsWith("Windows") -> mingwX64("native")
        hostOS == "Linux" -> linuxX64("native")
        else -> println("Not configured to build targeting native platform $hostOS")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.arrow-kt:arrow-core:1.2.0")
                implementation("space.kscience:kmath-core:0.3.1")
                implementation("space.kscience:kmath-tensors:0.3.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation("io.kotest:kotest-framework-engine:5.7.2")
                implementation("io.kotest:kotest-assertions-core:5.7.2")
                implementation("io.kotest:kotest-property:5.7.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("io.kotest:kotest-runner-junit5:5.7.2")
            }
        }
    }
}

tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    dependencies {
        dokkaPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:1.9.10")
        dokkaPlugin("org.jetbrains.dokka:mathjax-plugin:1.9.10")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set("1.0.1")
    debug.set(true)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    outputColorName.set("RED")
    ignoreFailures.set(true)
    enableExperimentalRules.set(true)
    additionalEditorconfig.set(
        // not supported until ktlint 0.49
        mapOf(
            "ktlint_standard_no-wildcard-imports" to "disabled",
        ),
    )
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}
