package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import java.util.concurrent.atomic.AtomicLong

/** Which coordinate of an item a [[Behavior.Follows]] moves. */
enum Coordinate:
  case X, Y

/** Which end of a segment a [[Behavior.Follows]] moves: the first, the second, or both (a rule has one place and counts
  * as both).
  */
enum Ends:
  case First, Second, Both

/** What a control does to the item it is attached to. A behavior is a small value: attach one or several (`a + b`) to a
  * mark of a [[Panel]], and the page shows the item accordingly whenever the control moves; the same behaviors give a
  * still picture at any value of the control (`Interactive.at`), which is how a talk's frames are made.
  *
  *   - [[Behavior.Shown]]: the item is there while the control's value `v` has `from <= v < until`. A simplex that
  *     enters the filtration at `f` is shown from `f`; a class that lives on `[b, d)` is shown from `b` until `d`.
  *   - [[Behavior.Follows]]: a coordinate of the item (of one or both ends of a segment, or of a rule) is the control's
  *     value held between `lo` and `hi`. A rule that follows the control is the movable line; a segment that follows it
  *     between `b` and `d` is a bar growing until the line passes its death.
  *   - [[Behavior.Radius]]: the radius of the discs of a mark (data units) is `factor` times the control's value: the
  *     balls whose overlaps a Vietoris-Rips or Cech complex records.
  *   - [[Behavior.Custom]]: your own. The page calls the function you register under `kind` (see
  *     [[Interactive.withScript]]); a still picture shows the item as it was drawn.
  */
enum Behavior:
  /** Shown while `from <= v < until` for the value `v` of `control`. */
  case Shown(control: String, from: Double, until: Double)

  /** `coordinate` of `ends` is the value of `control`, no less than `lo` and no more than `hi`. */
  case Follows(control: String, coordinate: Coordinate, ends: Ends, lo: Double, hi: Double)

  /** The radius, in data units, of the discs of the mark is `factor` times the value of `control`. */
  case Radius(control: String, factor: Double)

  /** A behavior of your own: `kind` names the function that handles it (at least two characters, letters, digits or
    * `-`), and `args` are the strings it is given.
    */
  case Custom(kind: String, control: String, args: Vector[String])

  /** The control this behavior listens to. */
  def control: String

  /** This behavior and `that` one, on the same item. */
  def +(that: Behavior): Reaction = Reaction(Vector(this, that))

  /** This behavior, then everything `that` reaction does. */
  def +(that: Reaction): Reaction = Reaction(this +: that.behaviors)

object Behavior:
  private val inf = Double.PositiveInfinity

  /** Shown from `from` on: an item that enters at `from` and stays. */
  def shownFrom(control: String, from: Double): Behavior = Shown(control, from, inf)

  /** Shown until `until`, not including it. */
  def shownUntil(control: String, until: Double): Behavior = Shown(control, -inf, until)

  /** Shown from `from` until `until`, not including it. */
  def shownBetween(control: String, from: Double, until: Double): Behavior = Shown(control, from, until)

  /** The x coordinate follows `control`, held between `lo` and `hi`; of both ends unless `ends` says one. */
  def followsX(
    control: String,
    lo: Double = Double.NegativeInfinity,
    hi: Double = Double.PositiveInfinity,
    ends: Ends = Ends.Both
  ): Behavior = Follows(control, Coordinate.X, ends, lo, hi)

  /** The y coordinate follows `control`, held between `lo` and `hi`; of both ends unless `ends` says one. */
  def followsY(
    control: String,
    lo: Double = Double.NegativeInfinity,
    hi: Double = Double.PositiveInfinity,
    ends: Ends = Ends.Both
  ): Behavior = Follows(control, Coordinate.Y, ends, lo, hi)

  /** The radius of the discs is `factor` times the value of `control`. */
  def radius(control: String, factor: Double = 1.0): Behavior = Radius(control, factor)

  /** A behavior of your own, handled by the function registered as `kind` on the page. */
  def custom(kind: String, control: String, args: String*): Behavior = Custom(kind, control, args.toVector)

/** What happens to one item: its behaviors, in order. `a + b` builds one. */
final case class Reaction(behaviors: Vector[Behavior]):
  /** This reaction, then `behavior`. */
  def +(behavior: Behavior): Reaction = Reaction(behaviors :+ behavior)

  /** This reaction, then everything `that` one does. */
  def +(that: Reaction): Reaction = Reaction(behaviors ++ that.behaviors)

  def isEmpty: Boolean = behaviors.isEmpty

object Reaction:
  /** Nothing happens: the item is always there. */
  val none: Reaction = Reaction(Vector.empty)

/** A number the viewer can set: a slider with a name, a range and a starting value, which behaviors refer to by name.
  *
  * @param name
  *   what behaviors call it (a letter, then letters, digits, `_` or `-`)
  * @param label
  *   what the page writes beside it (`ε`); the name when empty
  * @param stops
  *   when given, the slider steps through exactly these values (the values at which the picture changes), in order, and
  *   the arrow keys move from one to the next; otherwise it runs smoothly from `min` to `max`
  * @param step
  *   the grid of a smooth slider: a thousandth of the range when not given
  * @param play
  *   when given, a play button sweeps from the value the slider is at to `max` in this many seconds
  */
