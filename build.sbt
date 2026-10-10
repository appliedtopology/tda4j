name := "tda4j"
organization := "org.appliedtopology"
// Docs are built with 3.8.4 (scaladoc 3.9.0 ships broken JavaScript); docs.yml and test.yml set
// TDA4J_SCALA_VERSION=3.8.4 for their `sbt doc` step. `sbt "++3.8.4 doc"` does NOT work in sbt 2: "no subprojects
// list 3.8.4 ... in crossScalaVersions"; `++3.8.4!` does, and release.yml uses it because the env var only reaches a
// FRESH sbt server: a second `sbt` call in the same job joins the running server (thin client) and ignores it.
// TODO: delete the override when 3.9.1 is released.
ThisBuild / scalaVersion := sys.env.getOrElse("TDA4J_SCALA_VERSION", "3.9.0")

// The core library is the root project: every bare setting in this file is its own. `plot` (the `tda4j-plot` add-on,
// `plot/`) depends on it and adds no dependency to it. Root aggregates `plot`, so CI's `test`, lint, `doc` and `mima`
// commands cover it too; `assembly` does not aggregate (the CLI/MATLAB fat jar is the core's alone).
lazy val root = (project in file(".")).aggregate(plot)
assembly / aggregate := false

lazy val plot = (project in file("plot"))
  .dependsOn(LocalRootProject)
  .settings(
    name := "tda4j-plot",
    organization := "org.appliedtopology",
    scalacOptions ++= List(
      "-source:future",
      "-language:experimental.modularity",
      "-preview",
      "-feature",
      "-deprecation",
      "-unchecked"
    ),
    libraryDependencies += "org.specs2" %% "specs2-core" % "5.5.1" % "test",
    // Not published yet: the add-on's API is a draft (DESIGN-plotting.md). Drop these two lines to release it.
    publish / skip := true,
    mimaPreviousArtifacts := Set.empty,
    mimaFailOnNoPrevious := false
  )

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
libraryDependencies += "org.typelevel"  %% "cats-kernel"                % "2.13.0"
libraryDependencies += "org.typelevel"  %% "cats-core"                  % "2.13.0"
libraryDependencies += "org.typelevel"  %% "kittens"                    % "3.5.0"

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
  "-social-links:github::https://github.com/appliedtopology/tda4j",
  "-doc-footer",
  "TDA4j is built by the TDA @ CUNY workgroup",
  "-quick-links:Applied Topology::https://appliedtopology.org,Playground::https://scastie.scala-lang.org/?inputs=%7B%0A%20%20%22_isWorksheetMode%22%20%3A%20true%2C%0A%20%20%22code%22%20%3A%20%22import%20org.appliedtopology.tda4j.*%5Cn%E2%88%86(1%2C2%2C3)%5Cn%22%2C%0A%20%20%22target%22%20%3A%20%7B%0A%20%20%20%20%22scalaVersion%22%20%3A%20%223.9.0%22%2C%0A%20%20%20%20%22tpe%22%20%3A%20%22Scala3%22%0A%20%20%7D%2C%0A%20%20%22libraries%22%20%3A%20%5B%20%5D%2C%0A%20%20%22librariesFromList%22%20%3A%20%5B%20%5D%2C%0A%20%20%22sbtConfigExtra%22%3A%22%5CnscalacOptions%20%2B%2B%3D%20Seq(%5Cn%20%20%5C%22-deprecation%5C%22%2C%5Cn%20%20%5C%22-encoding%5C%22%2C%20%5C%22UTF-8%5C%22%2C%5Cn%20%20%5C%22-feature%5C%22%2C%5Cn%20%20%5C%22-unchecked%5C%22%2C%5Cn%20%20%5C%22-source%3Afuture%5C%22%2C%20%5Cn%20%20%5C%22-language%3Aexperimental.modularity%5C%22%5Cn)%5CnlibraryDependencies%20%2B%3D%20%5C%22org.appliedtopology%5C%22%20%25%25%20%5C%22tda4j%5C%22%20%25%20%5C%220.4.0%5C%22%5Cn%22%2C%0A%20%20%22sbtPluginsConfigExtra%22%20%3A%20%22%22%2C%0A%20%20%22isShowingInUserProfile%22%20%3A%20true%0A%7D%0A",
  "-scastie-configuration",
  """
    |  scalacOptions ++= Seq(
    |    "-deprecation",
    |    "-encoding", "UTF-8",
    |    "-feature", "-unchecked",
    |    "-source:future", "-language:experimental.modularity",
    |    "-language:implicitConversions", "-language:adhocExtensions"
    |  ) ;
    |  libraryDependencies += "org.appliedtopology" %% "tda4j" % "0.4.0"
    |""".stripMargin.replace("\n", ""),
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

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

mimaPreviousArtifacts := mimaBaselineVersions(version.value, releaseTags(baseDirectory.value))
  .map(v => organization.value %% name.value % v)
  .toSet

// Deliberate breaks inside the series (see mimaBaselineVersions), each reviewed in its PR:
// - `Chain` became an abstract class open to new storages: a subclass implements `entryIterator`, every other member
//   defaults over it (the library's storages are `HeapChain` and `PackedChain`). In 0.5.0 its constructor was
//   `private[tda4j]`, so no client could have extended or created a `Chain` directly.
// - `Chain.chainShow` needs only `Show` of the cells, not `OrderedCell` (chains over simplicial-set generators, which
//   have no given `OrderedCell`, are showable now). A given, found by implicit search: no source change for callers.
mimaBinaryIssueFilters ++= {
  import com.typesafe.tools.mima.core.*
  Seq(
    ProblemFilters.exclude[AbstractClassProblem]("org.appliedtopology.tda4j.Chain"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("org.appliedtopology.tda4j.Chain.entryIterator"),
    ProblemFilters.exclude[DirectMissingMethodProblem](
      "org.appliedtopology.tda4j.Chain.chainShow(org.appliedtopology.tda4j.OrderedCell,cats.Show,org.appliedtopology.tda4j.Field)cats.Show"
    )
  )
}

// Tutorial pages are tests: every `_docs/tutorials/*.md` with a "## The whole script" section has that section's first
// `scala` fence copied into a generated `object <Page>Script` (package `tutorial`), which `src/test/.../tutorial/*Spec`
// asserts on -- the page's code and the code the test runs are one and the same text. The page's narrative
// (`scala sc:nocompile`) fences before that heading are joined into an `object <Page>Narrative` whose `narrative()`
// is compiled but never called, so a narrative fence that drifts from the script fails `sbt Test/compile`, not just
// a reader. See .claude/WORKLOG-tutorial-docs-as-tests.md.
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
