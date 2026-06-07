publishMavenStyle := true

organizationName := "Applied Topology"
organizationHomepage := Some(url("https://appliedtopology.org"))

scmInfo := Some(
  ScmInfo(
    url("https://github.com/appliedtopology/tda4j"),
    "scl:git@github.com:appliedtopology/tda4j.git"
  )
)

developers := List(
  Developer(
    id = "michiexile",
    name = "Mikael Vejdemo-Johansson",
    email = "michiexile@gmail.com",
    url = url("https://mikael.johanssons.org")
  )
)

description := "A Java Platform compatible library for topological data analysis (TDA)."

licenses := List("MIT" -> url("https://opensource.org/license/mit"))
homepage := Some(url("https://appliedtopology.github.io/tda4j"))

pomIncludeRepository := { _ => false }

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
