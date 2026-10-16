name := "tda4j"
organization := "org.appliedtopology"
// Docs are built with 3.8.4 (scaladoc 3.9.0 ships broken JavaScript); docs.yml and test.yml set
// TDA4J_SCALA_VERSION=3.8.4 for their `sbt doc` step. `sbt "++3.8.4 doc"` does NOT work in sbt 2: "no subprojects
// list 3.8.4 ... in crossScalaVersions"; `++3.8.4!` does, and release.yml uses it because the env var only reaches a
// FRESH sbt server: a second `sbt` call in the same job joins the running server (thin client) and ignores it.
// TODO: delete the override when 3.9.1 is released.
ThisBuild / scalaVersion := sys.env.getOrElse("TDA4J_SCALA_VERSION", "3.9.0")

versionScheme := Some("semver-spec")

assembly / assemblyJarName := s"${name.value}-${version.value}-assembly.jar"

libraryDependencies += "org.specs2"        %% "specs2-core"                   % "5.9.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-matcher-extra"          % "5.9.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-scalacheck"             % "5.9.1" % "test"
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.3"
libraryDependencies += "org.apache.commons" % "commons-math3"                 % "3.6.1"
libraryDependencies += "com.eatthepath"     % "jvptree"                       % "0.3.0"
libraryDependencies += "com.dreizak"        % "miniball"                      % "1.0.3"
libraryDependencies +=
  "org.scala-lang.modules"              %% "scala-parallel-collections" % "1.2.0"
libraryDependencies += "org.scalacheck" %% "scalacheck"                 % "1.20.0" % "test"
libraryDependencies += "org.rogach"     %% "scallop"                    % "6.0.1"
libraryDependencies += "org.typelevel"  %% "cats-kernel"                % "2.13.0"
libraryDependencies += "org.typelevel"  %% "cats-core"                  % "2.13.0"
libraryDependencies += "org.typelevel"  %% "kittens"                    % "3.5.0"
libraryDependencies += "com.lihaoyi"    %% "cask"                       % "0.11.3"

// Docs versioning (RELEASE.md step 5): `release.yml` sets TDA4J_DOCS_VERSION to the tag's version
// (e.g. "0.1.3") when publishing a tagged release; `docs.yml`'s push-to-`scala` build leaves it unset, which
// publishes under the "dev" path segment instead of colliding with a real release's own directory. Both
// workflows' own "Stage versioned docs for publish" step nests this build's output under that path and
// merges in whatever version directories already exist on `gh-pages` before publishing -- `sbt-github-pages`
// has no setting to keep remote-only files, so any directory this build doesn't already contain, and doesn't
// restore itself, would be lost on the next publish.
val docsVersion = sys.env.getOrElse("TDA4J_DOCS_VERSION", "dev")

// The Playground link and the doc snippets' Scastie configuration load the LAST RELEASE of tda4j from Maven Central
// (never the snapshot being built), read from the `v*` git tags like the MiMa baseline: `release.yml` and `docs.yml`
// fetch the tags. `ScastieFallback` is used only when the checkout has none (a shallow clone).
val ScastieFallback = "0.5.0"
def scastieVersion: String =
  releaseTags(file("."))
    .find(_.matches("""\d+\.\d+\.\d+"""))
    .getOrElse(ScastieFallback)

def scastieSettings(version: String): Seq[String] = Seq(
  """scalacOptions ++= Seq("-deprecation", "-encoding", "UTF-8", "-feature", "-unchecked", "-source:future", "-language:experimental.modularity", "-language:implicitConversions", "-language:adhocExtensions")""",
  s"""libraryDependencies += "org.appliedtopology" %% "tda4j" % "$version""""
)

// https://scastie.scala-lang.org/?inputs=<URL-encoded JSON>; a space is %20 there (a `+` would stay a plus).
def scastieLink(version: String): String = {
  val code =
    "import scala.language.experimental.modularity\nimport org.appliedtopology.tda4j.*\n" +
      "val d = Persistence(Seq(Seq(0.0, 0.0), Seq(1.0, 0.0), Seq(1.0, 1.0), Seq(0.0, 1.0)))\n" +
      "d.bars.map(b => (b.dim, b.birth, b.death))\n"
  def str(x: String): String = "\"" + x.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case c    => c.toString
  } + "\""
  val json =
    s"""{"_isWorksheetMode": true, "code": ${str(code)}, "target": {"scalaVersion": "3.9.0", "tpe": "Scala3"}, """ +
      s""""libraries": [], "librariesFromList": [], "sbtConfigExtra": ${str(
          scastieSettings(version).mkString("", "\n", "\n")
        )}, """ +
      """"sbtPluginsConfigExtra": "", "isShowingInUserProfile": true}"""
  "https://scastie.scala-lang.org/?inputs=" + java.net.URLEncoder.encode(json, "UTF-8").replace("+", "%20")
}

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
  "-social-links:github::https://github.com/appliedtopology/tda4j",
  "-doc-footer",
  "TDA4j is built by the TDA @ CUNY workgroup",
  s"-quick-links:Applied Topology::https://appliedtopology.org,Playground::${scastieLink(scastieVersion)}",
  "-scastie-configuration",
  scastieSettings(scastieVersion).mkString(" ; "),
  // Every Scala fence in the docs is compiled; mark a purely illustrative fence `scala sc:nocompile` instead.
  "-snippet-compiler:compile"
)
Compile / doc / target := target.value / "api"

