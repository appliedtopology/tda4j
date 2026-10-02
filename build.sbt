name := "tda4j"
organization := "org.appliedtopology"
// Docs are built with 3.8.4 (scaladoc 3.9.0 ships broken JavaScript); the docs workflows set
// TDA4J_SCALA_VERSION=3.8.4 for their `sbt doc` step. `sbt "++3.8.4 doc"` does NOT work in sbt 2: "no subprojects
// list 3.8.4 ... in crossScalaVersions" (`++ 3.8.4!` would). TODO: delete the override when 3.9.1 is released.
scalaVersion := sys.env.getOrElse("TDA4J_SCALA_VERSION", "3.9.0")

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

mimaPreviousArtifacts := priorReleaseVersions(baseDirectory.value)
  .filter(v => !v.startsWith("0.1"))
  .map(v => organization.value %% name.value % v)
  .toSet

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
