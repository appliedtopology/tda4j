// Maven Central metadata. Build-wide (`ThisBuild`) so every published module (`tda4j`, `tda4j-plot`) carries the same
// POM fields, stages into the same bundle and uses the same credentials; each module sets its own `description`.
ThisBuild / publishMavenStyle := true

ThisBuild / organizationName := "Applied Topology"
ThisBuild / organizationHomepage := Some(uri("https://appliedtopology.org"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/appliedtopology/tda4j"),
    "scl:git@github.com:appliedtopology/tda4j.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "michiexile",
    name = "Mikael Vejdemo-Johansson",
    email = "michiexile@gmail.com",
    url = uri("https://mikael.johanssons.org")
  )
)

description := "A Java Platform compatible library for topological data analysis (TDA)."

ThisBuild / licenses := List("MIT" -> uri("https://opensource.org/license/mit"))
ThisBuild / homepage := Some(uri("https://appliedtopology.github.io/tda4j"))

ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")