// Release tags (`vX.Y.Z`), newest first, read straight from git rather than hand-maintained.
def releaseTags(baseDir: File): Seq[String] = {
  import scala.sys.process._
  scala.util
    .Try(Process(Seq("git", "tag", "--list", "v*", "--sort=-v:refname"), baseDir).!!)
    .getOrElse("")
    .linesIterator
    .toList
    .map(_.stripPrefix("v"))
}

// MiMa baseline: binary compatibility is enforced WITHIN a compatibility series and never across one. A series is
// "0.Y" while the major version is 0 (semver-spec: a 0.x minor bump may break anything, as 0.5.0 deliberately does
// from 0.4.x) and "X" from 1.0 on (a minor bump must stay compatible, a major bump may not). The baseline of a build is
// every earlier plain release (no -alpha/-RC suffix) of its own series: `0.5.0-SNAPSHOT` therefore has none, `0.5.1-SNAPSHOT`
// is checked against `0.5.0`, and the first `0.6.x` build starts a fresh, empty baseline. Needs the tags in the checkout
// (CI fetches full history), and a tag with no published Maven artifact fails dependency resolution -- see RELEASE.md.
// A deliberate break inside a series is allowed with a commented entry in `mimaBinaryIssueFilters`, reviewed in the PR.
def mimaBaselineVersions(current: String, tags: Seq[String]): Seq[String] = {
  val Release = """(\d+)\.(\d+)\.(\d+)""".r
  val Versioned = """(\d+)\.(\d+)\.(\d+)(?:-.+)?""".r
  def series(major: Int, minor: Int): String = if (major == 0) s"0.$minor" else s"$major"
  current match {
    case Versioned(major, minor, patch) =>
      val here = (major.toInt, minor.toInt, patch.toInt)
      tags.collect {
        case v @ Release(ma, mi, pa)
            if series(ma.toInt, mi.toInt) == series(here._1, here._2) &&
              Ordering[(Int, Int, Int)].lt((ma.toInt, mi.toInt, pa.toInt), here) =>
          v
      }
    case _ => Nil
  }
}

// Compiler options: language features (implicitConversions, adhocExtensions) and warning flags.
scalacOptions ++= List(
  "-source:future",
  "-language:experimental.modularity",
  "-preview", // for `into` - remove once we're on Scala 3.9.x.
  "-language:adhocExtensions",
  "-feature",
  "-deprecation",
  "-unchecked"
)

// Patch the favicon generation
Compile / doc := {
  val docDir = (Compile / doc).value
  val allHTML = (docDir ** "*.html").get()
  allHTML.filter(_.isFile).foreach { file =>
    val content = sbt.IO.read(file)
    val patched = content.replace(
      "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"favicon.ico\">",
      """
        |<link rel="shortcut icon" type="image/svg+xml" href="/images/header-icon.svg"/>
        |<link rel="apple-touch-icon" sizes="180x180" href="/images/header-icon-180.png"/>
        |<link rel="icon" sizes="16x16" type="image/png" href="/images/favicon-16.png"/>
        |<link rel="icon" sizes="32x32" type="image/png" href="/images/favicon-32.png"/>
        |<link rel="icon" sizes="48x48" type="image/png" href="/images/favicon-48.png"/>
        |<link rel="icon" sizes="64x64" type="image/png" href="/images/favicon-64.png"/>
        |<link rel="icon" sizes="128x128" type="image/png" href="/images/header-icon-128.png"/>
        |<link rel="icon" sizes="256x256" type="image/png" href="/images/header-icon-256.png"/>
        |<link rel="manifest" href="/images/manifest.json"/>
        |<link rel="shortcut icon" type="image/svg+xml" href="/images/header-icon-dark.svg" media="(prefers-color-scheme: dark)" />
        |<link rel="apple-touch-icon" sizes="180x180" href="/images/header-icon-dark-180.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="16x16" type="image/png" href="/images/favicon-16.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="32x32" type="image/png" href="/images/favicon-32.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="48x48" type="image/png" href="/images/favicon-48.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="64x64" type="image/png" href="/images/favicon-64.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="128x128" type="image/png" href="/images/header-icon-dark-128.png" media="(prefers-color-scheme: dark)" />
        |<link rel="icon" sizes="256x256" type="image/png" href="/images/header-icon-dark-256.png" media="(prefers-color-scheme: dark)" />
        |<link rel="manifest" href="/images/manifest-dark.json" media="(prefers-color-scheme: dark)" />
        |        |""".stripMargin
    )
    if (patched != content) sbt.IO.write(file, patched)
  }
  val favicon = (docDir ** "favicon.ico").get().foreach(file => sbt.IO.delete(file))
  docDir
}

