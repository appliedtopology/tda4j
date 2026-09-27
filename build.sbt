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
  // homeLink defaults to Laika's own DynamicHomeLink (site title as plain text); swapping in the project's
  // own mark here, `brand/icon.svg`'s standard (translucent-fill) rendering copied to
  // `src/docs/images/header-icon.svg` -- see `brand/DECISIONS.md` for the full logo/color decision record.
  .topNavigationBar(
    homeLink = ImageLink.internal(
      Root / "README.md",
      Image.internal(Root / "images" / "header-icon.svg", alt = Some("TDA4j"))
    ),
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
  // `favicon.svg` is the mark's small-size-optimized rendering (bolder strokes, tuned for 16-32px -- see
  // `brand/favicon.svg` and the "Rejected" section of `brand/DECISIONS.md` for why this mark specifically
  // survives at that size where every other concept tried didn't). "any" is the standard sizes value for a
  // scalable SVG favicon.
  .favIcons(Favicon.internal(Root / "images" / "favicon.svg", "any"))
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
      |.breadcrumb li a { display: inline-block; padding: 2px 8px; border-radius: 4px; }
      |.breadcrumb li:not(:last-child)::after { content: "\203A"; margin: 0 0.4em; color: var(--secondary-color); }
      |.tda4j-mark { font-family: var(--header-font); font-weight: 700; color: var(--primary-color); }
      |.tda4j-mark .tda4j-accent { color: var(--secondary-color); }
      |/* Helium's stock active/hover nav highlight is a hard-edged rectangle flush with its container's own
      | * edges (visible in the sidebar's current-page item and the version-menu dropdown) -- inset it slightly
      | * so the rounding actually reads instead of being swallowed by the container's own straight edge. */
      |.menu-content .nav-list li a { margin: 0 5px; border-radius: 4px; }
      |#sidebar .nav-list li.level1 a { margin: 0 5px; border-radius: 4px; }
      |""".stripMargin
  )
  // Slate & Gold, replacing the earlier Plum & Gold (`brand/DECISIONS.md`): Plum & Gold tested poorly once
  // rendered in the actual logo mark (plum and gold sit too close in lightness), where Slate & Gold read
  // cleanly against Indigo & Gold and Slate & Coral in a direct three-way comparison. These are the "UI-safe"
  // tier (4.5:1+ against their own background, for text/links/washes) -- the brand-mark tier used by the
  // logo/wordmark itself is brighter (`brand/icon.svg`'s `#456f87`/`#a67f07`) and is deliberately NOT reused
  // here; seeing them drift back together is the two-tier system quietly failing, not a simplification.
  .all
  .themeColors(
    primary = Color.hex("3c5a6b"),
    secondary = Color.hex("866504"),
    primaryMedium = Color.hex("b9cdd6"),
    primaryLight = Color.hex("eef3f5"),
    text = Color.hex("2b3338"),
    background = Color.hex("f8f7f4"),
    bgGradient = (Color.hex("28414f"), Color.hex("3c5a6b"))
  )
  // messageColors drives @:callout(...)'s three roles (info/warning/error), each an accent + a tinted
  // background -- entirely separate from themeColors above (confirmed via `javap -p` on the vendored
  // laika-core jar's MessageColors/ColorOps classes, since Laika's own scaladoc doesn't spell out the
  // positional order; passed positionally here rather than by name for exactly that reason). Without this
  // call every callout renders in Laika's stock default blue regardless of themeColors.
  // A hue-rotated triad off the same gold (not primary/secondary reuse, unlike the Plum & Gold version this
  // replaced): teal for info (180 degrees, chosen because it sits close to Slate's own ~202-degree hue and
  // reads as kin), the UI-safe gold itself for warning, red for error. `brand/DECISIONS.md` has the full
  // derivation and every contrast check.
  // `.themeColors(...)` returns plain `Helium`, not the `ColorOps`-mixing builder type, so `.all` must be
  // re-stated before the next color-related call -- same reason `.site.darkMode` is re-stated below.
  .all
  .messageColors(
    Color.hex("206f6f"), // info
    Color.hex("eef5f5"), // infoLight
    Color.hex("866504"), // warning      (= secondary)
    Color.hex("f5f3ee"), // warningLight
    Color.hex("812318"), // error
    Color.hex("f5efee") // errorLight
  )
  .site
  .darkMode
  .themeColors(
    primary = Color.hex("8fb4c7"),
    secondary = Color.hex("f0c647"),
    primaryMedium = Color.hex("33474f"),
    primaryLight = Color.hex("1a262b"),
    text = Color.hex("eef1f2"),
    background = Color.hex("161b1e"),
    bgGradient = (Color.hex("101a1e"), Color.hex("2c4552"))
  )
  .site
  .darkMode
  .messageColors(
    Color.hex("75c7c7"), // info
    Color.hex("1a2b2b"), // infoLight
    Color.hex("f0c647"), // warning      (= secondary)
    Color.hex("2b271a"), // warningLight
    Color.hex("d88279"), // error
    Color.hex("2b1c1a") // errorLight
  )
  // Heading font matches the logo's wordmark (`brand/DECISIONS.md`); body/code stay Helium's own Lato/Fira
  // Mono defaults, untouched. Loaded the same way Helium loads its own default fonts (confirmed via
  // `javap -p`/`strings` on the vendored jar: `HeliumDefaults` wires Lato through this exact
  // `Font.withWebCSS(...).definedAs(...)` + `addFontResources` mechanism, not a raw HTML head hack).
  .all
  .fontFamilies(body = "Lato", headlines = "JetBrains Mono", code = "Fira Mono")
  .site
  .addFontResources(
    Font
      .withWebCSS("https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@700&display=swap")
      .definedAs("JetBrains Mono", FontWeight.Bold, FontStyle.Normal)
  )
  .build

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
    laikaExtensions += Tda4jDirective,
    laikaConfig := {
      val older = priorReleaseVersions(baseDirectory.value).map(v => Version(v, v))
      laika.sbt.LaikaConfig.defaults.withConfigValue(
        Versions.forCurrentVersion(Version(docsVersion, docsVersion)).withOlderVersions(older: _*)
      )
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
