package org.appliedtopology.tda4j.plot

import RootText.lowerRoot

/** What a mark is painted with. A `Series` slot or a role (`Ink`, `Muted`, ...) is resolved by the figure's [[Palette]]
  * and [[Theme]], so the same figure renders light or dark and in another palette; `Value` is a data value (a heatmap
  * cell, an angle, a coefficient) that the figure's [[ColorScale]] turns into a color, again through its palette;
  * `Fixed` is a color of your own and is the same everywhere.
  */
enum Paint:
  /** A categorical slot, `0` first: homological degree `k` is always `Series(k)`, whichever degrees are shown. */
  case Series(slot: Int)
  case Ink, Secondary, Muted, Grid, Axis, Surface

  /** A data value, colored by the [[Figure]]'s [[ColorScale]]. */
  case Value(v: Double)
  case Fixed(hex: String)

/** Light, dark, or both through the viewer's `prefers-color-scheme` (`Auto`: colors are CSS variables, which every
  * browser, notebook and editor preview honours). Use `Light` for a file that goes into a paper: plain hex colors only.
  */
enum Theme:
  case Auto, Light, Dark

object Theme:
  /** The theme called `name`, in any case: `auto`, `light` or `dark`. */
  def named(name: String): Theme = name.trim.lowerRoot match
    case "auto"  => Auto
    case "light" => Light
    case "dark"  => Dark
    case other   => throw IllegalArgumentException(s"unknown theme '$other'; expected one of auto, light, dark")

/** Chart chrome of one mode: the surface, three inks, gridline and axis. */
final case class Chrome(surface: String, ink: String, secondary: String, muted: String, grid: String, axis: String)

/** Everything a plot's colors come from: categorical slots (light and dark steps of the same hues), chrome for each
  * mode, a sequential ramp (light = small) and a diverging pair. [[Palette.brand]] (the default) is TDA4j's Teal &
  * Gold; [[Palette.reference]] is the neutral palette of the dataviz method. Make your own with `withSeries`,
  * `withSurface` and `copy`.
  *
  * A palette is a [[Tweak]]: `fig + Palette.reference` recolors every part of the figure, heatmaps and cocycles
  * included.
  */