// Both settings are needed, not just one: `Compile / mainClass` is what `sbt run` uses; `assembly /
// mainClass` is what sbt-assembly writes into the fat jar's manifest (`java -jar ... `). Neither is inferred
// from the other.
Compile / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")
assembly / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")

// A fat jar is not a Java module, so module descriptors are dropped. sbt-assembly's default already discards a
// top-level `module-info.class`, but not the multi-release copies under META-INF/versions/<n>/, which several jars
// carry (each commons-numbers 1.3 jar has its own, so the default deduplication fails on them).
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  case other                                                    => (assembly / assemblyMergeStrategy).value(other)
}

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

mimaPreviousArtifacts := mimaBaselineVersions(version.value, releaseTags(baseDirectory.value))
  .map(v => organization.value %% name.value % v)
  .toSet

// No `mimaBinaryIssueFilters`: 0.7.0 starts a fresh series, whose baseline is empty (see mimaBaselineVersions). A
// deliberate break INSIDE a series is a commented `ProblemFilters.exclude` entry here, and a line under Breaking in the
// changelog.

// Tutorial pages are tests: every `_docs/tutorials/*.md` with a "## The whole script" section has that section's first
// `scala` fence copied into a generated `object <Page>Script` (package `tutorial`), which `src/test/.../tutorial/*Spec`
// asserts on -- the page's code and the code the test runs are one and the same text. The page's narrative
// (`scala sc:nocompile`) fences before that heading are joined into an `object <Page>Narrative` whose `narrative()`
// is compiled but never called, so a narrative fence that drifts from the script fails `sbt Test/compile`, not just
// a reader. See .claude/WORKLOG-tutorial-docs-as-tests.md.
// The library's version as a resource: `matlab.ResultFile` records which tda4j wrote a saved result (the class files
// carry no manifest version outside a packaged jar).
Compile / resourceGenerators += Def.uncached(Def.task {
  val out = (Compile / resourceManaged).value / "org" / "appliedtopology" / "tda4j" / "version.properties"
  IO.write(out, s"version=${version.value}\n")
  Seq(out)
}.taskValue)

Test / sourceGenerators += Def.uncached(Def.task {
  val docs = baseDirectory.value / "_docs" / "tutorials"
  val outDir = (Test / sourceManaged).value / "tutorial"
  def objectName(file: File): String =
    file.getName.stripSuffix(".md").split("-").map(_.capitalize).mkString
  // (info string, body) of every fenced block, in order
  def fences(lines: List[String]): List[(String, String)] = {
    val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
    var info: Option[String] = None
    val body = new StringBuilder
    lines.foreach { line =>
      if (line.startsWith("```")) {
        info match {
          case None    => info = Some(line.stripPrefix("```").trim); body.clear()
          case Some(i) => out += ((i, body.toString)); info = None
        }
      } else if (info.isDefined) body.append(line).append("\n")
    }
    out.toList
  }
  def indent(text: String, by: Int): String =
    text.linesIterator.map(l => if (l.isEmpty) l else (" " * by) + l).mkString("\n")
  val pages = (docs * "*.md").get().sortBy(_.getName)
  pages.flatMap { page =>
    val text = IO.read(page)
    val marker = "## The whole script"
    val at = text.indexOf(marker)
    if (at < 0) Nil
    else {
      val narrative = fences(text.substring(0, at).linesIterator.toList).collect { case ("scala sc:nocompile", b) => b }
      val script = fences(text.substring(at).linesIterator.toList)
        .collectFirst { case ("scala", b) => b }
        .getOrElse(sys.error(s"${page.getName}: no `scala` fence after '$marker'"))
      val name = objectName(page)
      val source =
        s"""// GENERATED by build.sbt from _docs/tutorials/${page.getName}; do not edit.
           |package org.appliedtopology.tda4j
           |package tutorial
           |
           |object ${name}Script:
           |${indent(script, 2)}
           |
           |object ${name}Narrative:
           |  def narrative(): Unit =
           |${indent(narrative.mkString("\n") + "\n()", 4)}
           |""".stripMargin
      val out = outDir / s"${name}Script.scala"
      IO.write(out, source)
      Seq(out)
    }
  }
}.taskValue)
