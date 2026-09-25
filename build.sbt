name := "TDA4j"
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
import laika.helium.config.{HeliumIcon, IconLink}
import laika.theme.config.Color
import laika.format.Markdown
import laika.ast.Path.Root
import laika.config.{Version, Versions}
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

// Off-white/dark-charcoal (light) and dark-charcoal/off-white (dark) rather than Helium's stock blues; teal/red
// stay as brand accent colors (links, headers, banner), not as the page's dominant wash.
val theme = Helium.defaults.all
  .metadata(
    title = Some("TDA4j"),
    authors = Seq("Mikael Vejdemo-Johansson", "Jordan Matuszewski", "Kei Kebreau")
  )
  .site
  .baseURL("https://appliedtopology.github.io/tda4j")
  .site
  .topNavigationBar(
    navLinks = Seq(
      IconLink.internal(Root / "api" / "index.html", HeliumIcon.api),
      IconLink.external("https://github.com/appliedtopology/tda4j", HeliumIcon.github)
    ),
    // Renders the version-switcher dropdown driven by the `laikaConfig`'s Versions value below -- links to
    // sibling versions only resolve once those versions are actually published side by side on gh-pages (see
    // RELEASE.md step 5), not from this setting alone.
    versionMenu = VersionMenu.default
  )
  .site
  .footer("MIT License © Mikael Vejdemo-Johansson, Daniel Hope")
  // The breadcrumb (added via a default.template.html override, since Helium doesn't include one) reuses the
  // `nav-list` class the sidebar nav uses, whose `li a { display: block }` stacks entries vertically -- there is
  // no dedicated `.breadcrumb` rule in Helium's own CSS to override that. Lay it out as a horizontal trail instead.
  .site
  .downloadPage("Downloads", None)
  .site
  .inlineCSS(
    """
      |.breadcrumb { display: flex; flex-wrap: wrap; list-style: none; padding: 0; margin: 0 0 1.5rem 0; }
      |.breadcrumb li { margin: 0; }
      |.breadcrumb li a { display: inline; padding: 0; }
      |.breadcrumb li:not(:last-child)::after { content: "\203A"; margin: 0 0.4em; color: var(--secondary-color); }
      |""".stripMargin
  )
  .all
  .themeColors(
    primary = Color.hex("007c99"),
    secondary = Color.hex("931813"),
    primaryMedium = Color.hex("a7d4de"),
    primaryLight = Color.hex("f2efe7"),
    text = Color.hex("333333"),
    background = Color.hex("faf8f4"),
    bgGradient = (Color.hex("095269"), Color.hex("007c99"))
  )
  .site
  .darkMode
  .themeColors(
    primary = Color.hex("7fc2d6"),
    secondary = Color.hex("f1c47b"),
    primaryMedium = Color.hex("3a5a63"),
    primaryLight = Color.hex("16323c"),
    text = Color.hex("f0ede6"),
    background = Color.hex("1e2124"),
    bgGradient = (Color.hex("064458"), Color.hex("197286"))
  )
  .build

lazy val root = (project in file("."))
  .enablePlugins(
    LaikaPlugin,
    GitHubPagesPlugin
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
    laikaTheme := theme,
    // Without this, fenced/inline code spans aren't recognized as code at all (plain CommonMark Markdown, the
    // laika-sbt default, doesn't include GFM fences) -- their contents get parsed as ordinary prose, so any `[...]`
    // in a code example (a Scala type param, a Java array type, a bracketed comment) is treated as a dangling
    // Markdown link/reference and fails the build.
    laikaExtensions += Markdown.GitHubFlavor,
    // Also opt-in, like GitHubFlavor: without it every fenced code block renders as plain, unstyled text.
    // (@:snip tokenizes its own extracted text separately -- see project/SnipDirective.scala.)
    laikaExtensions += laika.config.SyntaxHighlighting,
    laikaExtensions += new SnipDirective(baseDirectory.value),
    laikaConfig := {
      val older = priorReleaseVersions(baseDirectory.value).map(v => Version(v, v))
      laika.sbt.LaikaConfig.defaults.withConfigValue(
        Versions.forCurrentVersion(Version(docsVersion, docsVersion)).withOlderVersions(older: _*)
      )
    },
    // ***** gh-pages *****
    gitHubPagesOrgName := "appliedtopology",
    gitHubPagesRepoName := "tda4j",
    // NOT (laikaSite / target).value directly: `laikaSite` renders this build's own docs unnested (confirmed
    // by inspecting its actual output -- `laikaConfig`'s Versions value drives the version-switcher dropdown
    // and `laika/versionInfo.json`, not physical output placement). Each CI workflow's own "Stage versioned
    // docs for publish" step populates this directory itself: copy `(laikaSite / target).value`'s contents
    // into `<this dir>/<docsVersion>/`, then copy forward every other already-published version directory
    // from `gh-pages` before running `publishToGitHubPages`. A bare local `sbt publishToGitHubPages` run (no
    // such staging step first) would publish this directory empty -- always run through a workflow, or
    // replicate its staging steps by hand.
    gitHubPagesSiteDir := baseDirectory.value / "target" / "docs" / "publish",
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

mimaPreviousArtifacts := Set.empty
