package org.appliedtopology.tda4j.plot

/** Which of a [[Palette]]'s color maps a [[ColorScale]] reads.
  *
  * `Sequential` is for magnitudes (light to dark), `Diverging` for signed values (two hues about a neutral middle), and
  * `Cyclic` for angles (no seam where the end meets the start).
  */
enum Ramp:
  case Sequential, Diverging, Cyclic

/** How the data values of [[Paint.Value]] marks become colors: the figure's one continuous color scale, and the color
  * bar drawn from it.
  *
  * Marks hold the values, not the colors, so the colors follow the figure's [[Palette]] and can be re-limited
  * (`Scale.color(min = 0, max = 10)`) to put two figures on one scale. A `Diverging` scale is symmetric: `lo = -hi`. A
  * `Cyclic` scale reads its values as turns, `0` and `1` being the same color.
  *
  * @param lo
  *   the value of the low end of the ramp
  * @param hi
  *   the value of the high end
  * @param label
  *   the color bar's title
  * @param transform
  *   how values are spread along a `Sequential` ramp (`Transform.Sqrt` for a heavy-tailed image); the other ramps are
  *   always linear. A `Log10` scale that starts at 0 or below is a symmetric log, linear within a thousandth of its
  *   largest magnitude, so that every value has a color
  * @param stops
  *   the colors of a `Sequential` ramp, light to dark, instead of the palette's own (for example
  *   [[ColorScale.viridis]]); evenly spaced and mixed in OKLab
  * @param guide
  *   whether to draw the color bar
  */
final case class ColorScale(
  ramp: Ramp = Ramp.Sequential,
  lo: Double = 0.0,
  hi: Double = 1.0,
  label: String = "",
  transform: Transform = Transform.Identity,
  stops: Option[IndexedSeq[String]] = None,
  guide: Boolean = true
):
  /** The color for the value `v` (hex), as `palette` has it. */
  def hex(v: Double, palette: Palette): String = ramp match
    case Ramp.Sequential =>
      val tr = spread
      val t =
        if hi > lo then (tr(v) - tr(lo)) / (tr(hi) - tr(lo))
        else 0.0
      Palette.along(stops.getOrElse(palette.sequential), t)
    case Ramp.Diverging =>
      val u = if hi > 0 then v / hi else 0.0
      palette.divergingAt(if u.isNaN then 0.0 else math.max(-1.0, math.min(1.0, u)))
    case Ramp.Cyclic => Palette.cyclicAt(v)

  /** The transform the values are spread by: a log scale with no positive low end is a symmetric log. */
  private def spread: Transform = transform match
    case Transform.Log10 if lo <= 0 => Transform.SymLog(math.max(math.abs(lo), math.abs(hi)) / 1000.0)
    case other                      => other

  /** The color a fraction `t` of the way along the ramp, `0` at the low end: what the color bar draws. */
  def barColor(t: Double, palette: Palette): String = ramp match
    case Ramp.Sequential => Palette.along(stops.getOrElse(palette.sequential), t)
    case Ramp.Diverging  => palette.divergingAt(2 * t - 1)
    case Ramp.Cyclic     => Palette.cyclicAt(t)

object ColorScale:
  /** The viridis ramp (Smith and van der Walt, as matplotlib has it): dark purple to yellow, perceptually even and
    * readable in grayscale and with color-vision deficiencies. As `Scale.color(stops = ColorScale.viridis)`.
    */
  val viridis: IndexedSeq[String] =
    IndexedSeq(
      "#440154",
      "#482878",
      "#3e4989",
      "#31688e",
      "#26828e",
      "#1f9e89",
      "#35b779",
      "#6ece58",
      "#b5de2b",
      "#fde725"
    )

  /** White to black: for a figure that is printed in grayscale. */
  val greys: IndexedSeq[String] =
    IndexedSeq("#f5f5f5", "#d9d9d9", "#bdbdbd", "#969696", "#737373", "#525252", "#252525")

/** What to change about the look of one homological degree (more exactly, one categorical slot: the `k`-th landscape
  * for `Plot.landscape`) wherever it appears in a figure: its mark color, its legend label and its marker shape.
  * Whatever is `None` stays as the palette and the plot have it. `marker` changes the look of the degree's markers
  * (size, opacity, open or filled, the edge), over the figure's own.
  *
  * @param color
  *   the color in light mode (and dark, unless `darkColor` is given)
  * @param darkColor
  *   the color in dark mode
  */
final case class DegreeStyle(
  color: Option[String] = None,
  darkColor: Option[String] = None,
  label: Option[String] = None,
  shape: Option[Shape] = None,
  marker: MarkerStyle = MarkerStyle()
):
  /** `later` over this one: what `later` sets wins. A new `color` without a `darkColor` is the color of both modes, not
    * only of the light one beside an older dark one.
    */
  def merge(later: DegreeStyle): DegreeStyle =
    val (light, dark) =
      if later.color.isDefined then (later.color, later.darkColor)
      else (color, later.darkColor.orElse(darkColor))
    DegreeStyle(light, dark, later.label.orElse(label), later.shape.orElse(shape), marker.merge(later.marker))