final case class Control(
  name: String,
  min: Double,
  max: Double,
  value: Double,
  label: String = "",
  stops: Vector[Double] = Vector.empty,
  step: Option[Double] = None,
  play: Option[Double] = None
):
  require(
    name.matches("[A-Za-z][A-Za-z0-9_-]*"),
    s"Control: the name \"$name\" should be a letter and then letters, digits, _ or -"
  )
  require(
    min.isFinite && max.isFinite && min < max,
    s"Control $name: the minimum ($min) should be less than the maximum ($max), both numbers"
  )
  require(value >= min && value <= max, s"Control $name: the starting value $value is outside $min to $max")
  require(
    stops.forall(s => s.isFinite && s >= min && s <= max) && stops.zip(stops.drop(1)).forall(_ < _),
    s"Control $name: stops should be increasing numbers within $min to $max"
  )
  require(step.forall(s => s > 0 && s.isFinite), s"Control $name: the step should be a positive number")
  require(play.forall(s => s > 0 && s.isFinite), s"Control $name: the play time should be a positive number of seconds")

  /** What the page writes beside the slider. */
  def shownName: String = if label.isEmpty then name else label

  /** This control with the label `text`. */
  def withLabel(text: String): Control = copy(label = text)

  /** This control stepping through `values` (sorted, repeats dropped) instead of sliding smoothly; the starting value
    * moves to the nearest stop.
    */
  def withStops(values: Seq[Double]): Control =
    val sorted = values.filter(v => v >= min && v <= max).distinct.sorted.toVector
    if sorted.isEmpty then copy(stops = Vector.empty)
    else copy(stops = sorted, value = sorted.minBy(s => math.abs(s - value)))

  /** This control with a play button that sweeps to the maximum in `seconds`. */
  def withPlay(seconds: Double): Control = copy(play = Some(seconds))

  /** This control starting at `v`. */
  def startingAt(v: Double): Control = copy(value = v)

object Control:
  /** A slider from `min` to `max`, starting at `value` (the minimum when not given). */
  def slider(name: String, min: Double, max: Double, value: Optional[Double] = Optional.empty): Control =
    Control(name, min, max, value.getOrElse(min))

/** One picture of an interactive plot: a 2-D [[Figure]] or a 3-D [[Scene]], and what each of the items of its marks (or
  * layers) does when a control moves. `reactions` maps the index of a mark (layer) to a [[Reaction]] for all its items,
  * or one for each item, like the paints of a mark.
  */
sealed trait Panel:
  def reactions: Map[Int, IndexedSeq[Reaction]]

object Panel:
  /** A panel that shows nothing of its own accord. */
  def apply(figure: Figure): Flat = Flat(figure)

  /** A 3-D panel that shows nothing of its own accord. */
  def apply(scene: Scene): Spatial = Spatial(scene)

  // what a reaction may ask of an item of a mark: which behaviors the page can carry out, on which marks
  private[plot] def check(kind: String, what: String, reaction: Reaction, allowed: Behavior => Boolean): Unit =
    reaction.behaviors.find(!allowed(_)).foreach { b =>
      throw IllegalArgumentException(s"Panel: $b cannot be attached to $what ($kind)")
    }

  private[plot] def sizeCheck(what: String, reactions: IndexedSeq[Reaction], items: Int): Unit =
    require(
      reactions.size == 1 || reactions.size == items,
      s"Panel: $what has $items items, so it takes one reaction for all of them or $items, not ${reactions.size}"
    )

  /** A 2-D panel. Marks are added with their reactions, in drawing order: `Panel(fig).react(mark, behavior)`. */
  final case class Flat(figure: Figure, reactions: Map[Int, IndexedSeq[Reaction]] = Map.empty) extends Panel:
    /** `mark` added to the figure, its items all reacting as `behavior` says. */
    def react(mark: Mark, behavior: Behavior): Flat = reactEach(mark, IndexedSeq(Reaction(Vector(behavior))))

    /** `mark` added to the figure, its items all reacting as `reaction` says. */
    def react(mark: Mark, reaction: Reaction): Flat = reactEach(mark, IndexedSeq(reaction))

    /** `mark` added to the figure, item `i` reacting as `reactions(i)` says. */
    def reactEach(mark: Mark, reactions: IndexedSeq[Reaction]): Flat =
      val items = Interactive.itemCount(mark)
      require(
        items > 0,
        s"Panel: a ${mark.getClass.getSimpleName.stripSuffix("$")} cannot react (it has no items the page can find); " +
          "the marks that can are Points, Segments, Polygons, Discs, Boxes, Line and Rule"
      )
      Panel.sizeCheck(s"the ${mark.getClass.getSimpleName.stripSuffix("$")}", reactions, items)
      reactions.foreach(r =>
        Panel.check("2-D", mark.getClass.getSimpleName.stripSuffix("$"), r, Interactive.fitsMark(mark))
      )
      copy(
        figure = figure.copy(marks = figure.marks :+ mark),
        reactions = this.reactions.updated(figure.marks.size, reactions)
      )

    /** The same with `change` applied to the figure. */
    def mapFigure(change: Figure => Figure): Flat = copy(figure = change(figure))

  /** A 3-D panel; only [[Behavior.Shown]] and [[Behavior.Custom]] can be carried out on a canvas. */
  final case class Spatial(scene: Scene, reactions: Map[Int, IndexedSeq[Reaction]] = Map.empty) extends Panel:
    /** `layer` added to the scene, its items all reacting as `behavior` says. */
    def react(layer: Layer, behavior: Behavior): Spatial = reactEach(layer, IndexedSeq(Reaction(Vector(behavior))))

    /** `layer` added to the scene, its items all reacting as `reaction` says. */
    def react(layer: Layer, reaction: Reaction): Spatial = reactEach(layer, IndexedSeq(reaction))

    /** `layer` added to the scene, item `i` reacting as `reactions(i)` says. */
    def reactEach(layer: Layer, reactions: IndexedSeq[Reaction]): Spatial =
      val items = Interactive.itemCount(layer)
      Panel.sizeCheck("the layer", reactions, items)
      reactions.foreach(r =>
        Panel.check(
          "3-D",
          "a layer",
          r,
          {
            case _: Behavior.Shown | _: Behavior.Custom => true
            case _                                      => false
          }
        )
      )
      copy(
        scene = scene.copy(layers = scene.layers :+ layer),
        reactions = this.reactions.updated(scene.layers.size, reactions)
      )

    /** The same with `change` applied to the scene. */
    def mapScene(change: Scene => Scene): Spatial = copy(scene = change(scene))

  // ----- panels from the plots of persistent homology

  private val inf = Double.PositiveInfinity

  /** The persistence diagram `figure` (from `Plot.diagram`) as the diagram of a sweep: each class is a vertical segment
    * from the diagonal that grows with `control` until the line passes the class's death, and then a point. The movable
    * horizontal line is the control's value. Essential classes (on the line marked `∞`) never become points; their
    * segment grows with the line.
    */
  def sweepDiagram(figure: Figure, control: String = "t"): Flat =
    val top = figure.marks.collectFirst { case r: Mark.Rule if !r.vertical => r.at }
    val kept = figure.marks.filter {
      case _: Mark.Points => false
      case _              => true
    }
    val start = Flat(figure.copy(marks = kept))
    val withClasses = figure.marks.foldLeft(start) {
      case (panel, points: Mark.Points) =>
        val infinite = points.at.map((_, y) => top.exists(y >= _))
        val segments = Mark.Segments(
          points.at.map((b, d) => ((b, b), (b, d))),
          points.paints,
          width = 2.5,
          roundCaps = true,
          tips = points.tips
        )
        val grows = points.at.indices.map { i =>
          val (b, d) = points.at(i)
          Reaction(
            Vector(
              Behavior.Shown(control, b, if infinite(i) then inf else d),
              Behavior.Follows(control, Coordinate.Y, Ends.Second, b, d)
            )
          )
        }
        val becomes = points.at.indices.filterNot(infinite)
        val afterwards = points.copy(
          at = becomes.map(points.at),
          paints = if points.paints.size == points.at.size then becomes.map(points.paints) else points.paints,
          tips = if points.tips.size == points.at.size then becomes.map(points.tips) else points.tips
        )
        val dies = becomes.map(i => Reaction(Vector(Behavior.shownFrom(control, points.at(i)._2))))
        val withSegments = panel.reactEach(segments, grows)
        if becomes.isEmpty then withSegments else withSegments.reactEach(afterwards, dies)
      case (panel, _) => panel
    }
    // essential classes' segments would otherwise stop at the `∞` line; with no `∞` line they run with the control
    withClasses.react(Mark.Rule.horizontal(0.0).copy(paint = Paint.Series(0), width = 1.5), Behavior.followsY(control))

  /** The barcode `figure` (from `Plot.barcode`) as the barcode of a sweep: each bar grows with `control` from its
    * birth, until the movable vertical line passes its death.
    */
  def sweepBarcode(figure: Figure, control: String = "t"): Flat =
    val start = Flat(figure)
    val withBars = figure.marks.zipWithIndex.foldLeft(start) {
      case (panel, (bars: Mark.Segments, index)) =>
        val reactions = bars.ends.map { case ((birth, _), (end, _)) =>
          Reaction(Vector(Behavior.shownFrom(control, birth), Behavior.followsX(control, birth, end, Ends.Second)))
        }
        panel.copy(reactions = panel.reactions.updated(index, reactions))
      case (panel, _) => panel
    }
    withBars.react(Mark.Rule.vertical(0.0).copy(paint = Paint.Series(0), width = 1.5), Behavior.followsX(control))

