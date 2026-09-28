name := "tda4j"
organization := "org.appliedtopology"
scalaVersion := "3.9.0"

versionScheme := Some("semver-spec")

assembly / assemblyJarName := s"${name.value}-${version.value}-assembly.jar"

libraryDependencies += "org.specs2"        %% "specs2-core"                   % "5.5.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-matcher-extra"          % "5.5.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-scalacheck"             % "5.5.1" % "test"
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.1"
libraryDependencies += "org.apache.commons" % "commons-math3"                 % "3.6.1"
libraryDependencies += "com.eatthepath"     % "jvptree"                       % "0.2"
libraryDependencies += "com.dreizak"        % "miniball"                      % "1.0.3"
libraryDependencies +=
  "org.scala-lang.modules"              %% "scala-parallel-collections" % "1.0.4"
libraryDependencies += "org.scalacheck" %% "scalacheck"                 % "1.17.0" % "test"
libraryDependencies += "org.rogach"     %% "scallop"                    % "6.0.0"

// Docs versioning (RELEASE.md step 5): `release.yml` sets TDA4J_DOCS_VERSION to the tag's version
// (e.g. "0.1.3") when publishing a tagged release; `docs.yml`'s push-to-`scala` build leaves it unset, which
// publishes under the "dev" path segment instead of colliding with a real release's own directory. Both
// workflows' own "Stage versioned docs for publish" step nests this build's output under that path and
// merges in whatever version directories already exist on `gh-pages` before publishing -- `sbt-github-pages`
// has no setting to keep remote-only files, so any directory this build doesn't already contain, and doesn't
// restore itself, would be lost on the next publish.
val docsVersion = sys.env.getOrElse("TDA4J_DOCS_VERSION", "dev")

Compile / doc / scalacOptions ++= Seq(
  "-siteroot",
  baseDirectory.value.toString,
  "-project",
  name.value,
  "-project-version",
  docsVersion,
  "-source-links",
  "github://appliedtopology/tda4j/scala",
  "-Yapi-subdirectory",
  "-project-logo",
  "_assets/images/header-icon.svg",
  "-doc-canonical-base-url",
  "https://tda4j.appliedtopology.org",
  "-social-links:github::https://github.com/appliedtopology/tda4j"
)
Compile / doc / target := target.value / "api"

// Older release tags, oldest-first exclusion of the one being (re)published -- drives Laika's version
// switcher. Reads git tags directly rather than hand-maintaining a list; a tag with no matching docs
// directory on `gh-pages` yet (or one from before docs versioning existed) just won't have a working link
// until it's actually published once.
def priorReleaseVersions(baseDir: File): Seq[String] = {
  import scala.sys.process._
  scala.util
    .Try(Process(Seq("git", "tag", "--list", "v*", "--sort=-v:refname"), baseDir).!!)
    .getOrElse("")
    .linesIterator
    .toList
    .map(_.stripPrefix("v"))
    .filterNot(_ == docsVersion)
}

// Compiler options: language features (implicitConversions, adhocExtensions) and warning flags.
scalacOptions ++= List(
  "-source:future",
  "-language:experimental.modularity",
  "-language:implicitConversions",
  "-language:adhocExtensions",
  "-feature",
  "-deprecation",
  "-unchecked"
)

// Scala 3.9.0's own bundled scaladoc ships a `ux.js` that intercepts every same-origin link click
// (sidebar navigation included) to do its own SPA-style AJAX page swap via `$.get(href, ...)` -- but
// no page anywhere loads jQuery, so `$` is undefined. The click's own `e.preventDefault()` already
// ran by the time that throws, so the click's default navigation is cancelled AND the replacement
// AJAX navigation never happens: clicking a class in the API nav does nothing (confirmed against a
// real browser: `ReferenceError: $ is not defined` at ux.js:180, `HTMLAnchorElement` click handler).
// A real upstream scaladoc bug, not a Laika/tda4j config issue -- `$.get(url, cb)` is a drop-in match
// for `fetch(url).then(r => r.text()).then(cb)` (the callback only ever receives raw HTML text here),
// so patch the one call site post-generation rather than vendoring scaladoc's bundled JS ourselves.
//
// Patches `Compile / doc`'s own output directory (confirmed via `show Compile/doc`:
// `target/scala-3.9.0/api`), not `laikaSite`'s copy of it -- `laikaPreview` runs a live preview
// server (`startPreviewServer`/`buildPreviewServer` in sbt-laika's `Tasks.scala`) that is a
// completely separate task graph from `laikaSite`/`generate`, so a `laikaSite`-only patch is invisible
// there (confirmed: `laikaPreview`'s served `ux.js` was still unpatched). Patching at the actual
// source once means every consumer of `Compile / doc`'s output -- `laikaSite`'s own API-copy step
// included -- sees the fix, with no need to patch each consumer separately. (An idiomatic sbt task
// augmentation, not a self-referential cycle: `key := f(key.value)` captures the plugin/sbt-provided
// task, same mechanism `+=`/`++=` desugar to.) See .claude/WORKLOG-docs-site-fixes.md.
Compile / doc := {
  val apiDir = (Compile / doc).value
  val uxJs = apiDir / "scripts" / "ux.js"
  if (uxJs.exists()) {
    val original = IO.read(uxJs)
    val patched = original.replace(
      "$.get(href, function (data) {",
      "fetch(href).then((r) => r.text()).then(function (data) {"
    )
    if (patched != original) IO.write(uxJs, patched)
  }
  apiDir
}

// Patch the favicon generation
Compile / doc := {
  val docDir = (Compile / doc).value
  val allHTML = (docDir ** "*.html").get()
  allHTML.filter(_.isFile).foreach { file =>
    val content = sbt.IO.read(file)
    val patched = content.replace(
      "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"favicon.ico\">",
      "<link rel=\"shortcut icon\" type=\"image/svg+xml\" href=\"/images/header-icon.svg\"/>"
    )
    if (patched != content) sbt.IO.write(file, patched)
  }
  docDir
}

// Both settings are needed, not just one: `Compile / mainClass` is what `sbt run` uses; `assembly /
// mainClass` is what sbt-assembly writes into the fat jar's manifest (`java -jar ... `). Neither is inferred
// from the other.
Compile / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")
assembly / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

mimaPreviousArtifacts := priorReleaseVersions(baseDirectory.value)
  .filter(v => !v.startsWith("0.1"))
  .map(v => organization.value %% name.value % v)
  .toSet
