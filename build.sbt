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
// CLI argument parsing for the `cli` package -- chosen over decline specifically because it has zero transitive
// dependencies (decline pulls in cats-core, which nothing else in this codebase uses) -- see
// .claude/WORKLOG-cli-executable.md.
libraryDependencies += "org.rogach" %% "scallop" % "6.0.0"

import laika.helium.Helium
import laika.helium.config.{Favicon, HeliumIcon, IconLink, ImageLink}
import laika.theme.config.{Color, Font, FontStyle, FontWeight}
import laika.ast.Image
import laika.format.Markdown
import laika.ast.Path.Root
import laika.config.{ApiLinks, LinkConfig, SourceLinks, Version, Versions}
import laika.helium.config.VersionMenu

// Docs versioning (RELEASE.md step 5): `release.yml` sets TDA4J_DOCS_VERSION to the tag's version
// (e.g. "0.1.3") when publishing a tagged release; `docs.yml`'s push-to-`scala` build leaves it unset, which
// publishes under the "dev" path segment instead of colliding with a real release's own directory. Both
// workflows' own "Stage versioned docs for publish" step nests this build's output under that path and
// merges in whatever version directories already exist on `gh-pages` before publishing -- `sbt-github-pages`
// has no setting to keep remote-only files, so any directory this build doesn't already contain, and doesn't
// restore itself, would be lost on the next publish.
val docsVersion = sys.env.getOrElse("TDA4J_DOCS_VERSION", "dev")

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

lazy val root = (project in file("."))
  .enablePlugins(
    LaikaPlugin
  )
  .settings(
    // Compiler options: language features (implicitConversions, adhocExtensions) and warning flags.
    scalacOptions ++= List(
      "-source:future",
      "-language:experimental.modularity",
      "-language:implicitConversions",
      "-language:adhocExtensions",
      "-feature",
      "-deprecation",
      "-unchecked"
    ),
    // ***** laika ******
    Laika / sourceDirectories := Seq(sourceDirectory.value / "docs"),
    laikaIncludeAPI := true,
    laikaIncludePDF := true,
    laikaTheme := SiteTheme.theme,
    // Without this, fenced/inline code spans aren't recognized as code at all (plain CommonMark Markdown, the
    // laika-sbt default, doesn't include GFM fences) -- their contents get parsed as ordinary prose, so any `[...]`
    // in a code example (a Scala type param, a Java array type, a bracketed comment) is treated as a dangling
    // Markdown link/reference and fails the build.
    laikaExtensions += Markdown.GitHubFlavor,
    // Also opt-in, like GitHubFlavor: without it every fenced code block renders as plain, unstyled text.
    // (@:snip tokenizes its own extracted text separately -- see project/SnipDirective.scala.)
    laikaExtensions += laika.config.SyntaxHighlighting,
    laikaExtensions += new SnipDirective(baseDirectory.value),
    laikaExtensions += Tda4jDirective,
    laikaConfig := {
      val older = priorReleaseVersions(baseDirectory.value).map(v => Version(v, v))
      laika.sbt.LaikaConfig.defaults
        .withConfigValue(
          Versions.forCurrentVersion(Version(docsVersion, docsVersion)).withOlderVersions(older: _*)
        )
        .withConfigValue(LinkConfig.empty
          .addApiLinks(ApiLinks(baseUri="https://tda4j.appliedtopology.org/dev"))
          .addSourceLinks(SourceLinks(baseUri="https://github.com/appliedtopology/tda4j/", suffix="scala"))
        )
    },
    // Scala 3.9.0's own bundled scaladoc ships a `ux.js` that intercepts every same-origin link click
    // (sidebar navigation included) to do its own SPA-style AJAX page swap via `$.get(href, ...)` -- but
    // no page anywhere loads jQuery, so `$` is undefined. The click's own `e.preventDefault()` already
    // ran by the time that throws, so the click's default navigation is cancelled AND the replacement
    // AJAX navigation never happens: clicking a class in the API nav does nothing (confirmed against a
    // real browser: `ReferenceError: $ is not defined` at ux.js:180, `HTMLAnchorElement` click handler).
    // A real upstream scaladoc bug, not a Laika/tda4j config issue -- `$.get(url, cb)` is a drop-in match
    // for `fetch(url).then(r => r.text()).then(cb)` (the callback only ever receives raw HTML text here),
    // so patch the one call site post-generation rather than vendoring scaladoc's bundled JS ourselves.
    // Runs after Laika's own `laikaSite` (an idiomatic sbt task augmentation, not a self-referential
    // cycle: `key := f(key.value)` captures the plugin-provided task, same mechanism `+=`/`++=` desugar
    // to). See .claude/WORKLOG-docs-site-fixes.md.
    laikaSite := {
      val result = laikaSite.value
      val uxJs = target.value / "docs" / "site" / "api" / "scripts" / "ux.js"
      if (uxJs.exists()) {
        val original = IO.read(uxJs)
        val patched = original.replace(
          "$.get(href, function (data) {",
          "fetch(href).then((r) => r.text()).then(function (data) {"
        )
        if (patched != original) IO.write(uxJs, patched)
      }
      result
    },
    // Both settings are needed, not just one: `Compile / mainClass` is what `sbt run` uses; `assembly /
    // mainClass` is what sbt-assembly writes into the fat jar's manifest (`java -jar ... `). Neither is inferred
    // from the other.
    Compile / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI"),
    assembly / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")
  )

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

mimaPreviousArtifacts := priorReleaseVersions(baseDirectory.value)
  .filter(v => !v.startsWith("0.1"))
  .map(v => organization.value %% name.value % v)
  .toSet
