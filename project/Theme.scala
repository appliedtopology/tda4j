// format: off
// Required because project builds against Scala 2.12, but scalafmt expects Scala 3.7+

import laika.ast.{Image, InternalTarget}
import laika.ast.Path.Root
import laika.helium.Helium
import laika.helium.config.{
  Favicon,
  HeliumIcon,
  IconLink,
  ImageLink,
  LinkGroup,
  LinkPanel,
  Teaser,
  TextLink,
  VersionMenu
}
import laika.theme.config.{Color, Font, FontStyle, FontWeight}

// Off-white/dark-charcoal (light) and dark-charcoal/off-white (dark) rather than Helium's stock blues; teal/red
// stay as brand accent colors (links, headers, banner), not as the page's dominant wash.
object SiteTheme {
  val theme = Helium.defaults.all
    .metadata(
      title = Some("TDA4j"),
      authors = Seq("Mikael Vejdemo-Johansson", "Jordan Matuszewski", "Kei Kebreau", "Trevor Gordon", "Daniel Hope")
    )
    .site
    .baseURL("https://tda4j.appliedtopology.org")
    .site
    // homeLink defaults to Laika's own DynamicHomeLink (site title as plain text); swapping in the project's
    // own mark here, `brand/icon.svg`'s standard (translucent-fill) rendering copied to
    // `src/docs/images/header-icon.svg` -- see `brand/DECISIONS.md` for the full logo/color decision record.
    .topNavigationBar(
      homeLink = ImageLink.internal(
        Root / "landing-page.md",
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
    // `favicon.svg` is the mark's small-size-optimized rendering (bolder strokes, tuned for 16-32px -- see
    // `brand/favicon.svg` and the "Rejected" section of `brand/DECISIONS.md` for why this mark specifically
    // survives at that size where every other concept tried didn't). "any" is the standard sizes value for a
    // scalable SVG favicon.
    .site
    .favIcons(Favicon.internal(Root / "images" / "favicon.svg", "any"))
    .site
    .footer("TDA4j is released under the MIT License")
    // The breadcrumb (added via a default.template.html override, since Helium doesn't include one) reuses the
    // `nav-list` class the sidebar nav uses, whose `li a { display: block }` stacks entries vertically -- there is
    // no dedicated `.breadcrumb` rule in Helium's own CSS to override that. Lay it out as a horizontal trail instead.
    .site
    .downloadPage("Downloads", None, downloadPath = Root / "downloads", includeEPUB = true, includePDF = true)
    .site
    .landingPage(
      logo = Some(Image(InternalTarget(Root / "images" / "large-logo.svg"))),
      title = Some("TDA4j"),
      subtitle = Some("A Scala library for topological data analysis"),
      license = Some("MIT"),
      titleLinks = Seq(
        VersionMenu.create(unversionedLabel = "Getting Started"),
        LinkGroup.create(
          IconLink.external("https://github.com/appliedtopology/tda4j", HeliumIcon.github)
        )
      ),
      linkPanel = Some(
        LinkPanel(
          "Documentation",
          TextLink.internal(Root / "user-guide" / "README.md", "User Guide"),
          TextLink.internal(Root / "developers-guide" / "README.md", "Developer's Guide"),
          TextLink.internal(Root / "tutorials" / "README.md", "Tutorials")
        )
      ),
      projectLinks = Seq(
        TextLink.external("https://github.com/appliedtopology/tda4j", "GitHub"),
        TextLink.internal(Root / "api" / "index.html", "ScalaDoc")
      ),
      teasers = Seq(
        Teaser("Topological Data Analysis", "A Scala library for topological data analysis."),
        Teaser("Continuation of JavaPlex", "The oldest lineage of TDA software libraries."),
        Teaser(
          "Matlab, Mathematica, JVM",
          "Accessible from everything that works with the JVM, optimized for easy access from Matlab"
        ),
        Teaser(
          "Complex constructions",
          "Supports Vietoris-Rips, Cech, Alpha and Cubical complexes; Witness Complexes, Approximate Vietoris-Rips, Dowker Complexes, and Finitely generated simplicial sets."
        ),
        Teaser("Persistence", "Persistent Homology, Cohomology, and Barcode Algebra."),
        Teaser(
          "Circular Coordinates",
          "Internal support for circular and toroidal coordinates (simplified with the LLL algorithm)."
        )
      )
    )
    .site
    .inlineCSS(
      """
        |.breadcrumb { display: flex; flex-wrap: wrap; list-style: none; padding: 0; margin: 0 0 1.5rem 0; }
        |.breadcrumb li { margin: 0; }
        |.breadcrumb li a { display: inline; padding: 0; }
        |.breadcrumb li:not(:last-child)::after { content: "\203A"; margin: 0 0.4em; color: var(--secondary-color); }
        |.tda4j-mark { font-family: var(--header-font); font-weight: 700; color: var(--primary-color); }
        |.tda4j-mark .tda4j-accent { color: var(--secondary-color); }
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
      secondary = Color.hex("f3d068"),
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
      Color.hex("f3d068"), // warning      (= secondary)
      Color.hex("2b271a"), // warningLight
      Color.hex("d88279"), // error
      Color.hex("2b1c1a") // errorLight
    )
    // Heading font matches the logo's wordmark (`brand/DECISIONS.md`); body/code stay Helium's own Lato/Fira
    // Mono defaults, untouched. Loaded the same way Helium loads its own default fonts (confirmed via
    // `javap -p`/`strings` on the vendored jar: `HeliumDefaults` wires Lato through this exact
    // `Font.withWebCSS(...).definedAs(...)` + `addFontResources` mechanism, not a raw HTML head hack).
    .all
    .fontFamilies(body = "Lato", headlines = "JetBrains Mono", code = "JetBrains Mono")
    .site
    .addFontResources(
      Font
        .withWebCSS("https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@700&display=swap")
        .definedAs("JetBrains Mono", FontWeight.Bold, FontStyle.Normal)
    )
    .build
}