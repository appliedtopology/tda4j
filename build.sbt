import org.scalajs.linker.interface.{ModuleInitializer, ModuleSplitStyle, OutputPatterns}

// One library, two platforms. `tda4j` is the JVM build (artifact `tda4j_3`), `tda4jJS` the Scala.js build (artifact
// `tda4j_sjs1_3`). sbt 2 has projectMatrix built in (sbt-crossproject is not supported on sbt 2), and its default layout
// is used as it is: `src/{main,test}/scala` compiles for every row, `src/{main,test}/scalajvm` and `.../scalajs` hold
// what only one platform has. Commands are project-qualified where they only make sense for one platform
// (`sbt tda4j/assembly`, `sbt tda4jJS/test`); `sbt test` at the root runs every row that is aggregated below.
// .claude/DESIGN-scalajs-crossbuild.md has the platform seams and what carries over.
ThisBuild / organization := "org.appliedtopology"

// Docs are built with 3.8.4 (scaladoc 3.9.0 ships broken JavaScript); docs.yml and test.yml set
// TDA4J_SCALA_VERSION=3.8.4 for their `sbt doc` step. `sbt "++3.8.4 doc"` does NOT work in sbt 2: "no subprojects
// list 3.8.4 ... in crossScalaVersions"; `++3.8.4!` does, and release.yml uses it to stay in one sbt invocation. The env
// var only reaches a FRESH sbt JVM: through sbt 2's thin client (the local default) a second `sbt` call joins the
// running server and ignores it; CI starts every sbt call with `--server`, a new JVM each time.
// TODO: delete the override when 3.9.1 is released.
val tda4jScalaVersion = sys.env.getOrElse("TDA4J_SCALA_VERSION", "3.9.0")
ThisBuild / scalaVersion := tda4jScalaVersion

ThisBuild / versionScheme := Some("semver-spec")

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

// ---------------------------------------------------------------------------------------------------------------
// Settings of both platforms.

// The matrix rows' `baseDirectory` is sbt's own `.sbt/matrix/<id>`, not the checkout, so everything that names a file
// of the checkout (`_docs`) is resolved from the build's base directory.
def checkout: Def.Initialize[File] = Def.setting((ThisBuild / baseDirectory).value)

// Compiler options: language features (implicitConversions, adhocExtensions) and warning flags.
val commonScalacOptions = List(
  "-source:future",
  "-language:experimental.modularity",
  "-preview", // for `into` - remove once we're on Scala 3.9.x.
  "-language:adhocExtensions",
  "-feature",
  "-deprecation",
  "-unchecked"
)

val commonSettings: Seq[Setting[?]] = Seq(
  name := "tda4j",
  scalacOptions ++= commonScalacOptions,
  // `%%` is platform-aware in sbt 2: on the JS row it resolves `..._sjs1_3` artifacts. Only libraries that have a build
  // for both platforms belong here; JVM-only ones go in `jvmSettings`.
  libraryDependencies ++= Seq(
    "org.rogach"     %% "scallop"              % "6.0.1",
    "org.typelevel"  %% "cats-kernel"          % "2.13.0",
    "org.typelevel"  %% "cats-core"            % "2.13.0",
    "org.typelevel"  %% "kittens"              % "3.5.0",
    "org.specs2"     %% "specs2-core"          % "5.9.1"  % "test",
    "org.specs2"     %% "specs2-matcher-extra" % "5.9.1"  % "test",
    "org.specs2"     %% "specs2-scalacheck"    % "5.9.1"  % "test",
    "org.scalacheck" %% "scalacheck"           % "1.20.0" % "test"
  ),
  // The library's version as a source file, for both platforms: `matlab.ResultFile` records which tda4j wrote a saved
  // result (class files carry no manifest version outside a packaged jar, and Scala.js has no resources).
  Compile / sourceGenerators += Def.uncached(Def.task {
    val out = (Compile / sourceManaged).value / "org" / "appliedtopology" / "tda4j" / "LibraryVersion.scala"
    IO.write(
      out,
      s"""// GENERATED by build.sbt; do not edit.
         |package org.appliedtopology.tda4j
         |
         |// Restricted: a public top-level name joins every user's `import org.appliedtopology.tda4j.*`.
         |private[tda4j] object LibraryVersion:
         |  val value: String = "${version.value}"
         |""".stripMargin
    )
    Seq(out)
  }.taskValue),
  // Workaround for XML versioning issues
  // See: https://github.com/scala/bug/issues/12632
  libraryDependencySchemes ++= Seq(
    "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
  )
)

// ---------------------------------------------------------------------------------------------------------------
// The JVM row.

