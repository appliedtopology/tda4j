package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import java.util.Locale

/** The starting look of the plots you make: tweaks that every plot constructor (`Plot.barcode`, `Plot.diagram`, ...)
  * applies to what it builds, before anything you add yourself, so that `Plot.barcode(d)` is already in your house
  * style and a tweak added afterwards, or a `palette` named in the call, wins.
  *
  * A `Look` is a Scala `given`, found by the constructors without an import. Put one where the plots are made, such as
  * at the top of a file or a block:
  * {{{
  * given Look = Look.print + Text(TextRole.Title, size = 18)
  * Plot.barcode(d).save("barcode.svg") // in the print look
  * }}}
  * A look is made of the same tweaks as a plot (`Palette`, `Style`, `Text`, `Lines`, `Numbers`, ...). A tweak that a
  * kind of plot cannot have is left out for it, so one look serves figures and 3-D scenes alike (the tick labels of a
  * look do nothing to a scene). With no `given Look` the constructors use [[Look.none]], and a plot is as it was.
  */
final case class Look(tweaks: Vector[Tweak] = Vector.empty):
  /** This look, then `tweak`. */
  def +(tweak: Tweak): Look = copy(tweaks = tweaks :+ tweak)

  /** This look, then everything `that` one does. */
  def ++(that: Look): Look = copy(tweaks = tweaks ++ that.tweaks)

  def isEmpty: Boolean = tweaks.isEmpty

  /** `figure` with the tweaks of this look that apply to a figure. */
  def on(figure: Figure): Figure =
    tweaks.foldLeft(figure)((f, t) =>
      try t.onFigure(f)
      catch case _: UnsupportedOperationException => f
    )

  /** `scene` with the tweaks of this look that apply to a 3-D scene. */
  def on(scene: Scene): Scene =
    tweaks.foldLeft(scene)((s, t) =>
      try t.onScene(s)
      catch case _: UnsupportedOperationException => s
    )

  /** What a constructor does with the figure it builds: this look, and then the `palette` the caller named, if any,
    * because what is written in the call beats the look.
    */
  def figure(palette: Optional[Palette] = Optional.empty)(made: => Figure): Figure =
    val styled = on(made)
    palette.toOption.fold(styled)(styled + _)

  /** What a constructor does with the scene it builds. */
  def scene(made: => Scene): Scene = on(made)

  override def toString: String = if isEmpty then "Look.none" else s"Look(${tweaks.mkString(", ")})"

object Look:
  /** The look found when there is no `given Look` of your own: nothing is changed. */
  given standard: Look = Look()

  /** Nothing changed: a plot as its constructor makes it. */
  val none: Look = Look()

  /** For a paper: [[Palette.print]] and [[Style.print]]. */
  val print: Look = Look() + Palette.print + Style.print

  /** For a figure that takes the color of its page: [[Palette.clear]] and [[Style.clear]]. */
  val clear: Look = Look() + Palette.clear + Style.clear

  /** The look called `name`, in any case: `default` (none), `print` or `clear`. */
  def named(name: String): Look = name.trim.toLowerCase(Locale.ROOT) match
    case "default" | "none" => none
    case "print"            => print
    case "clear"            => clear
    case other => throw IllegalArgumentException(s"unknown look '$other'; expected one of default, print, clear")

  /** The look the `settings` write, in order: `Look.settings("palette=print", "title.size=18")`. See [[Settings]]. */
  def settings(specs: String*): Look = specs.foldLeft(none)((look, spec) => look + Settings.parse(spec))
