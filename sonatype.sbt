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
// Local dev: sbt-sonatype's own documented convention is a credentials file at `~/.sbt/sonatype_credentials`
// (sbt's native four-line Credentials format: `realm=...`/`host=...`/`user=...`/`password=...`, NOT Maven's
// `~/.m2/settings.xml` -- sbt has no built-in parser for that XML format, so a copy there is inert for this
// build specifically, whatever else might read it). When that file exists, it's used and the env-var path
// below is skipped entirely -- CI has no such file (only injected secrets), so it always falls back to
// SONATYPE_USERNAME/SONATYPE_PASSWORD. The file's own `host=` line must say `central.sonatype.com`, matching
// `sonatypeCredentialHost` above (not an old OSSRH host, if the file predates the Central Portal migration),
// and its `realm=` should say `Sonatype Central` to match the env-var branch's own realm exactly -- not
// verified end-to-end from this sandbox (no such file here to test against); confirm locally with a
// low-stakes check (e.g. `sbt sonatypeCredentials` or a dry-run upload) before trusting it for a real release.
credentials ++= {
  val local = Path.userHome / ".sbt" / "sonatype_credentials"
  if (local.exists())
    Seq(Credentials(local))
  else
    Seq(
      Credentials(
        "Sonatype Central",
        sonatypeCredentialHost.value,
        sys.env.getOrElse("SONATYPE_USERNAME", ""),
        sys.env.getOrElse("SONATYPE_PASSWORD", "")
      )
    )
}

// sbt-pgp reads this for `publishSigned`. Unset locally, sbt-pgp falls back to prompting (or the local gpg
// agent's own cached passphrase); CI has no terminal to prompt at, so this must be set there.
pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