final case class Palette(
  seriesLight: IndexedSeq[String],
  seriesDark: IndexedSeq[String],
  light: Chrome,
  dark: Chrome,
  sequential: IndexedSeq[String],
  divergingNegative: String,
  divergingPositive: String,
  divergingMidpoint: String
) extends Tweak:
  def onFigure(f: Figure): Figure = f.copy(palette = this)
  override def onScene(s: Scene): Scene = s.copy(palette = this)

  /** The color of a paint that needs only the palette. A [[Paint.Value]] needs the figure's [[ColorScale]] as well, so
    * it is colored by [[ColorScale.hex]] and has no color here.
    */
  def hex(paint: Paint, theme: Theme): String =
    val isDark = theme == Theme.Dark
    val chrome = if isDark then dark else light
    paint match
      case Paint.Series(k) => (if isDark then seriesDark else seriesLight) (k % seriesLight.size)
      case Paint.Ink       => chrome.ink
      case Paint.Secondary => chrome.secondary
      case Paint.Muted     => chrome.muted
      case Paint.Grid      => chrome.grid
      case Paint.Axis      => chrome.axis
      case Paint.Surface   => chrome.surface
      case Paint.Fixed(h)  => h
      case Paint.Value(_)  => throw valueNeedsScale

  /** The CSS variable a paint resolves through under `Theme.Auto`, or its hex for a fixed color. */
  def css(paint: Paint): String = paint match
    case Paint.Series(k) => s"var(--s${k % seriesLight.size})"
    case Paint.Ink       => "var(--ink)"
    case Paint.Secondary => "var(--ink2)"
    case Paint.Muted     => "var(--muted)"
    case Paint.Grid      => "var(--grid)"
    case Paint.Axis      => "var(--axis)"
    case Paint.Surface   => "var(--surface)"
    case Paint.Fixed(h)  => h
    case Paint.Value(_)  => throw valueNeedsScale

  private def valueNeedsScale: IllegalArgumentException =
    IllegalArgumentException(
      "Paint.Value is a data value that a ColorScale colors: draw it in a Figure with a colorScale, or use Paint.Fixed"
    )

  /** This palette with the categorical slots `colors` (light mode) and `dark` (dark mode, default: the same colors).
    * Slot `k` colors homological degree `k`, so list them in degree order.
    */
  def withSeries(colors: Seq[String], dark: Seq[String] = Nil): Palette =
    require(colors.nonEmpty, "Palette.withSeries: give at least one color")
    val darkColors = if dark.isEmpty then colors else dark
    require(
      darkColors.size == colors.size,
      s"Palette.withSeries: ${colors.size} light colors but ${darkColors.size} dark ones"
    )
    copy(seriesLight = colors.toIndexedSeq, seriesDark = darkColors.toIndexedSeq)

  /** This palette on other surfaces (the figure's background): `light` in light mode, `dark` (default: unchanged) in
    * dark mode. `withSurface("#ffffff")` for a figure that goes onto a white page.
    */
  def withSurface(light: String, dark: String = ""): Palette =
    copy(
      light = this.light.copy(surface = light),
      dark = if dark.isEmpty then this.dark else this.dark.copy(surface = dark)
    )

  /** This palette with each degree's own color where a [[DegreeStyle]] gives one. */
  def withDegrees(styles: Map[Int, DegreeStyle]): Palette =
    styles.foldLeft(this) { case (p, (k, s)) =>
      val slot = k % p.seriesLight.size
      val lightSeries = s.color.fold(p.seriesLight)(c => p.seriesLight.updated(slot, c))
      val darkSeries = s.darkColor.orElse(s.color).fold(p.seriesDark)(c => p.seriesDark.updated(slot, c))
      p.copy(seriesLight = lightSeries, seriesDark = darkSeries)
    }

  /** The CSS custom properties of both modes, scoped to `selector`. */
  def cssVariables(selector: String): String =
    def block(series: IndexedSeq[String], c: Chrome): String =
      (series.zipWithIndex.map((h, k) => s"--s$k:$h") ++ Seq(
        s"--surface:${c.surface}",
        s"--ink:${c.ink}",
        s"--ink2:${c.secondary}",
        s"--muted:${c.muted}",
        s"--grid:${c.grid}",
        s"--axis:${c.axis}"
      )).mkString(";")
    s"$selector{${block(seriesLight, light)}}" +
      s"@media (prefers-color-scheme: dark){$selector{${block(seriesDark, dark)}}}"

  /** `t` in `[0, 1]` on the sequential ramp. */
  def sequentialAt(t: Double): String = Palette.along(sequential, t)

  /** `t` in `[-1, 1]`: `divergingNegative` below zero, the neutral midpoint at zero, `divergingPositive` above. */
  def divergingAt(t: Double): String =
    if t < 0 then Palette.mix(divergingMidpoint, divergingNegative, math.min(1.0, -t))
    else Palette.mix(divergingMidpoint, divergingPositive, math.min(1.0, t))