val jvmSettings: Seq[Setting[?]] = Seq(
  assembly / assemblyJarName := s"${name.value}-${version.value}-assembly.jar",
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
    "com.lihaoyi"            %% "cask"                       % "0.11.3",
    // The libraries the pure-Scala replacements stand in for (they have no Scala.js build): oracles of the specs in
    // `src/test/scalajvm`, nothing else.
    "org.apache.commons" % "commons-numbers-combinatorics" % "1.3"   % Test,
    "org.apache.commons" % "commons-math3"                 % "3.6.1" % Test,
    "com.eatthepath"     % "jvptree"                       % "0.3.0" % Test,
    "com.dreizak"        % "miniball"                      % "1.0.3" % Test
  ),
  Compile / doc / scalacOptions ++= Seq(
    "-siteroot",
    checkout.value.toString,
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
  ),
  Compile / doc / target := target.value / "api",
  // Both settings are needed, not just one: `Compile / mainClass` is what `sbt run` uses; `assembly /
  // mainClass` is what sbt-assembly writes into the fat jar's manifest (`java -jar ... `). Neither is inferred
  // from the other.
  Compile / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI"),
  assembly / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI"),
  // A fat jar is not a Java module, so module descriptors are dropped. sbt-assembly's default already discards a
  // top-level `module-info.class`, but not the multi-release copies under META-INF/versions/<n>/, which several jars
  // carry (so the default deduplication fails on them).
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
    case other                                                    => (assembly / assemblyMergeStrategy).value(other)
  },
  mimaPreviousArtifacts := mimaBaselineVersions(version.value, releaseTags(checkout.value))
    .map(v => organization.value %% name.value % v)
    .toSet,
  // Tutorial pages are tests: every `_docs/tutorials/*.md` with a "## The whole script" section has that section's first
  // `scala` fence copied into a generated `object <Page>Script` (package `tutorial`), which `src/test/.../tutorial/*Spec`
  // asserts on -- the page's code and the code the test runs are one and the same text. The page's narrative
  // (`scala sc:nocompile`) fences before that heading are joined into an `object <Page>Narrative` whose `narrative()`
  // is compiled but never called, so a narrative fence that drifts from the script fails `sbt Test/compile`, not just
  // a reader. See .claude/WORKLOG-tutorial-docs-as-tests.md.
  Test / sourceGenerators += Def.uncached(Def.task {
    val docs = checkout.value / "_docs" / "tutorials"
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
        val narrative = fences(text.substring(0, at).linesIterator.toList).collect { case ("scala sc:nocompile", b) =>
          b
        }
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
)

// Patch the favicon generation
val patchDocFavicon: Seq[Setting[?]] = Seq(
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
)

// ---------------------------------------------------------------------------------------------------------------
// The JS row.

lazy val npmPack = taskKey[File](
  "Builds the npm package of the Scala.js build from the optimized link and returns its tarball (target/npm/)."
)

val jsSettings: Seq[Setting[?]] = Seq(
  // ES modules, one file per entry: `main.mjs` holds the exports of the JavaScript API, `cli.mjs` is the `tda4j` command
  // (below), and the code they share is in an `internal-*.mjs` next to them. The `.mjs` extension lets Node run the
  // files without a `package.json` that says `"type": "module"`.
  scalaJSLinkerConfig ~= {
    _.withModuleKind(ModuleKind.ESModule)
      .withModuleSplitStyle(ModuleSplitStyle.FewestModules)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.mjs"))
  },
  Compile / scalaJSModuleInitializers +=
    ModuleInitializer.mainMethod("org.appliedtopology.tda4j.cli.TDA4jNodeMain", "run").withModuleID("cli"),
  // The npm package: the linked modules (without their source maps, whose sources the package does not carry), the
  // hand-written `npm/package.json` (the version filled in), `npm/index.d.ts` and `npm/README.md`, packed by `npm pack`.
  // `cli.mjs` is the `bin` of the package and gets its shebang line here.
  npmPack := Def.uncached {
    val _ = (Compile / fullLinkJS).value
    val linked = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
    val root = checkout.value
    val out = root / "target" / "npm"
    val packageDirectory = out / "package"
    IO.delete(out)
    IO.createDirectory(packageDirectory)
    (linked * "*.mjs").get().foreach { module =>
      val lines = IO.readLines(module).filterNot(_.startsWith("//# sourceMappingURL"))
      val target = packageDirectory / module.getName
      IO.writeLines(target, if (module.getName == "cli.mjs") "#!/usr/bin/env node" +: lines else lines)
      target.setExecutable(module.getName == "cli.mjs")
    }
    IO.copyFile(root / "npm" / "index.d.ts", packageDirectory / "index.d.ts")
    IO.copyFile(root / "npm" / "README.md", packageDirectory / "README.md")
    IO.copyFile(root / "LICENSE.md", packageDirectory / "LICENSE.md")
    IO.write(
      packageDirectory / "package.json",
      IO.read(root / "npm" / "package.json").replace("@VERSION@", version.value)
    )
    import scala.sys.process.*
    val name = Process(
      Seq("npm", "pack", "--silent", "--pack-destination", out.getAbsolutePath),
      packageDirectory
    ).!!.trim.linesIterator.toList.last
    out / name
  }
)

// ---------------------------------------------------------------------------------------------------------------

lazy val tda4j = (projectMatrix in file("."))
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = Seq(tda4jScalaVersion), settings = jvmSettings ++ patchDocFavicon)
  .jsPlatform(scalaVersions = Seq(tda4jScalaVersion), settings = jsSettings)

// The root aggregates both builds (`sbt test`, `clean`, `scalafmtAll` and `publishSigned` reach each); it publishes nothing
// itself. A command that means something for one platform names its project: `tda4j/doc`, `tda4jJS/npmPack`.
lazy val root = (project in file("."))
  .aggregate(tda4j.projectRefs *)
  .settings(
    name := "tda4j-root",
    publish / skip := true
  )

// No `mimaBinaryIssueFilters`: 0.7.0 starts a fresh series, whose baseline is empty (see mimaBaselineVersions). A
// deliberate break INSIDE a series is a commented `ProblemFilters.exclude` entry in `jvmSettings`, and a line under
// Breaking in the changelog.
