// Maven Central metadata for the published `tda4j` artifact (build-wide, `ThisBuild`).
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

ThisBuild / description := "A library for topological data analysis (TDA), on the JVM and in JavaScript."

ThisBuild / licenses := List("MIT" -> uri("https://opensource.org/license/mit"))
ThisBuild / homepage := Some(uri("https://appliedtopology.github.io/tda4j"))

ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")

// Snapshots go to Central's Nexus, which asks for credentials under the realm "Sonatype Nexus Repository Manager"; sbt
// sends a token only to the realm it was given for, so the realm "Sonatype Central" of the file above (the one `sonaUpload`
// uses) is refused there with a 401. `snapshot.yml` has the Central Portal user token in these two variables, and a local
// `sbt publish` of a snapshot needs them too (RELEASE.md, "Snapshots").
ThisBuild / credentials ++= (for {
  user <- sys.env.get("SONATYPE_USERNAME")
  password <- sys.env.get("SONATYPE_PASSWORD")
} yield Credentials("Sonatype Nexus Repository Manager", "central.sonatype.com", user, password)).toSeq
