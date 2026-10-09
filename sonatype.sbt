publishMavenStyle := true

organizationName := "Applied Topology"
organizationHomepage := Some(uri("https://appliedtopology.org"))

scmInfo := Some(
  ScmInfo(
    uri("https://github.com/appliedtopology/tda4j"),
    "scl:git@github.com:appliedtopology/tda4j.git"
  )
)

developers := List(
  Developer(
    id = "michiexile",
    name = "Mikael Vejdemo-Johansson",
    email = "michiexile@gmail.com",
    url = uri("https://mikael.johanssons.org")
  )
)

description := "A Java Platform compatible library for topological data analysis (TDA)."

licenses := List("MIT" -> uri("https://opensource.org/license/mit"))
homepage := Some(uri("https://appliedtopology.github.io/tda4j"))

pomIncludeRepository := { _ => false }

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")
