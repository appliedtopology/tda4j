package org.appliedtopology.tda4j.plot

/** Where a figure's legend goes. */
enum LegendPosition:
  /** Beside the plot, at the top: the default. */
  case Right

  /** Below the plot, in a row (or several, when the labels are long): for a figure that has little width to spare. */
  case Bottom

  /** Not drawn. */
  case Hidden

object LegendPosition:
  /** The position called `name`, in any case: `right`, `bottom` or `none` (also `hidden`). */
  def named(name: String): LegendPosition = name.trim.toLowerCase(java.util.Locale.ROOT) match
    case "right"           => Right
    case "bottom"          => Bottom
    case "none" | "hidden" => Hidden
    case other             =>
      throw IllegalArgumentException(s"unknown legend position '$other'; expected one of right, bottom, none")

/** The look of a figure that is not its data: the font, the size of the text and of the marks, the gridlines, the
  * legend's place and the background. A style says nothing about what is drawn, so one value serves every figure of a
  * paper or a talk: `fig + Style.slides`.
  *
  * A style replaces the figure's whole style; to change one setting of a style, `Style.slides.copy(grid = false)`.
  * (`Theme` is something else: whether colors are light, dark, or follow the reader.)
  *
  * @param font
  *   the CSS `font-family` of every text
  * @param textScale
  *   a factor on every text size, and on the margins that make room for text
  * @param markScale
  *   a factor on the size of markers and the width of lines
  * @param grid
  *   `false` to draw no gridlines on either axis
  * @param legend
  *   where the legend goes
  * @param transparent
  *   `true` to draw no background: the figure takes the color of the page behind it
  */
final case class Style(
  font: String = Style.DefaultFont,
  textScale: Double = 1.0,
  markScale: Double = 1.0,
  grid: Boolean = true,
  legend: LegendPosition = LegendPosition.Right,
  transparent: Boolean = false
) extends Tweak:
  require(textScale > 0 && textScale.isFinite, s"Style.textScale must be a positive number, not $textScale")
  require(markScale > 0 && markScale.isFinite, s"Style.markScale must be a positive number, not $markScale")
  require(font.nonEmpty && !font.contains('<'), "Style.font must be a CSS font-family list, such as \"Georgia, serif\"")

  def onFigure(f: Figure): Figure = f.copy(style = this)
  override def onScene(s: Scene): Scene = s.copy(style = this)

object Style:
  /** The font stack of the default style: the reader's system font. */
  val DefaultFont: String = "system-ui,-apple-system,'Segoe UI',sans-serif"

  /** What a figure has unless given another. */
  val default: Style = Style()

  /** For a projector or a slide: text and marks 30% larger. Pair it with a bigger `Size` for a full-slide figure. */
  val slides: Style = Style(textScale = 1.3, markScale = 1.3)