/** A plot with controls: a page whose panels respond to sliders, with no library to load. The panels are the plots of
  * the library (a [[Figure]] or a [[Scene]]) drawn as usual; what changes when a control moves is what the
  * [[Behavior]]s of their items say. Like every plot it is an immutable value, built by chaining:
  *
  * {{{
  * Plot.filtration(points) // a complex, its diagram, a slider for the scale
  *   .withPlay(10)
  *   .withTitle("Noisy circle")
  *
  * Interactive()
  *   .withSlider("t", 0, 1)
  *   .withPanel(Panel(figure).react(Mark.Points(...), Behavior.shownFrom("t", 0.3)))
  * }}}
  *
  * The page is one HTML file, and `Interactive.at` gives the still picture at any value, so the same plot makes the
  * frames of a video or the slides of a talk (`frames`, `saveFrames`).
  *
  * '''Extending it.''' `withScript(js)` runs your JavaScript on the page with `tda4j`, the page's controller:
  * `tda4j.get(name)`, `tda4j.set(name, value)`, `tda4j.onChange(name, f)`, and `tda4j.reactor(kind, f)` to define what
  * a [[Behavior.Custom]] of that `kind` does (`f(item, behavior, value)`, `item.el` being the SVG element). Every
  * change is also announced as a `tda4j:change` event on the page's root element, for anything else on the page.
  *
  * @param panels
  *   what is drawn, left to right and wrapping
  * @param controls
  *   the sliders, top to bottom
  * @param title
  *   the page's heading
  * @param scripts
  *   JavaScript to run on the page (see above)
  */
