// Maven Central metadata for the published `tda4j` artifact (build-wide, `ThisBuild`).
import java.nio.file.Files
import java.util.Properties
import scala.util.{Try, Using}

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

// The token Central's repositories take (RELEASE.md, "Credentials and secrets"): `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`
// when both are set (that is how `snapshot.yml` has it), otherwise the credentials file. One credential serves `sonaUpload`
// (sbt looks it up by host) and a snapshot publish, but sbt sends a token only to the realm it was made for, and Central's
// snapshot repository (a Nexus) asks for "Sonatype Nexus Repository Manager": a file that says `realm=Sonatype Central` would
// get a 401 there, so the token is registered under the Nexus realm whichever of the two it came from. A file that is
// missing, unreadable or for another host contributes nothing.
ThisBuild / credentials ++= {
  val host = "central.sonatype.com"
  val fromEnv = for {
    user <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield (user, password)
  // The file's four keys (`realm`, `host`, `user`, `password`), read as sbt reads them.
  def fromFile = Try {
    val properties = Properties()
    Using.resource(Files.newBufferedReader((Path.userHome / ".sbt" / "sonatype_credentials").toPath))(
      properties.load(_)
    )
    (properties.getProperty("host"), properties.getProperty("user"), properties.getProperty("password"))
  }.toOption.collect { case (`host`, user, password) if user != null && password != null => (user, password) }
  fromEnv
    .orElse(fromFile)
    .map((user, password) => Credentials("Sonatype Nexus Repository Manager", host, user, password))
    .toSeq
}
