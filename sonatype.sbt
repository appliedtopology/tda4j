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

// Central Portal (not the legacy OSSRH host) -- sonaUpload/sonaRelease (release.sbt) and publishSigned
// (sbt-pgp) both read credentials via this host. Token comes from a Central Portal user token, not an
// Sonatype OSSRH username/password -- generate it from the Central Portal account page.
sonatypeCredentialHost := "central.sonatype.com"
credentials += Credentials(
  "Sonatype Central",
  sonatypeCredentialHost.value,
  sys.env.getOrElse("SONATYPE_USERNAME", ""),
  sys.env.getOrElse("SONATYPE_PASSWORD", "")
)

// sbt-pgp reads this for `publishSigned`. Unset locally, sbt-pgp falls back to prompting (or the local gpg
// agent's own cached passphrase); CI has no terminal to prompt at, so this must be set there.
pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