object Palette:

  /** TDA4j's Teal & Gold (`brand/DECISIONS.md`), stepped for charts: the docs site's cream and blue-black surfaces,
    * teal ink, and categorical slots that open with the brand's teal and gold (degree 0 teal, degree 1 gold, degree 2
    * brick, the site's error hue) at the lightness and chroma charts need. Checked with the dataviz palette validator:
    * the first three slots pass every check on ALL pairs in both modes (a persistence diagram puts any two degrees side
    * by side; worst color-vision-deficiency ΔE 8.5 light, 9.2 dark), all eight on adjacent pairs. The sequential ramp
    * is teal (light for small values), the diverging pair teal against gold, a blue-yellow axis every color vision
    * sees.
    */
  val brand: Palette = Palette(
    seriesLight = IndexedSeq("#1a729b", "#a68018", "#b54436", "#099393", "#aa5910", "#7f5bb6", "#5b7f1d", "#a84e7c"),
    seriesDark = IndexedSeq("#4aa0c7", "#b98e1b", "#c65954", "#25a6a6", "#bd6b2a", "#916dca", "#6c9133", "#bc5f8e"),
    light = Chrome("#f8f7f4", "#1d2a32", "#205976", "#7d8a91", "#e4e2dc", "#c5c9c9"),
    dark = Chrome("#161b1e", "#e8eef1", "#81bde0", "#7f8c93", "#252d32", "#3a454b"),
    sequential =
      IndexedSeq("#e2f1fa", "#afdaf4", "#7fc1e9", "#55a8d7", "#368ebd", "#2a749b", "#2c5a75", "#2f414c", "#1f2b32"),
    divergingNegative = "#1a729b",
    divergingPositive = "#a68018",
    divergingMidpoint = "#d9d7d0"
  )

  /** The reference palette of the dataviz method: blue, orange, aqua, yellow, magenta, green, violet, red on near-white
    * and near-black, a blue ramp, blue against red.
    */
  val reference: Palette = Palette(
    seriesLight = IndexedSeq("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"),
    seriesDark = IndexedSeq("#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"),
    light = Chrome("#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"),
    dark = Chrome("#1a1a19", "#f0efec", "#c3c2b7", "#898781", "#2c2c2a", "#383835"),
    sequential = IndexedSeq(
      "#cde2fb",
      "#b7d3f6",
      "#9ec5f4",
      "#86b6ef",
      "#6da7ec",
      "#5598e7",
      "#3987e5",
      "#2a78d6",
      "#256abf",
      "#1c5cab",
      "#184f95",
      "#104281",
      "#0d366b"
    ),
    divergingNegative = "#2a78d6",
    divergingPositive = "#e34948",
    divergingMidpoint = "#c3c2b7"
  )

  /** For a paper: a pure white surface (the brand's cream prints as a grey patch), black ink, and colors that stay
    * apart on paper. Degree colors are the Okabe-Ito blue, vermillion and bluish green first, so a persistence diagram
    * keeps its degrees apart for every kind of color vision; the first three pass every check of the dataviz validator
    * on ALL pairs, all eight on adjacent pairs. The sequential ramp is ColorBrewer's Blues (lightness falls steadily,
    * so it survives a grayscale printer), the diverging pair is blue against vermillion.
    *
    * Paper has one look: the dark mode is the light one, so `Theme.Dark` draws the same figure. It is for a figure that
    * has its own white background, [[Style.print]] being the fonts to go with it; for a figure that sits on a page of
    * either color, see [[Palette.clear]].
    */
  val print: Palette =
    val chrome = Chrome("#ffffff", "#000000", "#333333", "#595959", "#dddddd", "#000000")
    val series = IndexedSeq("#0072b2", "#d55e00", "#009e73", "#7f5bb6", "#a68018", "#b5528a", "#5b7f1d", "#7b3f00")
    Palette(
      seriesLight = series,
      seriesDark = series,
      light = chrome,
      dark = chrome,
      sequential = IndexedSeq(
        "#f7fbff",
        "#deebf7",
        "#c6dbef",
        "#9ecae1",
        "#6baed6",
        "#4292c6",
        "#2171b5",
        "#08519c",
        "#08306b"
      ),
      divergingNegative = "#0072b2",
      divergingPositive = "#d55e00",
      divergingMidpoint = "#f7f7f7"
    )

  /** For a paper that prints in black and white, or a reader who sees no color: every color is a gray on [[print]]'s
    * white surface and black ink. Degrees are told apart by lightness (a dark, a light and a middle gray first, each at
    * least 3:1 against the white so that a mark shows) and, as in every palette, by the marker shape and the legend:
    * past four degrees the grays repeat in spirit, so rely on the shapes. The sequential ramp is ColorBrewer's Greys
    * (darker for larger). A diverging scale is a dark gray for the negative end against a middle gray for the positive
    * one around a light middle: the sign shows by how dark the end is, which is a weaker cue than a hue, so use
    * [[print]] for a cocycle's values when color is available.
    *
    * A gray palette has no chroma, so the dataviz validator's chroma floor, which asks for colorful series, fails it by
    * construction; its lightness steps are what is checked. Like [[print]] it has one look for both modes.
    */
  val grayscale: Palette =
    val chrome = print.light
    val series = IndexedSeq("#1a1a1a", "#949494", "#555555", "#3b3b3b", "#777777", "#474747", "#858585", "#2b2b2b")
    Palette(
      seriesLight = series,
      seriesDark = series,
      light = chrome,
      dark = chrome,
      sequential = IndexedSeq(
        "#f7f7f7",
        "#f0f0f0",
        "#d9d9d9",
        "#bdbdbd",
        "#969696",
        "#737373",
        "#525252",
        "#252525",
        "#000000"
      ),
      divergingNegative = "#252525",
      divergingPositive = "#8c8c8c",
      divergingMidpoint = "#f0f0f0"
    )

  /** For a figure with no background of its own ([[Style.clear]]) that goes onto a page of either color: pure black
    * elements for a light page (`Theme.Light`) and pure white ones for a dark page (`Theme.Dark`, or `Theme.Auto`,
    * which follows the reader's setting, so use it only where the page does too). The degree colors are [[print]]'s on
    * a light page and the brand's dark steps on a dark one, each validated on its own surface (pure white, pure black:
    * the first three on all pairs, all eight on adjacent pairs); the heatmap ramps are [[print]]'s.
    *
    * It is [[print]] with a dark mode of its own; with no [[Style.clear]], the figure has the white or the black
    * background of its mode.
    */
  val clear: Palette =
    Palette(
      seriesLight = print.seriesLight,
      seriesDark = brand.seriesDark,
      light = print.light,
      dark = Chrome("#000000", "#ffffff", "#cccccc", "#a6a6a6", "#2b2b2b", "#ffffff"),
      sequential = print.sequential,
      divergingNegative = print.divergingNegative,
      divergingPositive = print.divergingPositive,
      divergingMidpoint = print.divergingMidpoint
    )

  /** The palette figures use unless given another. */
  val default: Palette = brand

  /** The palette called `name`, in any case: `brand` (the default), `reference`, `print`, `grayscale` or `clear`. */
  def named(name: String): Palette = name.trim.lowerRoot match
    case "brand"                   => brand
    case "reference"               => reference
    case "print"                   => print
    case "grayscale" | "greyscale" => grayscale
    case "clear"                   => clear
    case other                     =>
      throw IllegalArgumentException(
        s"unknown palette '$other'; expected one of brand, reference, print, grayscale, clear"
      )

  // ----- color arithmetic in OKLab (Björn Ottosson's matrices), so ramps interpolate perceptually

  private def rgb(h: String): (Double, Double, Double) =
    val v = Integer.parseInt(h.stripPrefix("#"), 16)
    (((v >> 16) & 255) / 255.0, ((v >> 8) & 255) / 255.0, (v & 255) / 255.0)

  private def toHex(r: Double, g: Double, b: Double): String =
    def c(x: Double) = math.round(math.max(0.0, math.min(1.0, x)) * 255).toInt
    f"#${c(r)}%02x${c(g)}%02x${c(b)}%02x"

  private def lin(c: Double): Double = if c <= 0.04045 then c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
  private def gam(c: Double): Double =
    if c <= 0.0031308 then 12.92 * c else 1.055 * math.pow(math.max(c, 0.0), 1 / 2.4) - 0.055

  private def toOklab(h: String): (Double, Double, Double) =
    val (r0, g0, b0) = rgb(h)
    val (r, g, b) = (lin(r0), lin(g0), lin(b0))
    val l = math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    (
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    )

  private def fromOklab(lab: (Double, Double, Double)): String =
    val (ll, a, b) = lab
    val l = math.pow(ll + 0.3963377774 * a + 0.2158037573 * b, 3)
    val m = math.pow(ll - 0.1055613458 * a - 0.0638541728 * b, 3)
    val s = math.pow(ll - 0.0894841775 * a - 1.2914855480 * b, 3)
    toHex(
      gam(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      gam(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      gam(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    )

  /** The color a fraction `t` of the way from `h1` to `h2`, in OKLab. */
  def mix(h1: String, h2: String, t: Double): String =
    val (l1, a1, b1) = toOklab(h1)
    val (l2, a2, b2) = toOklab(h2)
    fromOklab((l1 + t * (l2 - l1), a1 + t * (a2 - a1), b1 + t * (b2 - b1)))

  /** The color at `t` in `[0, 1]` along evenly spaced `stops`. */
  def along(stops: IndexedSeq[String], t: Double): String =
    val x = math.max(0.0, math.min(1.0, if t.isNaN then 0.0 else t)) * (stops.size - 1)
    val i = math.min(stops.size - 2, x.toInt)
    mix(stops(i), stops(i + 1), x - i)

  /** An angle as a fraction of a turn, `t` (taken mod 1), on a cyclic map: equal lightness and chroma all the way round
    * (OKLCH), so no angle looks more important than another and `0` meets `1` without a seam. The same in every
    * palette: a cyclic quantity needs a cyclic map, which no brand ramp is.
    */
  def cyclicAt(t: Double): String =
    val h = 2 * math.Pi * (t - math.floor(t))
    fromOklab((0.66, 0.12 * math.cos(h), 0.12 * math.sin(h)))
