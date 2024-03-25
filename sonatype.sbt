sonatypeProfileName := "org.appliedtopology"
publishMavenStyle := true

description := "A research library for Persistent Homology on the JVM"
licenses := List("MIT" -> new URL("https://opensource.org/license/mit"))
homepage := Some(url("https://appliedtopology.github.io/tda4j"))

import xerial.sbt.Sonatype.*
sonatypeProjectHosting := Some(
  GitHubHosting("appliedtopology", "tda4j", "mikael@johanssons.org")
)