final case class Interactive(
  panels: Vector[Panel] = Vector.empty,
  controls: Vector[Control] = Vector.empty,
  title: String = "",
  scripts: Vector[String] = Vector.empty
) extends Plot:
  override def toString: String =
    Plot.describe("Interactive", title, s"${panels.size} panels, ${controls.size} controls")

  // ----- building

  /** This plot with `figure` as one more panel. */
  def withPanel(figure: Figure): Interactive = withPanel(Panel(figure))

  /** This plot with the 3-D `scene` as one more panel. */
  def withPanel(scene: Scene): Interactive = withPanel(Panel(scene))

  /** This plot with `panel` as one more panel. */
  def withPanel(panel: Panel): Interactive = copy(panels = panels :+ panel)

  /** This plot with one more control. */
  def withControl(control: Control): Interactive =
    require(
      !controls.exists(_.name == control.name),
      s"Interactive: there is already a control called ${control.name}"
    )
    copy(controls = controls :+ control)

  /** This plot with a slider `name` from `min` to `max`, starting at `min`. */
  def withSlider(name: String, min: Double, max: Double): Interactive = withControl(Control.slider(name, min, max))

  /** This plot with a slider `name` from `min` to `max`, starting at `value` and written `label` beside it. */
  def withSlider(name: String, min: Double, max: Double, value: Double, label: String): Interactive =
    withControl(Control.slider(name, min, max, value).withLabel(label))

  /** A play button on the first slider, sweeping to its maximum in `seconds`. */
  def withPlay(seconds: Double): Interactive =
    require(controls.nonEmpty, "Interactive.withPlay: add a slider first (withSlider)")
    withPlay(controls.head.name, seconds)

  /** A play button on the slider `name`, sweeping to its maximum in `seconds`. */
  def withPlay(name: String, seconds: Double): Interactive =
    require(controls.exists(_.name == name), s"Interactive.withPlay: no control called $name (${names})")
    copy(controls = controls.map(c => if c.name == name then c.withPlay(seconds) else c))

  /** JavaScript to run on the page; see the class's note on extending it. */
  def withScript(javascript: String): Interactive = copy(scripts = scripts :+ javascript)

  /** The heading of the page. */
  def withTitle(text: String): Interactive = copy(title = text)

  /** Every panel in the palette called `name`: `brand`, `reference`, `print`, `grayscale`, `okabe-ito` or `clear`. */
  def withPalette(name: String): Interactive = this + Palette.named(name)

  /** Every panel in the style called `name`: `default`, `slides`, `print` or `clear`. */
  def withStyle(name: String): Interactive = this + Style.named(name)

  /** `tweak` in every panel; a tweak a 3-D panel cannot have (an axis) leaves it as it is, and `Labels(title = ...)`
    * titles the page, not the panels.
    */
  def +(tweak: Tweak): Interactive = tweak.onInteractive(this)

  /** `figures` applied to the 2-D panels and `scenes` to the 3-D ones. */
  def mapPanels(figures: Figure => Figure, scenes: Scene => Scene): Interactive =
    copy(panels = panels.map {
      case p: Panel.Flat    => p.mapFigure(figures)
      case p: Panel.Spatial => p.mapScene(scenes)
    })

  /** A factor on every text size of every panel. */
  def withTextScale(factor: Double): Interactive = mapPanels(_.withTextScale(factor), _.withTextScale(factor))

  /** One change written as text, `element.property=value`, to every panel. See [[Settings]]. */
  def withSetting(spec: String): Interactive = this + Settings.parse(spec)

  private def names: String = controls.map(_.name).mkString(", ")

  // ----- checking

  /** Whether every behavior listens to a control of this plot, on a mark and axes it can be carried out on. */
  private def validate(): Unit =
    require(controls.nonEmpty, "Interactive: a plot with no control has nothing to move; add one with withSlider")
    require(panels.nonEmpty, "Interactive: add a panel with withPanel")
    val known = controls.map(_.name).toSet
    panels.zipWithIndex.foreach { (panel, p) =>
      panel.reactions.foreach { (m, rs) =>
        rs.foreach(_.behaviors.foreach { b =>
          require(
            known(b.control),
            s"Interactive: panel $p, part $m reacts to a control called ${b.control}, but the controls are: $names"
          )
          b match
            case Behavior.Custom(kind, _, args) =>
              require(
                kind.matches("[A-Za-z][A-Za-z0-9-]+"),
                s"Behavior.Custom: the kind \"$kind\" should be two or more letters, digits or -"
              )
              require(!Set("v", "f", "r")(kind), s"Behavior.Custom: \"$kind\" is a built-in behavior")
            case _ =>
        })
      }
      panel match
        case Panel.Flat(figure, rs) =>
          val (xt, yt) = Svg.resolved(figure)
          val moving = rs.values.exists(_.exists(_.behaviors.exists {
            case _: Behavior.Follows | _: Behavior.Radius => true
            case _                                        => false
          }))
          if moving then
            Seq(xt, yt).collectFirst { case t @ Transform.Custom(_, _, _) => t }.foreach { t =>
              throw IllegalArgumentException(
                s"Interactive: panel $p moves items with a control, but its axis uses $t, which the page cannot " +
                  "compute; use identity, log, sqrt or symlog"
              )
            }
          val discs = rs.exists((m, r) => r.exists(_.behaviors.exists(_.isInstanceOf[Behavior.Radius])))
          if discs && !(xt == Transform.Identity && yt == Transform.Identity) then
            throw IllegalArgumentException(
              s"Interactive: panel $p has balls that grow with a control (Behavior.Radius), which need linear axes " +
                "(a ball is no longer round on a curved axis)"
            )
        case _ =>
    }

  // ----- the page

  private def firstFigure: Option[Figure] = panels.collectFirst { case Panel.Flat(f, _) => f }

  private def look: Palette =
    firstFigure
      .map(_.palette)
      .orElse(panels.collectFirst { case Panel.Spatial(s, _) => s.palette })
      .getOrElse(Palette.default)

  private def attrsFor(reactions: Map[Int, IndexedSeq[Reaction]])(mark: Int, item: Int): String =
    reactions.get(mark) match
      case None     => ""
      case Some(rs) =>
        val r = rs(if rs.size == 1 then 0 else item)
        if r.isEmpty then "" else s"""data-ia="${Svg.escape(Interactive.encode(r))}""""

  // A panel is as wide as its picture at most and as the room allows at least 320 pixels: a page in a slide or an
  // iframe narrower than its pictures side by side shrinks them (the `viewBox` keeps the drawing), then stacks them.
  private def panelBox(width: Int, picture: String): String =
    s"""<div class="tda4j-panel" style="max-width:${width}px">$picture</div>"""

  def fragment(theme: Theme = Theme.Auto): String =
    validate()
    val id = Interactive.freshId()
    val pal = look
    val parts = panels.zipWithIndex.map {
      case (Panel.Flat(figure, rs), k) =>
        val moving = rs.values.exists(_.exists(_.behaviors.exists {
          case _: Behavior.Follows | _: Behavior.Radius => true
          case _                                        => false
        }))
        panelBox(figure.width, Svg.renderTagged(figure, theme, false, Svg.Tags(attrsFor(rs), moving, Some(s"$id-p$k"))))
      case (Panel.Spatial(scene, rs), k) => panelBox(scene.width, scene.fragmentReacting(theme, rs, s"$id-p$k"))
    }
    val headline =
      if title.isEmpty then ""
      else
        s"""<div class="tda4j-title" style="font-size:18px;font-weight:600;margin:4px 0 8px;color:var(--ink)">${Svg
            .escape(title)}</div>"""
    val ui = controls.map(Interactive.controlHtml(id, _)).mkString
    val spec = controls.map(Interactive.controlJson).mkString("[", ",", "]")
    val mine = scripts
      .map(js => s"try{(function(tda4j,root){$js\n})(api,root);}catch(e){console.error('tda4j script:',e);}")
      .mkString("\n")
    val program = Interactive.runtime.replace("__ID__", id).replace("__CONTROLS__", spec).replace("__SCRIPTS__", mine)
    s"""<div id="$id" class="tda4j-ia">
       |<style>${Interactive.styles(id, pal, theme)}</style>
       |$headline
       |<div class="tda4j-panels">${parts.mkString}</div>
       |<div class="tda4j-controls">$ui</div>
       |<script>$program</script>
       |</div>""".stripMargin

  def html(theme: Theme = Theme.Auto): String = Html.page(title, fragment(theme), look)

  /** The plot at its starting values, as one still picture. */
  def svgFile(theme: Theme = Theme.Light): String = at(Map.empty[String, Double]).svgFile(theme)

  // ----- stills

  /** The starting value of every control, with `values` in place of the ones it names. */
  private def valuesWith(values: Map[String, Double]): Map[String, Double] =
    values.foreach { (name, v) =>
      val c = controls
        .find(_.name == name)
        .getOrElse(throw IllegalArgumentException(s"Interactive.at: no control called $name (${names})"))
      require(v.isFinite, s"Interactive.at: the value of $name should be a number, not $v")
    }
    controls.map(c => c.name -> values.getOrElse(c.name, c.value)).toMap

  /** The still picture with the controls at `values` (the others at their starting values): the same items, in the same
    * places, as the page shows at those values.
    */
  def at(values: Map[String, Double]): Snapshot =
    validate()
    val now = valuesWith(values)
    val shown = panels.map {
      case Panel.Flat(figure, rs)   => Interactive.still(figure, rs, now)
      case Panel.Spatial(scene, rs) => Interactive.still(scene, rs, now)
    }
    val readout = controls.map(c => s"${c.shownName} = ${Svg.number(now(c.name))}").mkString("   ")
    Snapshot(shown, title, if title.isEmpty then readout else s"$title: $readout", now)

  /** The still picture with the control `name` at `value`. */
  def at(name: String, value: Double): Snapshot = at(Map(name -> value))

  /** The still picture with the first control at `value`. */
  def at(value: Double): Snapshot =
    require(controls.nonEmpty, "Interactive.at: this plot has no control")
    at(Map(controls.head.name -> value))

  /** `count` still pictures with the first control at even steps from its minimum to its maximum, for a video or a
    * slide for each.
    */
  def frames(count: Int): Seq[Snapshot] =
    require(count >= 2, s"Interactive.frames: ask for at least 2 frames, not $count")
    require(controls.nonEmpty, "Interactive.frames: this plot has no control")
    val c = controls.head
    (0 until count).map(i => at(c.name, c.min + (c.max - c.min) * i / (count - 1)))

  /** A still picture for each of `values` of the first control. */
  def frames(values: Seq[Double]): Seq[Snapshot] = values.map(at)

  /** A still picture at every stop of the first control: one for each change of the picture. */
  def framesAtStops(): Seq[Snapshot] =
    require(
      controls.nonEmpty && controls.head.stops.nonEmpty,
      "Interactive.framesAtStops: the first control has no stops"
    )
    frames(controls.head.stops)

  /** Writes `count` frames (see [[frames]]) as `frame-0000.svg`, `frame-0001.svg`, ... in `directory`, in `theme`, and
    * returns their paths. Files carry no background unless the style asks for one (`withStyle("print")`).
    */
  def saveFrames(directory: String, count: Int, theme: Theme = Theme.Light): Seq[String] =
    frames(count).zipWithIndex.map { (frame, i) =>
      frame.save(f"$directory/frame-$i%04d.svg", Some(theme))
    }

  /** [[saveFrames]] in the theme called `theme` (`auto`, `light` or `dark`), the paths as an array: for callers with a
    * word and no `Seq` (MATLAB, Python).
    */
  def saveFrames(directory: String, count: Int, theme: String): Array[String] =
    saveFrames(directory, count, Theme.named(theme)).toArray

  /** [[saveFrames]] in the light theme, for callers that cannot use a default argument (Java, MATLAB). */
  def saveFrames(directory: String, count: Int): Array[String] = saveFrames(directory, count, Theme.Light).toArray

