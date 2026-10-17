package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** Where a figure's legend goes. `Right`, `Left`, `Top` and `Bottom` are beside the plot (the sides take the room they
  * need); the four corners are inside the plot area, over its corner, where a plot has room (the lower right of a
  * persistence diagram, below the diagonal).
  */
enum LegendPosition:
  /** Beside the plot, at the top: the default. */
  case Right

  /** Beside the plot, at its left, before the y axis. */
  case Left

  /** Above the plot, in a row (or several, when the labels are long). */
  case Top

  /** Below the plot, in a row (or several, when the labels are long): for a figure that has little width to spare. */
  case Bottom

  /** Inside the plot area, at its upper left corner. */
  case UpperLeft

  /** Inside the plot area, at its upper right corner. */
  case UpperRight

  /** Inside the plot area, at its lower left corner. */
  case LowerLeft

  /** Inside the plot area, at its lower right corner. */
  case LowerRight

  /** Not drawn. */
  case Hidden

object LegendPosition:
  /** The position called `name`, in any case: `right`, `left`, `top`, `bottom`, `upper-left`, `upper-right`,
    * `lower-left`, `lower-right`, or `none` (also `hidden`).
    */
  def named(name: String): LegendPosition = name.trim.toLowerCase(java.util.Locale.ROOT) match
    case "right"                        => Right
    case "left"                         => Left
    case "top"                          => Top
    case "bottom"                       => Bottom
    case "upper-left" | "top-left"      => UpperLeft
    case "upper-right" | "top-right"    => UpperRight
    case "lower-left" | "bottom-left"   => LowerLeft
    case "lower-right" | "bottom-right" => LowerRight
    case "none" | "hidden"              => Hidden
    case other                          =>
      throw IllegalArgumentException(
        s"unknown legend position '$other'; expected one of right, left, top, bottom, upper-left, upper-right, lower-left, lower-right, none"
      )

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
  *   where the legend goes; unset, a plot has its own place (beside the plot, or at the lower right of a persistence
  *   diagram)
  * @param transparent
  *   `true` to draw no background: the figure takes the color of the page behind it
  */
final case class Style(
  font: String = Style.DefaultFont,
  textScale: Double = 1.0,
  markScale: Double = 1.0,
  grid: Boolean = true,
  legend: Optional[LegendPosition] = Optional.empty,
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

  /** The font stack of [[print]] and [[clear]]: families a converter or a publisher's pipeline knows by name. */
  val PrintFont: String = "Helvetica, Arial, 'Liberation Sans', sans-serif"

  /** For a paper, where the figure has a white background of its own: a font stack that names real families
    * (`system-ui`, the default's first choice, is a browser keyword that other SVG tools do not know). Pair it with
    * [[Palette.print]], whose surface is exactly white and whose elements are black. For a serif face to match the
    * text, `Style.print.copy(font = "'Times New Roman', Times, serif")`.
    */
  val print: Style = Style(font = PrintFont)

  /** For a figure that goes onto a page, a slide or a plot of its own, with no background, so that it takes the color
    * of what is behind it, and the fonts of [[print]]. Pair it with [[Palette.clear]], whose elements are black for a
    * light page and white for a dark one: `Theme.Light` or `Theme.Dark` picks.
    */
  val clear: Style = Style(font = PrintFont, transparent = true)

  /** The style called `name`, in any case: `default`, `slides`, `print` or `clear`. */
  def named(name: String): Style = name.trim.toLowerCase(java.util.Locale.ROOT) match
    case "default" => default
    case "slides"  => slides
    case "print"   => print
    case "clear"   => clear
    case other     =>
      throw IllegalArgumentException(s"unknown style '$other'; expected one of default, slides, print, clear")