/** The still picture of an [[Interactive]] at some values of its controls: its panels side by side under a heading that
  * says the values. A plot like any other (`save`, `view`, `svgFile`).
  */
final case class Snapshot(
  parts: Vector[Plot],
  title: String = "",
  heading: String = "",
  values: Map[String, Double] = Map.empty
) extends Plot:
  override def toString: String = Plot.describe("Snapshot", title, s"${parts.size} panels")

  private def sizes: Vector[(Int, Int)] = parts.map(Interactive.sizeOf)
  private def totalWidth: Int = sizes.map(_._1).sum + 12 * math.max(0, parts.size - 1)
  private def tallest: Int = sizes.map(_._2).maxOption.getOrElse(0)
  private def firstFigure: Option[Figure] = parts.collectFirst { case f: Figure => f }

  private def headingAbove(theme: Theme, saved: Boolean): Option[(String, Int)] =
    firstFigure.filter(_ => heading.nonEmpty).map(Svg.heading(_, heading, totalWidth, theme, saved))

  def fragment(theme: Theme = Theme.Auto): String =
    val row =
      parts.map(_.fragment(theme)).mkString("""<div style="display:flex;flex-wrap:wrap;gap:12px">""", "", "</div>")
    headingAbove(theme, saved = false).fold(row)((svg, _) => s"<div>$svg$row</div>")

  def html(theme: Theme = Theme.Auto): String =
    Html.page(title, fragment(theme), firstFigure.map(_.palette).getOrElse(Palette.default))

  def svgFile(theme: Theme = Theme.Light): String =
    val head = headingAbove(theme, saved = true)
    val offset = head.fold(0)(_._2)
    val xs = sizes.scanLeft(0)((x, s) => x + s._1 + 12)
    val inner = parts.zip(xs).map { (p, x) =>
      s"""<g transform="translate($x,$offset)">${Interactive.bare(p.svgFile(theme))}</g>"""
    }
    val (w, h) = (totalWidth, offset + tallest)
    val pal = firstFigure.map(_.palette).getOrElse(Palette.default)
    val background =
      if firstFigure.forall(!_.style.hasBackground(saved = true)) then ""
      else s"""<rect width="$w" height="$h" style="fill:${pal.hex(Paint.Surface, theme)}"/>"""
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">$background${head.fold("")(
        h => s"""<g>${h._1}</g>"""
      )}${inner.mkString}</svg>""".stripMargin

object Interactive:
  // Element ids of a page: a page is meant to be pasted into a document (a reveal.js deck, a blog post) next to pages
  // that other runs wrote, and the script finds its root by id, so a number per run is not enough (two runs' first
  // pages would both be `1`). The nonce is drawn once per run and the number counts the pages of that run; every
  // picture inside a page takes its id from the page's, so nothing in a page can collide with another page's.
  private[plot] val nonce: String = java.lang.Integer.toString(scala.util.Random.nextInt(Int.MaxValue), 36)
  private val counter = new AtomicLong()
  private[plot] def freshId(): String = s"tda4jia-$nonce-${counter.incrementAndGet()}"

  /** `svg` without the XML declaration of a file, to nest it in another picture. */
  private[plot] def bare(svg: String): String = svg.replaceFirst("""^<\?xml[^>]*\?>\s*""", "")

  /** The page's own size for a panel, for laying stills side by side. */
  private[plot] def sizeOf(p: Plot): (Int, Int) = p match
    case f: Figure => (f.width, f.height)
    case s: Scene  => (s.width, s.height)
    case _         => (560, 520)

  // ----- items

  /** How many items a mark has, for the marks the page can find (0 for the rest). */
  private[plot] def itemCount(m: Mark): Int = m match
    case m: Mark.Points   => m.at.size
    case m: Mark.Segments => m.ends.size
    case m: Mark.Polygons => m.corners.size
    case m: Mark.Discs    => m.centers.size
    case m: Mark.Boxes    => m.boxes.size
    case _: Mark.Line     => 1
    case _: Mark.Rule     => 1
    case _                => 0

  /** How many items a layer has. */
  private[plot] def itemCount(l: Layer): Int = l match
    case l: Layer.Points    => l.at.size
    case l: Layer.Segments  => l.ends.size
    case l: Layer.Triangles => l.corners.size

  /** Which behaviors a mark can carry out. */
  private[plot] def fitsMark(m: Mark)(b: Behavior): Boolean = (m, b) match
    case (_, _: Behavior.Shown | _: Behavior.Custom) => true
    case (_: Mark.Segments, f: Behavior.Follows)     => true
    case (r: Mark.Rule, f: Behavior.Follows)         => (f.coordinate == Coordinate.X) == r.vertical
    case (_: Mark.Discs, _: Behavior.Radius)         => true
    case _                                           => false

  // ----- what the page is told

  private def escapeField(s: String): String =
    s.flatMap {
      case '%' => "%25"
      case ',' => "%2C"
      case ';' => "%3B"
      case c   => c.toString
    }

  private def num(v: Double): String = v.toString

  /** The attribute value of a reaction: behaviors separated by `;`, each `kind,control,arguments...`. */
  private[plot] def encode(r: Reaction): String =
    r.behaviors
      .map { b =>
        val fields = b match
          case Behavior.Shown(c, from, until)                => Seq("v", c, num(from), num(until))
          case Behavior.Follows(c, coordinate, ends, lo, hi) =>
            Seq(
              "f",
              c,
              if coordinate == Coordinate.X then "x" else "y",
              ends match
                case Ends.First  => "1"
                case Ends.Second => "2"
                case Ends.Both   => "b"
              ,
              num(lo),
              num(hi)
            )
          case Behavior.Radius(c, factor)     => Seq("r", c, num(factor))
          case Behavior.Custom(kind, c, args) => Seq(kind, c) ++ args
        fields.map(escapeField).mkString(",")
      }
      .mkString(";")

  private def jsString(s: String): String =
    "\"" + s.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '<'  => "\\u003c"
      case c    => c.toString
    } + "\""

  private[plot] def controlJson(c: Control): String =
    val step = c.step.getOrElse((c.max - c.min) / 1000)
    val stops = if c.stops.isEmpty then "null" else c.stops.map(num).mkString("[", ",", "]")
    s"""{"n":${jsString(c.name)},"l":${jsString(c.shownName)},"min":${num(c.min)},"max":${num(c.max)},"v":${num(
        c.value
      )},"step":${num(step)},"stops":$stops,"play":${c.play.fold("0")(num)}}"""

  private[plot] def controlHtml(id: String, c: Control): String =
    val label = Svg.escape(c.shownName)
    val play = c.play.fold("")(_ =>
      s"""<button type="button" class="tda4j-play" aria-label="play ${label}" title="play">&#9654;</button>"""
    )
    val range =
      if c.stops.isEmpty then
        s"""min="${num(c.min)}" max="${num(c.max)}" step="${num((c.max - c.min) / 1000)}" value="${num(c.value)}""""
      else s"""min="0" max="${c.stops.size - 1}" step="1" value="${c.stops.indexOf(c.value)}""""
    s"""<div class="tda4j-control" data-control="${Svg.escape(
        c.name
      )}">$play<label for="$id-${c.name}">$label</label><input id="$id-${c.name}" type="range" $range aria-label="$label"><output for="$id-${c.name}"></output></div>"""

  private[plot] def styles(id: String, pal: Palette, theme: Theme): String =
    val variables =
      if theme == Theme.Auto then pal.cssVariables(s"#$id")
      else
        s"#$id{--ink:${pal.hex(Paint.Ink, theme)};--ink2:${pal.hex(Paint.Secondary, theme)};--s0:${pal.hex(Paint.Series(0), theme)};--surface:${pal.hex(Paint.Surface, theme)}}"
    s"""$variables
       |#$id{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;color:var(--ink)}
       |#$id .tda4j-panels{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start}
       |#$id .tda4j-panel{flex:1 1 320px;min-width:0}
       |#$id .tda4j-panel>svg{display:block;width:100%;height:auto}
       |#$id .tda4j-control{display:flex;align-items:center;gap:10px;margin:10px 0 0;font-size:15px}
       |#$id .tda4j-control label{min-width:2.5em}
       |#$id .tda4j-control input[type=range]{flex:1;min-width:120px;accent-color:var(--s0)}
       |#$id .tda4j-control output{min-width:5.5em;text-align:right;font-variant-numeric:tabular-nums;color:var(--ink2)}
       |#$id .tda4j-play{font-size:14px;line-height:1;padding:6px 10px;border:1px solid var(--ink2);border-radius:4px;background:transparent;color:var(--ink);cursor:pointer}""".stripMargin

  // ----- stills: the same behaviors, applied in Scala

  private def inside(shown: Behavior, values: Map[String, Double]): Boolean = shown match
    case Behavior.Shown(c, from, until) => values.get(c).forall(v => from <= v && v < until)
    case _                              => true

  private def clamp(v: Double, lo: Double, hi: Double): Double = math.min(math.max(v, lo), hi)

  private def reactionOf(rs: IndexedSeq[Reaction], i: Int): Reaction = rs(if rs.size == 1 then 0 else i)

  // the items of `xs` that stay
  private def keepItems[A](xs: IndexedSeq[A], keep: IndexedSeq[Boolean]): IndexedSeq[A] =
    xs.zip(keep).collect { case (x, true) => x }

  // the same for what a mark holds for each item (tips), when it holds one for each of the `n` and not one for all
  private def keepOf[A](xs: IndexedSeq[A], keep: IndexedSeq[Boolean], n: Int): IndexedSeq[A] =
    if xs.size == n then keepItems(xs, keep) else xs

  // the paints: as `keepOf`, but a mark always keeps a paint
  private def keepPaints(xs: IndexedSeq[Paint], keep: IndexedSeq[Boolean], n: Int): IndexedSeq[Paint] =
    val kept = keepOf(xs, keep, n)
    if kept.isEmpty then xs.take(1) else kept

  /** `figure` as it is with the controls at `values`: the items that are not shown dropped, the ones that follow a
    * control moved, the discs resized.
    */
  private[plot] def still(
    figure: Figure,
    reactions: Map[Int, IndexedSeq[Reaction]],
    values: Map[String, Double]
  ): Figure =
    val marks = figure.marks.zipWithIndex.flatMap { (mark, index) =>
      reactions.get(index).fold(Option(mark))(rs => stillMark(mark, rs, values))
    }
    // the page keeps the range of the whole figure while items come and go: so does a still
    val (rangeX, rangeY) = Svg.ranges(figure)
    val (zeroX, zeroY) = Svg.atMinusInfinity(figure)
    def pinned(a: Axis, range: (Double, Double), zero: Boolean): Axis =
      a.copy(
        min = a.min.orElse(Some(range._1)),
        max = a.max.orElse(Some(range._2)),
        minusInfinity = if zero && a.minusInfinity.isEmpty then Some(true) else a.minusInfinity
      )
    figure.copy(marks = marks, x = pinned(figure.x, rangeX, zeroX), y = pinned(figure.y, rangeY, zeroY))

  private def stillMark(mark: Mark, rs: IndexedSeq[Reaction], values: Map[String, Double]): Option[Mark] =
    val n = itemCount(mark)
    val per = (0 until n).map(reactionOf(rs, _))
    val keep = per.map(_.behaviors.forall(inside(_, values)))
    def follow(i: Int, end: ((Double, Double), (Double, Double))): ((Double, Double), (Double, Double)) =
      per(i).behaviors.foldLeft(end) {
        case (((ax, ay), (bx, by)), Behavior.Follows(c, coordinate, ends, lo, hi)) =>
          values.get(c).fold(((ax, ay), (bx, by))) { v =>
            val x = clamp(v, lo, hi)
            val first = ends != Ends.Second
            val second = ends != Ends.First
            coordinate match
              case Coordinate.X => ((if first then x else ax, ay), (if second then x else bx, by))
              case Coordinate.Y => ((ax, if first then x else ay), (bx, if second then x else by))
          }
        case (end, _) => end
      }
    mark match
      case m: Mark.Points =>
        Some(m.copy(at = keepItems(m.at, keep), paints = keepPaints(m.paints, keep, n), tips = keepOf(m.tips, keep, n)))
      case m: Mark.Segments =>
        val moved = m.ends.indices.map(i => follow(i, m.ends(i)))
        Some(
          m.copy(ends = keepItems(moved, keep), paints = keepPaints(m.paints, keep, n), tips = keepOf(m.tips, keep, n))
        )
      case m: Mark.Polygons =>
        Some(
          m.copy(
            corners = keepItems(m.corners, keep),
            paints = keepPaints(m.paints, keep, n),
            tips = keepOf(m.tips, keep, n)
          )
        )
      case m: Mark.Discs =>
        val radius = per.headOption
          .flatMap(_.behaviors.collectFirst { case Behavior.Radius(c, factor) => values.get(c).map(_ * factor) })
          .flatten
          .getOrElse(m.radius)
        Some(
          m.copy(
            centers = keepItems(m.centers, keep),
            radius = radius,
            paints = keepPaints(m.paints, keep, n),
            tips = keepOf(m.tips, keep, n)
          )
        )
      case m: Mark.Boxes =>
        Some(
          m.copy(
            boxes = keepItems(m.boxes, keep),
            paints = keepPaints(m.paints, keep, n),
            tips = keepOf(m.tips, keep, n)
          )
        )
      case m: Mark.Rule =>
        if !keep.head then None
        else
          val at = per.head.behaviors.foldLeft(m.at) {
            case (current, Behavior.Follows(c, _, _, lo, hi)) => values.get(c).fold(current)(clamp(_, lo, hi))
            case (current, _)                                 => current
          }
          Some(m.copy(at = at))
      case m: Mark.Line => Option.when(keep.head)(m)
      case other        => Some(other)

  /** `scene` with the controls at `values`: the items that are not shown dropped. */
  private[plot] def still(scene: Scene, reactions: Map[Int, IndexedSeq[Reaction]], values: Map[String, Double]): Scene =
    val layers = scene.layers.zipWithIndex.map { (layer, index) =>
      reactions.get(index).fold(layer) { rs =>
        val n = itemCount(layer)
        val keep = (0 until n).map(i => reactionOf(rs, i).behaviors.forall(inside(_, values)))
        layer match
          case l: Layer.Points    => l.copy(at = keepItems(l.at, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Segments  => l.copy(ends = keepItems(l.ends, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Triangles =>
            l.copy(corners = keepItems(l.corners, keep), paints = keepPaints(l.paints, keep, n))
      }
    }
    scene.copy(layers = layers)

  // ----- the page's controller

  /** The controller of an interactive page; the placeholders are filled in by `Interactive.fragment`. */
  private[plot] val runtime: String =
    """(function(){
      |const root=document.getElementById('__ID__'),spec=__CONTROLS__;
      |const state={},ctl={},watch={},reactors={},byControl={},items=[];
      |const fmt=v=>String(Number(v.toPrecision(3)));
      |const tr={i:v=>v,l:v=>Math.log10(v),s:v=>Math.sign(v)*Math.sqrt(Math.abs(v)),m:(v,c)=>Math.sign(v)*Math.log10(1+Math.abs(v)/c)};
      |function frameOf(svg){
      |  if(svg._fr)return svg._fr;const f=svg.getAttribute('data-fr');if(!f)return null;
      |  const ax=f.split(';').map(s=>{const a=s.split(',');return{o:+a[0],n:+a[1],lo:+a[2],hi:+a[3],rev:a[4]==='1',t:a[5],c:+a[6]};});
      |  const pos=(a,v)=>{const u=tr[a.t](v,a.c);return(a.rev?a.hi-u:u-a.lo)/(a.hi-a.lo)*a.n;};
      |  return svg._fr={px:v=>ax[0].o+pos(ax[0],v),py:v=>ax[1].o+ax[1].n-pos(ax[1],v),scale:ax[0].n/(ax[0].hi-ax[0].lo)};}
      |reactors.v=(it,b,v,j)=>{it.h[j]=!(v>=+b.a[0]&&v<+b.a[1]);it.el.style.display=Object.values(it.h).some(x=>x)?'none':'';};
      |reactors.f=(it,b,v)=>{const fr=frameOf(it.svg);if(!fr)return;
      |  const x=Math.min(Math.max(v,+b.a[2]),+b.a[3]),e=b.a[1],el=it.el;
      |  if(b.a[0]==='x'){const p=fr.px(x);if(e!=='2')el.setAttribute('x1',p);if(e!=='1')el.setAttribute('x2',p);}
      |  else{const p=fr.py(x);if(e!=='2')el.setAttribute('y1',p);if(e!=='1')el.setAttribute('y2',p);}};
      |reactors.r=(it,b,v)=>{const fr=frameOf(it.svg);if(fr)it.el.setAttribute('r',+b.a[0]*v*fr.scale);};
      |root.querySelectorAll('[data-ia]').forEach(el=>{
      |  const bs=el.getAttribute('data-ia').split(';').map(s=>{const a=s.split(',').map(decodeURIComponent);return{k:a[0],c:a[1],a:a.slice(2)};});
      |  const it={el,bs,svg:el.ownerSVGElement,h:{}};items.push(it);
      |  new Set(bs.map(b=>b.c)).forEach(c=>(byControl[c]=byControl[c]||[]).push(it));});
      |function apply(name){const v=state[name];
      |  for(const it of byControl[name]||[])it.bs.forEach((b,j)=>{if(b.c===name&&reactors[b.k])reactors[b.k](it,b,v,j);});
      |  (watch[name]||[]).forEach(f=>f(v));
      |  root.dispatchEvent(new CustomEvent('tda4j:change',{detail:{name:name,value:v},bubbles:true}));}
      |const nearest=(s,v)=>{let lo=0,hi=s.length-1;while(lo<hi){const m=(lo+hi)>>1;if(s[m]<v)lo=m+1;else hi=m;}
      |  return lo>0&&Math.abs(s[lo-1]-v)<=Math.abs(s[lo]-v)?lo-1:lo;};
      |function put(c,v,fromInput){v=Math.min(Math.max(+v,c.min),c.max);state[c.n]=v;
      |  if(!fromInput)c.input.value=c.stops?nearest(c.stops,v):v;
      |  c.out.textContent=fmt(v);apply(c.n);}
      |function playing(c){
      |  if(c.raf){cancelAnimationFrame(c.raf);c.raf=0;c.btn.innerHTML='&#9654;';return;}
      |  if(state[c.n]>=c.max)put(c,c.min);
      |  const from=state[c.n],t0=performance.now(),dur=Math.max(1,c.play*1000*(c.max-from)/(c.max-c.min));
      |  c.btn.innerHTML='&#10074;&#10074;';
      |  const step=t=>{const f=Math.min(1,(t-t0)/dur);put(c,from+(c.max-from)*f);
      |    if(f<1)c.raf=requestAnimationFrame(step);else{c.raf=0;c.btn.innerHTML='&#9654;';}};
      |  c.raf=requestAnimationFrame(step);}
      |const api={root:root,controls:spec,
      |  get:n=>state[n],
      |  set:(n,v)=>put(ctl[n],v),
      |  onChange:(n,f)=>{(watch[n]=watch[n]||[]).push(f);f(state[n]);},
      |  reactor:(k,f)=>{reactors[k]=f;for(const n in ctl)apply(n);},
      |  items:items};
      |root.tda4j=api;
      |spec.forEach(c=>{ctl[c.n]=c;
      |  const box=root.querySelector('.tda4j-control[data-control="'+c.n+'"]');
      |  c.input=box.querySelector('input');c.out=box.querySelector('output');c.btn=box.querySelector('.tda4j-play');
      |  c.input.addEventListener('input',()=>put(c,c.stops?c.stops[+c.input.value]:+c.input.value,true));
      |  if(c.btn)c.btn.addEventListener('click',()=>playing(c));
      |  put(c,c.v);});
      |__SCRIPTS__
      |})();""".stripMargin
