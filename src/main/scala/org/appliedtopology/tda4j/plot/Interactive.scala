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
  *   - [[Behavior.Selects]]: pointing at the item (or clicking it) chooses a key of a [[Selection]]. What the item
  *     looks like does not change; other items react.
  *   - [[Behavior.Selected]]: the item is there while a key is chosen in a [[Selection]]: a ring around a point, a
  *     cycle over the input. Together with [[Behavior.Selects]] on the item that stands for the same thing in another
  *     panel, this links the views.
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

  /** Pointing at the item chooses `key` in the selection named `control`; clicking pins it (see [[Selection]]). */
  case Selects(control: String, key: Int)

  /** The item is shown while `key` is chosen in the selection named `control`. */
  case Selected(control: String, key: Int)

  /** The control (or selection) this behavior listens to. */
  def control: String

  /** Whether this behavior listens to a [[Selection]] rather than a slider. */
  def listensToSelection: Boolean = this match
    case _: Selects | _: Selected => true
    case _                        => false

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

  /** Pointing at the item chooses `key` in the selection `selection`; a click pins it. */
  def selects(selection: String, key: Int): Behavior = Selects(selection, key)

  /** The item is shown while `key` is chosen in the selection `selection`. */
  def selected(selection: String, key: Int): Behavior = Selected(selection, key)

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
  final case class Flat(
    figure: Figure,
    reactions: Map[Int, IndexedSeq[Reaction]] = Map.empty,
    brush: Option[String] = None,
    zoom: Option[ZoomAxes] = None
  ) extends Panel:
    /** This panel zoomable: the viewer zooms in on a region and pans over the picture, and the axes follow (new ticks
      * and numbers for the range shown); the buttons beside the panel, or `Ctrl` and the mouse wheel, zoom, and
      * dragging pans (in a panel that brushes too, a button chooses what dragging does). Points and text keep their
      * size and lines their width; everything else moves with the data. `axes` says which axes the zoom changes.
      *
      * Linear, logarithmic, square-root, symmetric-log and reversed axes zoom, with their number formats, number style,
      * slanted labels and minor ticks; the page draws them as the plot does. What it cannot compute is refused, and the
      * message says what: a transform of your own, a log axis with a `-∞` line for what is at 0, a number format other
      * than `%f` and `%e` (with the flags `-`, `+`, `0` and `,`, a width and a precision). [[PageState.zoom]] gives the
      * still picture of a window.
      */
    def zoomable(axes: ZoomAxes = ZoomAxes.Both): Flat = copy(zoom = Some(axes))

    // zoomable when the page can draw its axes again, as it is otherwise
    private[plot] def zoomableIfPossible(axes: ZoomAxes): Flat =
      if Interactive.zoomProblem(figure).isEmpty then zoomable(axes) else this

    /** This panel with dragging a rectangle over it choosing, in the selection `selection`, the keys of every item of
      * this panel that the rectangle touches (a shift-drag adds to what is chosen): a region of a diagram, a stretch of
      * a barcode. Some item of the panel has to choose in that selection ([[selecting]]). The rectangle stays on the
      * page until the next click. Brushing is with the mouse or a pen: a finger scrolls the page.
      */
    def brushable(selection: String): Flat = copy(brush = Some(selection))

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

    /** The mark at `index` of the figure, linked to the selection `selection`: pointing at its item `i` chooses the key
      * `keys(i)` there. With `halo`, a translucent shape over each item is added to the figure and appears while the
      * item's key is chosen, whoever chose it (this panel's item, or an item of another panel with the same key): the
      * halo of a point, the thick outline of a bar, a triangle drawn darker. The halo moves and comes and goes with the
      * item it surrounds, so it can be added to a mark that is already reacting to a slider.
      *
      * {{{
      * Panel(diagram).selecting(1, "class", keys)
      * }}}
      */
    def selecting(index: Int, selection: String, keys: IndexedSeq[Int], halo: Boolean = true): Flat =
      require(
        figure.marks.indices.contains(index),
        s"Panel.selecting: the figure has ${figure.marks.size} marks, so there is no mark $index"
      )
      val mark = figure.marks(index)
      val items = Interactive.itemCount(mark)
      require(
        items > 0,
        s"Panel.selecting: a ${mark.getClass.getSimpleName.stripSuffix("$")} has no items the page can find"
      )
      require(
        keys.size == items,
        s"Panel.selecting: mark $index has $items items, so it takes $items keys (one for each), not ${keys.size}"
      )
      val before = (0 until items).map(i => reactions.get(index).fold(Reaction.none)(Interactive.reactionOf(_, i)))
      val chosen = before.indices.map(i => before(i) + Behavior.Selects(selection, keys(i)))
      val linked = copy(reactions = reactions.updated(index, chosen))
      Interactive.halo(mark).filter(_ => halo).fold(linked) { shape =>
        val lit = before.indices.map { i =>
          Reaction(before(i).behaviors.filterNot(_.isInstanceOf[Behavior.Selects])) + Behavior.Selected(
            selection,
            keys(i)
          )
        }
        linked.reactEach(shape, lit)
      }

  /** A 3-D panel. A canvas carries out [[Behavior.Shown]], [[Behavior.Selected]] and [[Behavior.Selects]] on any layer
    * and [[Behavior.Radius]] on balls ([[Layer.Balls]]); [[Behavior.Custom]] is yours.
    */
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
        Panel.check("3-D", s"a ${Interactive.nameOf(layer)} layer", r, Interactive.fitsLayer(layer))
      )
      copy(
        scene = scene.copy(layers = scene.layers :+ layer),
        reactions = this.reactions.updated(scene.layers.size, reactions)
      )

    /** The same with `change` applied to the scene. */
    def mapScene(change: Scene => Scene): Spatial = copy(scene = change(scene))

    /** The layer at `index` of the scene, linked to the selection `selection`: pointing at its item `i` chooses the key
      * `keys(i)` there, and a click pins it (a canvas is turned by dragging, so there is no brushing). With `halo`, a
      * translucent shape over each item (a ring around a point, a thicker line, a darker triangle) is added to the
      * scene and appears while the item's key is chosen, whoever chose it. See [[Panel.Flat.selecting]].
      */
    def selecting(index: Int, selection: String, keys: IndexedSeq[Int], halo: Boolean = true): Spatial =
      require(
        scene.layers.indices.contains(index),
        s"Panel.selecting: the scene has ${scene.layers.size} layers, so there is no layer $index"
      )
      val layer = scene.layers(index)
      val items = Interactive.itemCount(layer)
      require(
        keys.size == items,
        s"Panel.selecting: layer $index has $items items, so it takes $items keys (one for each), not ${keys.size}"
      )
      val before = (0 until items).map(i => reactions.get(index).fold(Reaction.none)(Interactive.reactionOf(_, i)))
      val chosen = before.indices.map(i => before(i) + Behavior.Selects(selection, keys(i)))
      val linked = copy(reactions = reactions.updated(index, chosen))
      Interactive.halo(layer).filter(_ => halo).fold(linked) { shape =>
        val lit = before.indices.map { i =>
          Reaction(before(i).behaviors.filterNot(_.isInstanceOf[Behavior.Selects])) + Behavior.Selected(
            selection,
            keys(i)
          )
        }
        linked.reactEach(shape, lit)
      }

  // ----- panels from the plots of persistent homology

  private val inf = Double.PositiveInfinity

  /** The persistence diagram `figure` (from `Plot.diagram`) as the diagram of a sweep: each class is a vertical segment
    * from the diagonal that grows with `control` until the line passes the class's death, and then a point. The movable
    * horizontal line is the control's value. Essential classes (on the line marked `∞`) never become points; their
    * segment grows with the line.
    *
    * With `link = Some((selection, keys))`, the classes of the `g`-th degree's mark of the figure (in the order of the
    * figure's `Points` marks) are linked to the selection called `selection`, `keys(g)(i)` being the key of the `i`-th
    * (see [[Flat.selecting]]): pointing at a class chooses its key, and it lights up while its key is chosen.
    */
  def sweepDiagram(
    figure: Figure,
    control: String = "t",
    link: Option[(String, Seq[IndexedSeq[Int]])] = None
  ): Flat =
    val top = figure.marks.collectFirst { case r: Mark.Rule if !r.vertical => r.at }
    val kept = figure.marks.filter {
      case _: Mark.Points => false
      case _              => true
    }
    val start = Flat(figure.copy(marks = kept))
    val (withClasses, _) = figure.marks.foldLeft((start, 0)) {
      case ((panel, group), points: Mark.Points) =>
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
        val linkedSegments = link.fold(withSegments)((name, keys) =>
          withSegments.selecting(withSegments.figure.marks.size - 1, name, keys(group))
        )
        val result =
          if becomes.isEmpty then linkedSegments
          else
            val withPoints = linkedSegments.reactEach(afterwards, dies)
            link.fold(withPoints)((name, keys) =>
              withPoints.selecting(withPoints.figure.marks.size - 1, name, becomes.map(keys(group)))
            )
        (result, group + 1)
      case ((panel, group), _) => (panel, group)
    }
    // essential classes' segments would otherwise stop at the `∞` line; with no `∞` line they run with the control
    withClasses.react(Mark.Rule.horizontal(0.0).copy(paint = Paint.Series(0), width = 1.5), Behavior.followsY(control))

  /** The barcode `figure` (from `Plot.barcode`) as the barcode of a sweep: each bar grows with `control` from its
    * birth, until the movable vertical line passes its death. `link` links the bars of the `g`-th `Segments` mark to a
    * selection, `keys(g)(i)` for the `i`-th, as in [[sweepDiagram]].
    */
  def sweepBarcode(
    figure: Figure,
    control: String = "t",
    link: Option[(String, Seq[IndexedSeq[Int]])] = None
  ): Flat =
    val start = Flat(figure)
    val (withBars, _) = figure.marks.zipWithIndex.foldLeft((start, 0)) {
      case ((panel, group), (bars: Mark.Segments, index)) =>
        val reactions = bars.ends.map { case ((birth, _), (end, _)) =>
          Reaction(Vector(Behavior.shownFrom(control, birth), Behavior.followsX(control, birth, end, Ends.Second)))
        }
        val moving = panel.copy(reactions = panel.reactions.updated(index, reactions))
        (link.fold(moving)((name, keys) => moving.selecting(index, name, keys(group))), group + 1)
      case ((panel, group), _) => (panel, group)
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
  scripts: Vector[String] = Vector.empty,
  selections: Vector[Selection] = Vector.empty
) extends Plot:
  override def toString: String =
    Plot.describe(
      "Interactive",
      title,
      s"${panels.size} panels, ${controls.size} controls" +
        (if selections.isEmpty then "" else s", ${selections.size} selections")
    )

  // ----- building

  /** This plot with `figure` as one more panel. */
  def withPanel(figure: Figure): Interactive = withPanel(Panel(figure))

  /** This plot with the 3-D `scene` as one more panel. */
  def withPanel(scene: Scene): Interactive = withPanel(Panel(scene))

  /** This plot with `panel` as one more panel. */
  def withPanel(panel: Panel): Interactive = copy(panels = panels :+ panel)

  /** This plot with every 2-D panel that the page can zoom made zoomable (see [[Panel.Flat.zoomable]]): the viewer
    * zooms in on a region with the buttons beside a panel or `Ctrl` and the mouse wheel, pans by dragging, and the axes
    * follow. A panel whose axes the page cannot draw again (a log axis, a number format) and a 3-D panel are left as
    * they are. With `axes`, the axes the zoom changes.
    */
  def zoomable(axes: ZoomAxes = ZoomAxes.Both): Interactive =
    copy(panels = panels.map {
      case flat: Panel.Flat => flat.zoomableIfPossible(axes)
      case other            => other
    })

  /** This plot with one more control. */
  def withControl(control: Control): Interactive =
    require(
      !controls.exists(_.name == control.name) && !selections.exists(_.name == control.name),
      s"Interactive: there is already a control or selection called ${control.name}"
    )
    copy(controls = controls :+ control)

  /** This plot with one more selection: a set of keys the viewer chooses by pointing at items (see [[Selection]]). */
  def withSelection(selection: Selection): Interactive =
    require(
      !controls.exists(_.name == selection.name) && !selections.exists(_.name == selection.name),
      s"Interactive: there is already a control or selection called ${selection.name}"
    )
    copy(selections = selections :+ selection)

  /** This plot with a selection called `name`. */
  def withSelection(name: String): Interactive = withSelection(Selection(name))

  /** This plot with a selection called `name`, listed under the panels with `labels(key)` for each chosen key. */
  def withSelection(name: String, labels: Seq[String]): Interactive = withSelection(Selection.listing(name, labels))

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

  private def names: String =
    (controls.map(_.name) ++ selections.map(s => s"${s.name} (a selection)")).mkString(", ")

  // ----- checking

  /** Whether every behavior listens to a control of this plot, on a mark and axes it can be carried out on. */
  private def validate(): Unit =
    require(
      controls.nonEmpty || selections.nonEmpty || panels.exists {
        case Panel.Flat(_, _, _, zoom) => zoom.isDefined; case _ => false
      },
      "Interactive: a plot with no control, selection or zoomable panel has nothing to respond to; add one with " +
        "withSlider, withSelection or Panel.Flat.zoomable"
    )
    require(panels.nonEmpty, "Interactive: add a panel with withPanel")
    val known = controls.map(_.name).toSet
    val chosen = selections.map(_.name).toSet
    panels.zipWithIndex.foreach { (panel, p) =>
      panel.reactions.foreach { (m, rs) =>
        rs.foreach(_.behaviors.foreach { b =>
          b match
            case _: Behavior.Selects | _: Behavior.Selected =>
              require(
                chosen(b.control),
                s"Interactive: panel $p, part $m reacts to a selection called ${b.control}, but the selections are: " +
                  (if selections.isEmpty then "none (add one with withSelection)"
                   else selections.map(_.name).mkString(", "))
              )
            case _: Behavior.Custom =>
              require(
                known(b.control) || chosen(b.control),
                s"Interactive: panel $p, part $m reacts to ${b.control}, but the controls and selections are: $names"
              )
            case _ =>
              require(
                known(b.control),
                s"Interactive: panel $p, part $m reacts to a control called ${b.control}, but the controls are: " +
                  (if controls.isEmpty then "none (add one with withSlider)" else controls.map(_.name).mkString(", "))
              )
          b match
            case Behavior.Custom(kind, _, args) =>
              require(
                kind.matches("[A-Za-z][A-Za-z0-9-]+"),
                s"Behavior.Custom: the kind \"$kind\" should be two or more letters, digits or -"
              )
              require(!Set("v", "f", "r", "s", "w")(kind), s"Behavior.Custom: \"$kind\" is a built-in behavior")
            case _ =>
        })
      }
      panel match
        case Panel.Flat(figure, rs, brush, zoom) =>
          zoom.foreach { _ =>
            Interactive
              .zoomProblem(figure)
              .foreach(why => require(false, s"Interactive: panel $p is zoomable, but $why"))
          }
          brush.foreach { b =>
            require(
              chosen(b),
              s"Interactive: panel $p is brushable on a selection called $b, but the selections are: " +
                (if selections.isEmpty then "none (add one with withSelection)"
                 else selections.map(_.name).mkString(", "))
            )
            require(
              rs.values.exists(_.exists(_.behaviors.exists {
                case Behavior.Selects(c, _) => c == b
                case _                      => false
              })),
              s"Interactive: panel $p is brushable on $b, but none of its items chooses in $b " +
                "(Panel.selecting links a mark's items to a selection)"
            )
          }
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

  private def firstFigure: Option[Figure] = panels.collectFirst { case Panel.Flat(f, _, _, _) => f }

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
        if r.isEmpty then ""
        else
          // what shows only while a key is chosen is not there on a page without script, either
          val hidden = if r.behaviors.exists(_.isInstanceOf[Behavior.Selected]) then " display=\"none\"" else ""
          s"""data-ia="${Svg.escape(Interactive.encode(r))}"$hidden"""

  // A panel is as wide as its picture at most and as the room allows at least 320 pixels: a page in a slide or an
  // iframe narrower than its pictures side by side shrinks them (the `viewBox` keeps the drawing), then stacks them.
  private def panelBox(
    width: Int,
    picture: String,
    brush: Option[String] = None,
    zoom: Option[String] = None
  ): String =
    val brushing = brush.fold("")(b => s""" data-brush="${Svg.escape(b)}"""")
    val zooming = zoom.fold("")(z => s""" data-zoomable="$z"""")
    s"""<div class="tda4j-panel"$brushing$zooming style="max-width:${width}px">$picture</div>"""

  def fragment(theme: Theme = Theme.Auto): String =
    validate()
    val id = Interactive.freshId()
    val pal = look
    val parts = panels.zipWithIndex.map {
      case (Panel.Flat(figure, rs, brush, zoom), k) =>
        val moving = rs.values.exists(_.exists(_.behaviors.exists {
          case _: Behavior.Follows | _: Behavior.Radius => true
          case _                                        => false
        }))
        panelBox(
          figure.width,
          Svg.renderTagged(figure, theme, false, Svg.Tags(attrsFor(rs), moving, Some(s"$id-p$k"), zoom.map(_.code))),
          brush,
          zoom.map(_.code)
        )
      case (Panel.Spatial(scene, rs), k) => panelBox(scene.width, scene.fragmentReacting(theme, rs, s"$id-p$k"))
    }
    val headline =
      if title.isEmpty then ""
      else
        s"""<div class="tda4j-title" style="font-size:18px;font-weight:600;margin:4px 0 8px;color:var(--ink)">${Svg
            .escape(title)}</div>"""
    val ui = controls.map(Interactive.controlHtml(id, _)).mkString + selections.map(Interactive.selectionHtml).mkString
    val spec = controls.map(Interactive.controlJson).mkString("[", ",", "]")
    val choices = selections.map(Interactive.selectionJson).mkString("[", ",", "]")
    val mine = scripts
      .map(js => s"try{(function(tda4j,root){$js\n})(api,root);}catch(e){console.error('tda4j script:',e);}")
      .mkString("\n")
    val program = Interactive.runtime
      .replace("__ID__", id)
      .replace("__CONTROLS__", spec)
      .replace("__SELECTIONS__", choices)
      .replace("__SCRIPTS__", mine)
    s"""<div id="$id" class="tda4j-ia">
       |<style>${Interactive.styles(
        id,
        pal,
        theme,
        selections.exists(_.listed),
        panels.exists { case Panel.Flat(_, _, brush, _) => brush.isDefined; case _ => false },
        panels.exists { case Panel.Flat(_, _, _, zoom) => zoom.isDefined; case _ => false }
      )}</style>
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
  def at(values: Map[String, Double]): Snapshot = at(PageState(values))

  /** The still picture with the sliders and selections as `state` has them: what the page shows after the viewer has
    * done the same. Sliders it leaves out are at their starting values, selections at nothing chosen.
    */
  def at(state: PageState): Snapshot =
    validate()
    val now = valuesWith(state.values)
    state.selected.foreach { (name, keys) =>
      require(
        selections.exists(_.name == name),
        s"Interactive.at: no selection called $name (${
            if selections.isEmpty then "this plot has none" else selections.map(_.name).mkString(", ")
          })"
      )
    }
    val chosen = selections.map(s => s.name -> state.chosen(s.name)).toMap
    state.windows.foreach { (k, w) =>
      val zoomable = panels.zipWithIndex.collect { case (Panel.Flat(_, _, _, Some(_)), i) => i }
      require(
        zoomable.contains(k),
        s"Interactive.at: panel $k is not a zoomable panel (zoomable panels: " +
          (if zoomable.isEmpty then "none" else zoomable.mkString(", ")) + ")"
      )
      panels(k) match
        case Panel.Flat(figure, _, _, _) =>
          val (xt, yt) = Svg.resolved(figure)
          Seq("x" -> (xt, w.x0, w.x1), "y" -> (yt, w.y0, w.y1)).foreach { case (axis, (t, lo, hi)) =>
            require(
              t(lo).isFinite && t(hi).isFinite,
              s"Interactive.at: the window of panel $k runs from $lo to $hi along its $axis axis, which a ${t.label} " +
                "axis cannot show (positive numbers only, on a log axis)"
            )
          }
        case _ =>
    }
    state.brushes.foreach { (k, w) =>
      val brushable = panels.zipWithIndex.collect { case (Panel.Flat(_, _, Some(_), _), i) => i }
      require(
        brushable.contains(k),
        s"Interactive.at: panel $k is not a brushable panel (brushable panels: " +
          (if brushable.isEmpty then "none" else brushable.mkString(", ")) + ")"
      )
      val (xt, yt) = panels(k) match
        case Panel.Flat(figure, _, _, _) => Svg.resolved(figure)
        case _                           => (Transform.Identity, Transform.Identity)
      require(
        Seq(xt(w.x0), xt(w.x1), yt(w.y0), yt(w.y1)).forall(_.isFinite),
        s"Interactive.at: the brush of panel $k runs over $w, which an axis cannot show (positive numbers only, on a log axis)"
      )
    }
    val shown = panels.zipWithIndex.map {
      case (Panel.Flat(figure, rs, _, _), k) =>
        Interactive.still(figure, rs, now, chosen, state.windows.get(k), state.brushes.get(k))
      case (Panel.Spatial(scene, rs), _) => Interactive.still(scene, rs, now, chosen)
    }
    val sliders = controls.map(c => s"${c.shownName} = ${Svg.number(now(c.name))}")
    val picked = selections.flatMap { s =>
      val keys = chosen(s.name).toSeq.sorted
      Option.when(keys.nonEmpty) {
        val named = keys.take(4).map(s.labelOf).mkString(" | ")
        s"${s.shownName} = $named${if keys.size > 4 then s" and ${keys.size - 4} more" else ""}"
      }
    }
    val readout = (sliders ++ picked).mkString("   ")
    Snapshot(shown, title, if title.isEmpty then readout else s"$title: $readout", now, chosen.filter(_._2.nonEmpty))

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
  values: Map[String, Double] = Map.empty,
  selected: Map[String, Set[Int]] = Map.empty
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
    case l: Layer.Balls     => l.centers.size
    case l: Layer.Halos     => l.at.size

  private[plot] def nameOf(l: Layer): String = l match
    case _: Layer.Points    => "points"
    case _: Layer.Segments  => "segments"
    case _: Layer.Triangles => "triangles"
    case _: Layer.Balls     => "balls"
    case _: Layer.Halos     => "halos"

  /** The shape that lights up the layer `l` while it is chosen (see [[Panel.Spatial.selecting]]); none for balls and
    * halos, which nothing chooses.
    */
  private[plot] def halo(l: Layer): Option[Layer] = l match
    case l: Layer.Points    => Some(Layer.Halos(l.at, l.paints, l.radius + 5.0, 0.3))
    case l: Layer.Segments  => Some(Layer.Segments(l.ends, l.paints, l.width + 6.0, 0.3))
    case l: Layer.Triangles => Some(Layer.Triangles(l.corners, l.paints, math.min(0.9, l.opacity + 0.35)))
    case _                  => None

  /** Which behaviors a layer can carry out: everything that needs no axis, and a radius for balls. */
  private[plot] def fitsLayer(l: Layer)(b: Behavior): Boolean = (l, b) match
    case (_, _: Behavior.Shown | _: Behavior.Custom | _: Behavior.Selects | _: Behavior.Selected) => true
    case (_: Layer.Balls, _: Behavior.Radius)                                                     => true
    case _                                                                                        => false

  /** The shape that lights up `m` while it is chosen (see [[Panel.Flat.selecting]]), with the same items in the same
    * places; none for a mark that has no good one.
    */
  private[plot] def halo(m: Mark): Option[Mark] = m match
    case m: Mark.Points =>
      // a round-capped segment of no length is a disc as wide as the line
      Some(
        Mark.Segments(
          m.at.map(p => (p, p)),
          m.paints,
          width = 2 * m.radius + 10,
          opacity = 0.3,
          roundCaps = true
        )
      )
    case m: Mark.Segments =>
      Some(Mark.Segments(m.ends, m.paints, width = m.width + 8, opacity = 0.3, roundCaps = true))
    case m: Mark.Polygons => Some(Mark.Polygons(m.corners, m.paints, opacity = math.min(0.9, m.opacity + 0.35)))
    case _                => None

  /** Which behaviors a mark can carry out. */
  private[plot] def fitsMark(m: Mark)(b: Behavior): Boolean = (m, b) match
    case (_, _: Behavior.Shown | _: Behavior.Custom | _: Behavior.Selects | _: Behavior.Selected) => true
    case (_: Mark.Segments, f: Behavior.Follows)                                                  => true
    case (r: Mark.Rule, f: Behavior.Follows) => (f.coordinate == Coordinate.X) == r.vertical
    case (_: Mark.Discs, _: Behavior.Radius) => true
    case _                                   => false

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
          case Behavior.Selects(c, key)       => Seq("s", c, key.toString)
          case Behavior.Selected(c, key)      => Seq("w", c, key.toString)
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

  private[plot] def selectionJson(s: Selection): String =
    val labels = s.labels.map(jsString).mkString("[", ",", "]")
    s"""{"n":${jsString(s.name)},"l":${jsString(s.shownName)},"labels":$labels,"hover":${s.hover},"click":${s.click}}"""

  // the list of what is chosen, under the controls
  private[plot] def selectionHtml(s: Selection): String =
    if !s.listed then ""
    else
      s"""<div class="tda4j-selection" data-selection="${Svg.escape(s.name)}"><span>${Svg.escape(
          s.shownName
        )}</span><output aria-live="polite"></output></div>"""

  private[plot] def styles(
    id: String,
    pal: Palette,
    theme: Theme,
    listing: Boolean = false,
    brushing: Boolean = false,
    zooming: Boolean = false
  ): String =
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
       |#$id .tda4j-play{font-size:14px;line-height:1;padding:6px 10px;border:1px solid var(--ink2);border-radius:4px;background:transparent;color:var(--ink);cursor:pointer}""".stripMargin +
      (if !listing then ""
       else s"""
               |#$id .tda4j-selection{display:flex;gap:10px;margin:10px 0 0;font-size:15px;min-height:1.4em}
               |#$id .tda4j-selection span{min-width:2.5em;color:var(--ink2)}
               |#$id .tda4j-selection output{flex:1;font-variant-numeric:tabular-nums}""".stripMargin) +
      (if !brushing then ""
       else
         s"""
            |#$id .tda4j-brush{fill:var(--s0);fill-opacity:.12;stroke:var(--s0);stroke-dasharray:4 3;pointer-events:none}
            |#$id .tda4j-cursor{fill:none;stroke:var(--s0);stroke-width:1.5;pointer-events:none}
            |#$id svg:focus-visible{outline:2px solid var(--s0);outline-offset:2px}
            |#$id [data-brush] svg{user-select:none}""".stripMargin) +
      (if !zooming then ""
       else
         s"""
            |#$id [data-z=s] *{vector-effect:non-scaling-stroke}
            |#$id .tda4j-panel{position:relative}
            |#$id .tda4j-zoombar{position:absolute;top:4px;right:4px;display:flex;gap:4px;z-index:1}
            |#$id .tda4j-zoombar button{min-width:26px;height:26px;padding:0 6px;font-size:14px;line-height:1;border:1px solid var(--ink2);border-radius:4px;background:var(--surface);color:var(--ink);cursor:pointer;opacity:.85}
            |#$id .tda4j-zoombar button[aria-pressed=true]{background:var(--s0);color:var(--surface)}
            |#$id [data-zoomable] svg{user-select:none}""".stripMargin)

  // ----- stills: the same behaviors, applied in Scala

  private def inside(shown: Behavior, values: Map[String, Double], chosen: Map[String, Set[Int]]): Boolean =
    shown match
      case Behavior.Shown(c, from, until) => values.get(c).forall(v => from <= v && v < until)
      case Behavior.Selected(c, key)      => chosen.get(c).exists(_.contains(key))
      case _                              => true

  private def clamp(v: Double, lo: Double, hi: Double): Double = math.min(math.max(v, lo), hi)

  private[plot] def reactionOf(rs: IndexedSeq[Reaction], i: Int): Reaction = rs(if rs.size == 1 then 0 else i)

  /** Why the page cannot zoom a picture of `figure` (it draws the ticks of the linear, logarithmic, square-root and
    * symmetric-log axes, forward or reversed, with the number formats it can write), or `None` when it can.
    */
  private[plot] def zoomProblem(figure: Figure): Option[String] =
    val (xt, yt) = Svg.resolved(figure)
    val (zeroX, zeroY) = Svg.atMinusInfinity(figure)
    val custom = Seq("x" -> xt, "y" -> yt).collectFirst { case (axis, t @ Transform.Custom(_, _, _)) =>
      s"its $axis axis uses a transform of your own (${t.label}) that the page cannot compute; " +
        "use identity, log, sqrt or symlog"
    }
    // a log axis with something at 0 has a `-∞` line below the numbers, which a window could not move with the rest
    val band = Seq("x" -> (xt, zeroX, figure.x), "y" -> (yt, zeroY, figure.y)).collectFirst {
      case (axis, (Transform.Log10, zero, a)) if zero || a.minusInfinity.contains(true) =>
        s"its $axis axis is logarithmic with a `-∞` line for what is at 0; zooming needs the numbers alone " +
          "(Axis(minusInfinity = Some(false)) leaves such marks out)"
    }
    val format = Seq("x" -> figure.x, "y" -> figure.y).collectFirst {
      case (axis, a) if a.format.exists(!formatSupported(_)) =>
        s"its $axis axis has the number format ${a.format.get}, which the page cannot write; it writes %f and %e " +
          "with the flags - + 0 and , (for f), a width and a precision"
    }
    custom.orElse(band).orElse(format)

  // the number formats the page writes as `String.format` does: `%f` and `%e` (or `%E`) with a width, a precision and
  // the flags `-`, `+`, `0` (and `,` for `f`), between any text and `%%`
  private val formatPattern =
    """(?:[^%]|%%|%(?:[-+0]*,?[-+0]*[0-9]*(?:\.[0-9]+)?f|[-+0]*[0-9]*(?:\.[0-9]+)?[eE]))*""".r

  private[plot] def formatSupported(pattern: String): Boolean = formatPattern.matches(pattern)

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
    values: Map[String, Double],
    chosen: Map[String, Set[Int]] = Map.empty,
    window: Option[Window] = None,
    brush: Option[Window] = None
  ): Figure =
    val marks = figure.marks.zipWithIndex.flatMap { (mark, index) =>
      reactions.get(index).fold(Option(mark))(rs => stillMark(mark, rs, values, chosen))
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
    val whole = figure.copy(marks = marks, x = pinned(figure.x, rangeX, zeroX), y = pinned(figure.y, rangeY, zeroY))
    // a zoomed picture shows the window as asked; what lies outside it is clipped
    val zoomed = window.fold(whole)(w =>
      whole.copy(
        x = whole.x.copy(min = Some(w.x0), max = Some(w.x1)),
        y = whole.y.copy(min = Some(w.y0), max = Some(w.y1))
      )
    )
    // the rectangle a viewer has dragged out lies over everything the panel draws
    brush.fold(zoomed)(b => zoomed.copy(marks = zoomed.marks :+ Mark.Region((b.x0, b.y0, b.x1, b.y1))))

  private def stillMark(
    mark: Mark,
    rs: IndexedSeq[Reaction],
    values: Map[String, Double],
    chosen: Map[String, Set[Int]]
  ): Option[Mark] =
    val n = itemCount(mark)
    val per = (0 until n).map(reactionOf(rs, _))
    val keep = per.map(_.behaviors.forall(inside(_, values, chosen)))
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

  /** `scene` with the controls and selections as they are: the items that are not shown dropped, the balls resized. */
  private[plot] def still(
    scene: Scene,
    reactions: Map[Int, IndexedSeq[Reaction]],
    values: Map[String, Double],
    chosen: Map[String, Set[Int]]
  ): Scene =
    val layers = scene.layers.zipWithIndex.map { (layer, index) =>
      reactions.get(index).fold(layer) { rs =>
        val n = itemCount(layer)
        val per = (0 until n).map(reactionOf(rs, _))
        val keep = per.map(_.behaviors.forall(inside(_, values, chosen)))
        layer match
          case l: Layer.Points    => l.copy(at = keepItems(l.at, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Segments  => l.copy(ends = keepItems(l.ends, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Triangles =>
            l.copy(corners = keepItems(l.corners, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Halos => l.copy(at = keepItems(l.at, keep), paints = keepPaints(l.paints, keep, n))
          case l: Layer.Balls =>
            val radius = per.headOption
              .flatMap(_.behaviors.collectFirst { case Behavior.Radius(c, factor) => values.get(c).map(_ * factor) })
              .flatten
              .getOrElse(l.radius)
            l.copy(centers = keepItems(l.centers, keep), radius = radius, paints = keepPaints(l.paints, keep, n))
      }
    }
    scene.copy(layers = layers)

  // ----- the page's controller

  /** The controller of an interactive page; the placeholders are filled in by `Interactive.fragment`. */
  private[plot] val runtime: String =
    """(function(){
      |const root=document.getElementById('__ID__'),spec=__CONTROLS__,choices=__SELECTIONS__;
      |const state={},ctl={},watch={},reactors={},byControl={},items=[],sel={},builtin={v:1,f:1,r:1,s:1,w:1};
      |choices.forEach(c=>{sel[c.n]={c:c,pin:new Set(),hov:new Set(),now:new Set(),byKey:{},out:null};});
      |const keysOf=n=>Array.from(sel[n].now).sort((a,b)=>a-b);
      |function refresh(it){it.el.style.display=Object.values(it.h).some(x=>x)?'none':'';}
      |const fmt=v=>String(Number(v.toPrecision(3)));
      |const tr={i:v=>v,l:v=>Math.log10(v),s:v=>Math.sign(v)*Math.sqrt(Math.abs(v)),m:(v,c)=>Math.sign(v)*Math.log10(1+Math.abs(v)/c)};
      |function axesOf(svg){
      |  if(svg._ax)return svg._ax;const f=svg.getAttribute('data-fr');if(!f)return null;
      |  return svg._ax=f.split(';').map(s=>{const a=s.split(',');return{o:+a[0],n:+a[1],lo:+a[2],hi:+a[3],rev:a[4]==='1',t:a[5],c:+a[6]};});}
      |function frameOf(svg){
      |  if(svg._fr)return svg._fr;const ax=axesOf(svg);if(!ax)return null;
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
      |  const it={el,bs,svg:el.ownerSVGElement,h:{},picks:[]};items.push(it);
      |  new Set(bs.map(b=>b.c)).forEach(c=>(byControl[c]=byControl[c]||[]).push(it));
      |  bs.forEach((b,j)=>{
      |    if(b.k==='s')it.picks.push({n:b.c,key:+b.a[0]});
      |    if(b.k==='w'){const s=sel[b.c],key=+b.a[0];(s.byKey[key]=s.byKey[key]||[]).push({it:it,j:j});
      |      it.h[j]=true;el.removeAttribute('display');el.style.pointerEvents='none';}});});
      |items.forEach(it=>{if(Object.keys(it.h).length&&it.bs.some(b=>b.k==='w'))refresh(it);});
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
      |function listOut(n){const s=sel[n];if(!s.out)return;const ks=keysOf(n),L=s.c.labels;
      |  const name=k=>L[k]!==undefined?L[k]:String(k);
      |  s.out.textContent=ks.length?ks.slice(0,12).map(name).join(' | ')+(ks.length>12?'  and '+(ks.length-12)+' more':''):'nothing chosen';}
      |function custom(n){const v=keysOf(n);
      |  (byControl[n]||[]).forEach(it=>it.bs.forEach((b,j)=>{if(b.c===n&&!builtin[b.k]&&reactors[b.k])reactors[b.k](it,b,v,j);}));}
      |function commit(n){const s=sel[n],next=new Set([...s.pin,...s.hov]),old=s.now;
      |  let same=next.size===old.size;if(same)for(const k of next)if(!old.has(k)){same=false;break;}
      |  if(same)return;s.now=next;
      |  const flip=(k,hide)=>(s.byKey[k]||[]).forEach(e=>{e.it.h[e.j]=hide;refresh(e.it);});
      |  for(const k of old)if(!next.has(k))flip(k,true);
      |  for(const k of next)if(!old.has(k))flip(k,false);
      |  listOut(n);custom(n);const v=keysOf(n);
      |  (watch[n]||[]).forEach(f=>f(v));
      |  root.dispatchEvent(new CustomEvent('tda4j:change',{detail:{name:n,value:v},bubbles:true}));}
      |function pinAll(n,ks){sel[n].pin=new Set(Array.from(ks,Number));commit(n);}
      |const pickers=new Map();
      |items.forEach(it=>{if(it.picks.length){const l=pickers.get(it.svg)||[];l.push(it);pickers.set(it.svg,l);}});
      |function inPlot(svg,x,y){const ax=axesOf(svg);if(!ax||!svg._zoomed)return true;
      |  const p=new DOMPoint(x,y).matrixTransform(svg.getScreenCTM().inverse());
      |  return p.x>=ax[0].o&&p.x<=ax[0].o+ax[0].n&&p.y>=ax[1].o&&p.y<=ax[1].o+ax[1].n;}
      |function hit(list,x,y){let best=null,bd=Infinity,ba=Infinity;
      |  if(list.length&&!inPlot(list[0].svg,x,y))return null;
      |  for(const it of list){if(!it.el.getClientRects().length)continue;
      |    const r=it.el.getBoundingClientRect();
      |    const d=Math.hypot(Math.max(r.left-x,0,x-r.right),Math.max(r.top-y,0,y-r.bottom));
      |    if(d>4)continue;
      |    const a=Math.max(r.width,1)*Math.max(r.height,1);
      |    if(d<bd||(d===bd&&a<=ba)){bd=d;ba=a;best=it;}}
      |  return best;}
      |const namesOf=list=>{const out=new Set();list.forEach(it=>it.picks.forEach(p=>out.add(p.n)));return out;};
      |function hover(svg,names,it){
      |  for(const n of names){const s=sel[n];if(!s.c.hover)continue;
      |    s.hov=new Set(it?it.picks.filter(p=>p.n===n).map(p=>p.key):[]);commit(n);}
      |  svg.style.cursor=it?'pointer':'';}
      |function clickKeys(n,ks,shift){const s=sel[n];if(!s||!s.c.click)return;
      |  if(!ks.length){if(s.pin.size){s.pin=new Set();commit(n);}return;}
      |  if(shift)ks.forEach(k=>{if(s.pin.has(k))s.pin.delete(k);else s.pin.add(k);});
      |  else{const same=s.pin.size===ks.length&&ks.every(k=>s.pin.has(k));s.pin=same?new Set():new Set(ks);}
      |  commit(n);}
      |function hoverKeys(n,ks){const s=sel[n];if(!s||!s.c.hover)return;s.hov=new Set(ks);commit(n);}
      |function clicked(names,it,shift){
      |  for(const n of names){
      |    if(!it){clickKeys(n,[],shift);continue;}
      |    const ks=it.picks.filter(p=>p.n===n).map(p=>p.key);
      |    if(ks.length)clickKeys(n,ks,shift);}}
      |const NS='http://www.w3.org/2000/svg';
      |const boxed=[];
      |const clearBoxes=()=>boxed.forEach(s=>{if(s._box){s._box.remove();s._box=null;}});
      |const brushes={};
      |function brushing(svg,list,n,pi){let from=null,kb=null;
      |  boxed.push(svg);
      |  const showBox=(a,b)=>{
      |    if(!svg._box){svg._box=document.createElementNS(NS,'rect');svg._box.setAttribute('class','tda4j-brush');svg.appendChild(svg._box);}
      |    svg._box.setAttribute('x',Math.min(a.x,b.x));svg._box.setAttribute('y',Math.min(a.y,b.y));
      |    svg._box.setAttribute('width',Math.abs(a.x-b.x));svg._box.setAttribute('height',Math.abs(a.y-b.y));};
      |  // the items a rectangle (in client pixels) touches; a zoomed picture takes only what the plot area shows
      |  const choose=(x0,y0,x1,y1,shift)=>{
      |    const ax=axesOf(svg);
      |    if(ax&&svg._zoomed){const m=svg.getScreenCTM(),a=new DOMPoint(ax[0].o,ax[1].o).matrixTransform(m),b=new DOMPoint(ax[0].o+ax[0].n,ax[1].o+ax[1].n).matrixTransform(m);
      |      x0=Math.max(x0,a.x);y0=Math.max(y0,a.y);x1=Math.min(x1,b.x);y1=Math.min(y1,b.y);}
      |    const keys=new Set(shift?sel[n].pin:[]);
      |    for(const it of list){if(!it.el.getClientRects().length)continue;
      |      const r=it.el.getBoundingClientRect();
      |      if(r.right>=x0&&r.left<=x1&&r.bottom>=y0&&r.top<=y1)it.picks.forEach(p=>{if(p.n===n)keys.add(p.key);});}
      |    sel[n].pin=keys;commit(n);};
      |  brushes[pi]={svg:svg,show:showBox};
      |  svg.addEventListener('pointerdown',ev=>{svg._dragged=false;
      |    if(ev.button!==0||ev.pointerType==='touch'||svg._pan)return;from=[ev.clientX,ev.clientY];});
      |  svg.addEventListener('pointermove',ev=>{if(!from)return;
      |    if(!svg._dragged){if(Math.hypot(ev.clientX-from[0],ev.clientY-from[1])<5)return;
      |      svg._dragged=true;try{svg.setPointerCapture(ev.pointerId);}catch(e){}clearBoxes();}
      |    const m=svg.getScreenCTM().inverse();
      |    showBox(new DOMPoint(from[0],from[1]).matrixTransform(m),new DOMPoint(ev.clientX,ev.clientY).matrixTransform(m));});
      |  svg.addEventListener('pointerup',ev=>{if(!from)return;const start=from;from=null;
      |    if(!svg._dragged)return;
      |    try{svg.releasePointerCapture(ev.pointerId);}catch(e){}
      |    choose(Math.min(start[0],ev.clientX),Math.min(start[1],ev.clientY),Math.max(start[0],ev.clientX),Math.max(start[1],ev.clientY),ev.shiftKey);});
      |  // the keyboard: arrows move a cross over the plot area (shift: further), Enter sets the first corner and then the second, which
      |  // chooses (with shift: adds), Escape lets go; where a drag pans, the arrows pan
      |  const cursorAt=(x,y)=>{
      |    if(!svg._cur){svg._cur=document.createElementNS(NS,'path');svg._cur.setAttribute('class','tda4j-cursor');svg.appendChild(svg._cur);}
      |    svg._cur.setAttribute('d','M'+(x-8)+' '+y+'H'+(x+8)+'M'+x+' '+(y-8)+'V'+(y+8));};
      |  const leave=()=>{kb=null;if(svg._cur){svg._cur.remove();svg._cur=null;}};
      |  svg.addEventListener('keydown',ev=>{
      |    if(svg._pan||ev.ctrlKey||ev.metaKey||ev.altKey)return;
      |    const ax=axesOf(svg),P=ax?{x:ax[0].o,y:ax[1].o,w:ax[0].n,h:ax[1].n}:{x:0,y:0,w:svg.viewBox.baseVal.width,h:svg.viewBox.baseVal.height};
      |    const d={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[ev.key];
      |    if(d){ev.preventDefault();kb=kb||{x:P.x+P.w/2,y:P.y+P.h/2,from:null};const step=ev.shiftKey?0.16:0.04;
      |      kb.x=Math.min(Math.max(kb.x+d[0]*step*P.w,P.x),P.x+P.w);kb.y=Math.min(Math.max(kb.y+d[1]*step*P.h,P.y),P.y+P.h);
      |      cursorAt(kb.x,kb.y);if(kb.from)showBox(kb.from,kb);return;}
      |    if((ev.key==='Enter'||ev.key===' ')&&kb){ev.preventDefault();
      |      if(!kb.from){clearBoxes();kb.from={x:kb.x,y:kb.y};showBox(kb.from,kb);return;}
      |      const m=svg.getScreenCTM(),a=new DOMPoint(kb.from.x,kb.from.y).matrixTransform(m),b=new DOMPoint(kb.x,kb.y).matrixTransform(m);
      |      choose(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.max(a.x,b.x),Math.max(a.y,b.y),ev.shiftKey);leave();return;}
      |    if(ev.key==='Escape'&&kb){ev.preventDefault();clearBoxes();leave();}});
      |  svg.addEventListener('blur',leave);}
      |pickers.forEach((list,svg)=>{const names=namesOf(list);
      |  svg.addEventListener('pointermove',ev=>{if(ev.pointerType==='touch'||svg._dragged||ev.buttons)return;svg._xy=[ev.clientX,ev.clientY];
      |    if(svg._raf)return;
      |    svg._raf=requestAnimationFrame(()=>{svg._raf=0;hover(svg,names,hit(list,svg._xy[0],svg._xy[1]));});});
      |  svg.addEventListener('pointerleave',()=>{if(svg._raf){cancelAnimationFrame(svg._raf);svg._raf=0;}hover(svg,names,null);});
      |  svg.addEventListener('click',ev=>{if(svg._dragged){svg._dragged=false;return;}
      |    clearBoxes();clicked(names,hit(list,ev.clientX,ev.clientY),ev.shiftKey);});
      |  const box=svg.closest('.tda4j-panel');const bn=box&&box.getAttribute('data-brush');
      |  if(bn&&sel[bn]){svg.setAttribute('tabindex','0');brushing(svg,list,bn,Array.from(root.querySelectorAll('.tda4j-panel')).indexOf(box));}});
      |const MAXZ=200,zooms={};
      |function numberText(v){const a=Math.abs(v);if(v===0)return '0';
      |  if(a>=1e5||a<1e-3){if(a<1e-3&&a>=1e-4)return v.toPrecision(3);
      |    const m=/^(-?[0-9.]+)e([+-])([0-9]+)$/.exec(v.toExponential(2));return m[1]+'e'+m[2]+(m[3].length<2?'0'+m[3]:m[3]);}
      |  const s=String(Number(v.toPrecision(4)));return s==='-0'?'0':s;}
      |function niceTicks(lo,hi,target){
      |  if(!(hi>lo)||!isFinite(hi)||!isFinite(lo))return [lo];
      |  const raw=(hi-lo)/target,mag=Math.pow(10,Math.floor(Math.log10(raw))),norm=raw/mag;
      |  const step=mag*(norm<1.5?1:norm<3?2:norm<7?5:10),first=Math.ceil(lo/step-1e-9),last=Math.floor(hi/step+1e-9),out=[];
      |  for(let i=first;i<=last;i++){const t=i*step;out.push(t===0?0:t);}return out;}
      |const inv={i:u=>u,l:u=>Math.pow(10,u),s:u=>Math.sign(u)*u*u,m:(u,c)=>Math.sign(u)*c*(Math.pow(10,Math.abs(u))-1)};
      |// numbers as the plot writes them: `String.format`'s %f and %e (rounding the shortest decimal half up, as Java does) and NumberStyle
      |function digitsOf(a){if(a===0)return {d:'0',e:0};
      |  const m=/^([0-9])(?:\.([0-9]+))?e([+-][0-9]+)$/.exec(a.toExponential());return {d:m[1]+(m[2]||''),e:+m[3]};}
      |function scaled(a,frac){const g=digitsOf(a),keep=g.e+1+frac;if(keep<0)return 0n;
      |  let n=keep===0?0n:BigInt(g.d.slice(0,keep).padEnd(keep,'0'));if(g.d.charAt(keep)>='5')n+=1n;return n;}
      |function expo(a,prec){if(a===0)return [prec>0?'0.'+'0'.repeat(prec):'0',0];
      |  const g=digitsOf(a),keep=prec+1;let n=BigInt(g.d.slice(0,keep).padEnd(keep,'0'));if(g.d.charAt(keep)>='5')n+=1n;
      |  let t=n.toString(),e=g.e;if(t.length>keep){t=t.slice(0,keep);e+=1;}
      |  return [t.charAt(0)+(prec>0?'.'+t.slice(1):''),e];}
      |function javaFormat(pattern,v){
      |  return pattern.replace(/%(?:(%)|([-+0]*,?[-+0]*)([0-9]*)(?:\.([0-9]+))?([feE]))/g,(all,pct,flags,width,prec,conv)=>{
      |    if(pct)return '%';
      |    const w=width?+width:0;
      |    // NaN and the infinities are words: padded with spaces (never zeros), a sign only on an infinity
      |    if(!isFinite(v)){let word=isNaN(v)?'NaN':(v<0?'-':flags.includes('+')?'+':'')+'Infinity';if(conv==='E')word=word.toUpperCase();
      |      return word.length>=w?word:flags.includes('-')?word.padEnd(w):word.padStart(w);}
      |    const neg=v<0||Object.is(v,-0),a=Math.abs(v),sign=neg?'-':flags.includes('+')?'+':'',p=prec===undefined?6:+prec;
      |    let text;
      |    if(conv==='f'){const n=scaled(a,p).toString().padStart(p+1,'0'),whole=n.slice(0,n.length-p),part=n.slice(n.length-p);
      |      text=(flags.includes(',')?whole.replace(/\B(?=([0-9]{3})+(?![0-9]))/g,','):whole)+(p>0?'.'+part:'');}
      |    else{const x=expo(a,p),ae=Math.abs(x[1]);text=x[0]+'e'+(x[1]<0?'-':'+')+(ae<10?'0'+ae:String(ae));if(conv==='E')text=text.toUpperCase();}
      |    let out=sign+text;
      |    if(out.length<w){if(flags.includes('-'))out=out.padEnd(w);else if(flags.includes('0'))out=sign+text.padStart(w-sign.length,'0');else out=out.padStart(w);}
      |    return out;});}
      |function localize(t,ns){if(!ns)return t;const dec=ns[0]||'.',grp=ns[1],mi=ns[2]||'-';
      |  if(t.startsWith('-∞'))return mi+t.slice(1);
      |  return t.replace(/(-?)([0-9]+)(?:\.([0-9]+))?(?:e(-?[0-9]+))?/g,(m,sg,wh,fr,ex)=>{
      |    const rev=Array.from(wh).reverse().join(''),grouped=grp?Array.from(rev.match(/.{1,3}/g).join(Array.from(grp).reverse().join(''))).reverse().join(''):wh;
      |    return (sg?mi:'')+grouped+(fr!==undefined?dec+fr:'')+(ex!==undefined?'e'+ex.replace('-',mi):'');});}
      |function logLabel(v){if(v>0&&(v<1e-3||v>=1e5)){const e=Math.floor(Math.log10(v)+1e-9),m=v/Math.pow(10,e);
      |  if(Math.abs(m-Math.round(m))<1e-9)return Math.round(m)+'e'+e;}return numberText(v);}
      |const tickText=(s,t,v)=>s.f!==null&&s.f!==undefined?javaFormat(s.f,v):t==='l'?logLabel(v):numberText(v);
      |function logTicks(lo,hi,target){
      |  if(!(lo>0&&hi>lo&&isFinite(hi)))return [];
      |  const kLo=Math.floor(Math.log10(lo)),kHi=Math.ceil(Math.log10(hi)),decades=kHi-kLo,stride=Math.max(1,Math.ceil(decades/6)),multiples=decades>=4?[1]:[1,2,5],found=[];
      |  for(let k=kLo;k<=kHi;k+=stride)for(const m of multiples){const t=m*Math.pow(10,k);if(t>=lo*(1-1e-9)&&t<=hi*(1+1e-9))found.push(t);}
      |  return found.length>=2?found:niceTicks(lo,hi,target);}
      |function trTicks(t,c,lo,hi,target){
      |  if(t==='l')return logTicks(lo,hi,target);
      |  if(t==='m'){const reach=Math.max(Math.abs(lo),Math.abs(hi));if(reach<=10*c)return niceTicks(lo,hi,target);
      |    const mag=logTicks(c,reach,target);return mag.map(x=>-x).reverse().concat([0],mag).filter(x=>x>=lo&&x<=hi);}
      |  return niceTicks(lo,hi,target);}
      |// the minor ticks between `majors`: the whole numbers of each decade on a log axis, else the halves between ticks
      |function minorPositions(t,c,majors,lo,hi){
      |  const T=tr[t],U=inv[t],ms=Array.from(new Set(majors.filter(v=>isFinite(T(v,c))))).sort((a,b)=>a-b);
      |  if(ms.length<2)return [];
      |  const whole=v=>v>0&&Math.abs(v/Math.pow(10,Math.floor(Math.log10(v)+1e-12))-Math.round(v/Math.pow(10,Math.floor(Math.log10(v)+1e-12))))<1e-9;
      |  let cand=[];
      |  if(t==='l'&&ms.every(whole)){const k0=Math.floor(Math.log10(Math.max(lo,1e-300))),k1=Math.ceil(Math.log10(hi));
      |    for(let k=k0;k<=k1;k++)for(let j=1;j<=9;j++)cand.push(j*Math.pow(10,k));}
      |  else{const us=ms.map(v=>T(v,c)),steps=us.slice(1).map((b,i)=>b-us[i]),edges=[us[0]-steps[0]].concat(us,[us[us.length-1]+steps[steps.length-1]]);
      |    cand=edges.slice(1).map((b,i)=>U((edges[i]+b)/2,c));}
      |  return cand.filter(v=>isFinite(T(v,c))&&v>=lo&&v<=hi&&!ms.some(m=>Math.abs(T(m,c)-T(v,c))<1e-9*(1+Math.abs(T(m,c)))));}
      |// the ticks of an axis showing positions `a` to `b` (of its transform): the labelled ones and the minor ones
      |function ticksFor(s,t,c,a,b){const T=tr[t],U=inv[t],lo=U(a,c),hi=U(b,c),span=Math.abs(b-a);let major;
      |  if(s.m==='f'||s.m==='l')major=s.fx.slice();
      |  else{const l=s.m==='w'?Math.max(s.lo,lo):lo,h=s.m==='w'?Math.min(s.hi,hi):hi;
      |    const clear=v=>s.ex.every(e=>Math.abs(T(v,c)-T(e[0],c))>=0.05*span);
      |    major=trTicks(t,c,l,h,5).filter(clear).map(v=>[v,tickText(s,t,v)]);}
      |  const inside=v=>{const u=T(v,c);return u>=a&&u<=b;};
      |  const minor=s.m==='l'?[]:minorPositions(t,c,major.map(x=>x[0]),lo,hi).filter(inside);
      |  return {major:major.concat(s.ex).filter(x=>inside(x[0])),minor:minor};}
      |// where a position `u` of axis `a` lands, when the axis shows `lo` to `hi`
      |function posFn(a,lo,hi,vertical){const n=a.n,o=a.o;
      |  if(!vertical)return a.rev?u=>o+(hi-u)/(hi-lo)*n:u=>o+(u-lo)/(hi-lo)*n;
      |  return a.rev?u=>o+(u-lo)/(hi-lo)*n:u=>o+n-(u-lo)/(hi-lo)*n;}
      |function drawAxes(z,whole){
      |  if(z.gen){z.gen.remove();z.gen=null;}
      |  z.orig.forEach(e=>{e.style.display=whole?'':'none';});
      |  if(whole)return;
      |  const w=z.win,X=z.ax[0],Y=z.ax[1],px=posFn(X,w[0],w[1],false),py=posFn(Y,w[2],w[3],true);
      |  const grid=[],marks=[],labels=[],ns=z.spec.n,angle=z.spec.a;
      |  for(const axis of ['x','y']){
      |    const A=axis==='x'?X:Y,lo=axis==='x'?w[0]:w[2],hi=axis==='x'?w[1]:w[3],T=tr[A.t],tk=ticksFor(z.spec[axis],A.t,A.c,lo,hi);
      |    const place=(tp,at,list)=>{if(!tp)return;const e=tp.cloneNode(false);e.style.removeProperty('display');e.removeAttribute('data-k');
      |      if(axis==='x'){e.setAttribute('x1',at);e.setAttribute('x2',at);}else{e.setAttribute('y1',at);e.setAttribute('y2',at);}list.push(e);};
      |    for(const t of tk.major){const at=(axis==='x'?px:py)(T(t[0],A.c));
      |      place(z.tpl['g'+axis],at,grid);place(z.tpl['m'+axis],at,marks);
      |      const tl=z.tpl['l'+axis];
      |      if(tl){const e=tl.cloneNode(false);e.style.removeProperty('display');e.removeAttribute('data-k');e.removeAttribute('data-v');e.textContent=localize(t[1],ns);
      |        if(axis==='x'){e.setAttribute('x',at);if(angle)e.setAttribute('transform','rotate('+(-angle)+' '+at+' '+z.labelY+')');}else e.setAttribute('y',at+z.off);labels.push(e);}}
      |    for(const v of tk.minor){const at=(axis==='x'?px:py)(T(v,A.c));place(z.tpl['n'+axis],at,grid);place(z.tpl['i'+axis],at,marks);}}
      |  const g=document.createElementNS(NS,'g');g.setAttribute('data-gen','1');
      |  grid.concat(marks,labels).forEach(e=>g.appendChild(e));
      |  z.svg.insertBefore(g,z.svg.querySelector('g[clip-path]'));z.gen=g;}
      |function setWindow(z,w){clearBoxes();
      |  const X=z.ax[0],Y=z.ax[1],full=z.full;w=w.slice();
      |  if(z.axes==='x'){w[2]=full[2];w[3]=full[3];}
      |  if(z.axes==='y'){w[0]=full[0];w[1]=full[1];}
      |  // a picture drawn with a fixed ratio of the lengths of a y unit and an x unit widens one range of a window, as the renderer does
      |  if(z.asp&&z.axes==='b'){const sx=(w[1]-w[0])/X.n,sy=(w[3]-w[2])/Y.n,wanted=sx/z.asp;
      |    if(sy<wanted){const c=(w[2]+w[3])/2;w[2]=c-wanted*Y.n/2;w[3]=c+wanted*Y.n/2;}
      |    else{const c=(w[0]+w[1])/2;w[0]=c-sy*z.asp*X.n/2;w[1]=c+sy*z.asp*X.n/2;}}
      |  for(const i of [0,2]){const fw=full[i+1]-full[i],wd=Math.min(Math.max(w[i+1]-w[i],fw/MAXZ),fw),a=Math.min(Math.max(w[i],full[i]),full[i+1]-wd);w[i]=a;w[i+1]=a+wd;}
      |  z.win=w;
      |  const fx=posFn(X,X.lo,X.hi,false),wx=posFn(X,w[0],w[1],false),fy=posFn(Y,Y.lo,Y.hi,true),wy=posFn(Y,w[2],w[3],true);
      |  const sx=(wx(X.hi)-wx(X.lo))/(fx(X.hi)-fx(X.lo)),tx=wx(X.lo)-sx*fx(X.lo);
      |  const sy=(wy(Y.hi)-wy(Y.lo))/(fy(Y.hi)-fy(Y.lo)),ty=wy(Y.lo)-sy*fy(Y.lo);
      |  const whole=Math.abs(sx-1)<1e-12&&Math.abs(sy-1)<1e-12&&Math.abs(tx)<1e-9&&Math.abs(ty)<1e-9;
      |  z.svg._zoomed=!whole;
      |  z.svg.querySelectorAll('g[data-z=s]').forEach(g=>{if(whole)g.removeAttribute('transform');else g.setAttribute('transform','matrix('+sx+' 0 0 '+sy+' '+tx+' '+ty+')');});
      |  z.pts.forEach(p=>{if(whole)p.el.removeAttribute('transform');else p.el.setAttribute('transform','translate('+((sx-1)*p.cx+tx)+' '+((sy-1)*p.cy+ty)+')');});
      |  drawAxes(z,whole);
      |  root.dispatchEvent(new CustomEvent('tda4j:zoom',{detail:{panel:z.pi,window:toData(z,w)},bubbles:true}));}
      |// a window in the units of the data, and in the positions of the axes' transforms (which the page draws in)
      |const toData=(z,w)=>[inv[z.ax[0].t](w[0],z.ax[0].c),inv[z.ax[0].t](w[1],z.ax[0].c),inv[z.ax[1].t](w[2],z.ax[1].c),inv[z.ax[1].t](w[3],z.ax[1].c)];
      |function toSpace(z,w){const r=[tr[z.ax[0].t](w[0],z.ax[0].c),tr[z.ax[0].t](w[1],z.ax[0].c),tr[z.ax[1].t](w[2],z.ax[1].c),tr[z.ax[1].t](w[3],z.ax[1].c)];
      |  if(!r.every(isFinite))throw new Error('the window '+w.join(', ')+' has an end that its axis cannot show (positive numbers only, on a log axis)');return r;}
      |// what a picture shows now (its window, if it zooms), and where a client point is in the positions of its axes' transforms
      |function viewOf(svg,z){const ax=axesOf(svg),w=z?z.win:[ax[0].lo,ax[0].hi,ax[1].lo,ax[1].hi],px=posFn(ax[0],w[0],w[1],false),py=posFn(ax[1],w[2],w[3],true);
      |  return{ax:ax,w:w,x:v=>px(tr[ax[0].t](v,ax[0].c)),y:v=>py(tr[ax[1].t](v,ax[1].c))};}
      |function spaceAt(svg,z,cx,cy){const p=new DOMPoint(cx,cy).matrixTransform(svg.getScreenCTM().inverse()),v=viewOf(svg,z),X=v.ax[0],Y=v.ax[1],w=v.w;
      |  const fx=(p.x-X.o)/X.n,fy=(Y.o+Y.n-p.y)/Y.n;
      |  return [X.rev?w[1]-fx*(w[1]-w[0]):w[0]+fx*(w[1]-w[0]),Y.rev?w[3]-fy*(w[3]-w[2]):w[2]+fy*(w[3]-w[2])];}
      |const dataAt=(z,ev)=>spaceAt(z.svg,z,ev.clientX,ev.clientY);
      |function zoomBy(z,f,cx,cy){const w=z.win;setWindow(z,[cx-(cx-w[0])*f,cx+(w[1]-cx)*f,cy-(cy-w[2])*f,cy+(w[3]-cy)*f]);}
      |root.querySelectorAll('.tda4j-panel').forEach((box,pi)=>{
      |  const svg=box.querySelector('svg[data-zm]');if(!svg)return;
      |  const ax=axesOf(svg),z={svg:svg,pi:pi,ax:ax,spec:JSON.parse(svg.getAttribute('data-zm')),axes:'b',tpl:{},mode:'pan'};
      |  z.axes=z.spec.ax;z.asp=z.spec.asp;z.full=[ax[0].lo,ax[0].hi,ax[1].lo,ax[1].hi];z.win=z.full.slice();
      |  z.orig=Array.from(svg.querySelectorAll('[data-k]'));
      |  ['gx','mx','lx','nx','ix','gy','my','ly','ny','iy'].forEach(k=>{z.tpl[k]=svg.querySelector('[data-k="'+k+'"]');});
      |  z.off=0;if(z.tpl.ly){z.off=+z.tpl.ly.getAttribute('y')-posFn(ax[1],ax[1].lo,ax[1].hi,true)(tr[ax[1].t](+z.tpl.ly.getAttribute('data-v'),ax[1].c));}
      |  z.labelY=z.tpl.lx?+z.tpl.lx.getAttribute('y'):0;
      |  z.pts=Array.from(svg.querySelectorAll('g[data-z=p] [data-c]')).map(el=>{const c=el.getAttribute('data-c').split(',');return{el:el,cx:+c[0],cy:+c[1]};});
      |  zooms[pi]=z;
      |  const bar=document.createElement('div');bar.className='tda4j-zoombar';
      |  const button=(text,label,f)=>{const b=document.createElement('button');b.type='button';b.textContent=text;b.setAttribute('aria-label',label);b.title=label;b.addEventListener('click',f);bar.appendChild(b);return b;};
      |  const centre=()=>[(z.win[0]+z.win[1])/2,(z.win[2]+z.win[3])/2];
      |  button('+','zoom in',()=>{const c=centre();zoomBy(z,0.5,c[0],c[1]);});
      |  button('\u2212','zoom out',()=>{const c=centre();zoomBy(z,2,c[0],c[1]);});
      |  button('1:1','show everything',()=>setWindow(z,z.full));
      |  if(box.getAttribute('data-brush')){z.mode='brush';
      |    const t=button('pan','drag to pan instead of choosing',()=>{z.mode=z.mode==='pan'?'brush':'pan';svg._pan=z.mode==='pan';t.setAttribute('aria-pressed',z.mode==='pan');});
      |    t.setAttribute('aria-pressed','false');}
      |  svg._pan=z.mode==='pan';
      |  box.insertBefore(bar,svg);
      |  svg.addEventListener('wheel',ev=>{if(!(ev.ctrlKey||ev.metaKey))return;ev.preventDefault();
      |    const c=dataAt(z,ev);zoomBy(z,Math.exp(ev.deltaY*0.0015),c[0],c[1]);},{passive:false});
      |  // the keyboard: + and - zoom about the middle, 0 shows everything, and where a drag pans, the arrows pan (shift: further)
      |  svg.setAttribute('tabindex','0');
      |  svg.addEventListener('keydown',ev=>{if(ev.ctrlKey||ev.metaKey||ev.altKey)return;
      |    const c=centre(),w=z.win;
      |    if(ev.key==='+'||ev.key==='='){ev.preventDefault();zoomBy(z,0.5,c[0],c[1]);return;}
      |    if(ev.key==='-'||ev.key==='_'){ev.preventDefault();zoomBy(z,2,c[0],c[1]);return;}
      |    if(ev.key==='0'){ev.preventDefault();setWindow(z,z.full);return;}
      |    const d={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,1],ArrowDown:[0,-1]}[ev.key];
      |    if(!d||z.mode!=='pan')return;ev.preventDefault();
      |    const f=ev.shiftKey?0.5:0.1,dx=d[0]*(ax[0].rev?-1:1)*f*(w[1]-w[0]),dy=d[1]*(ax[1].rev?-1:1)*f*(w[3]-w[2]);
      |    setWindow(z,[w[0]+dx,w[1]+dx,w[2]+dy,w[3]+dy]);});
      |  // touch: two fingers pinch and drag the picture (the distance zooms, the midpoint pans), one finger scrolls the page and a tap
      |  // chooses; touch events, because a page keeps scrolling under one finger. Put both fingers down, then move.
      |  svg.style.touchAction='pan-x pan-y';
      |  let pinch=null;
      |  const fingers=ev=>{const t=Array.from(ev.touches).filter(x=>svg.contains(x.target));if(t.length!==2)return null;
      |    const m=svg.getScreenCTM().inverse(),a=new DOMPoint(t[0].clientX,t[0].clientY).matrixTransform(m),b=new DOMPoint(t[1].clientX,t[1].clientY).matrixTransform(m);
      |    return{x:(a.x+b.x)/2,y:(a.y+b.y)/2,d:Math.hypot(a.x-b.x,a.y-b.y)};};
      |  // the window of one axis that keeps what was under the first midpoint (p0) under the current one (p1), `f` as wide
      |  const keep=(A,lo,hi,p0,p1,f,vertical)=>{const W=(hi-lo)*f,fr=p=>vertical?(A.o+A.n-p)/A.n:(p-A.o)/A.n,
      |    anchor=A.rev?hi-fr(p0)*(hi-lo):lo+fr(p0)*(hi-lo),a=A.rev?anchor+fr(p1)*W-W:anchor-fr(p1)*W;return[a,a+W];};
      |  svg.addEventListener('touchstart',ev=>{const g=fingers(ev);if(!g){pinch=null;return;}ev.preventDefault();clearBoxes();pinch={g:g,win:z.win.slice()};},{passive:false});
      |  svg.addEventListener('touchmove',ev=>{const g=fingers(ev);if(!g||!pinch)return;ev.preventDefault();
      |    const s=pinch.g,w=pinch.win,f=Math.max(s.d,1)/Math.max(g.d,1),xs=keep(ax[0],w[0],w[1],s.x,g.x,f,false),ys=keep(ax[1],w[2],w[3],s.y,g.y,f,true);
      |    setWindow(z,[xs[0],xs[1],ys[0],ys[1]]);},{passive:false});
      |  const lift=ev=>{if(Array.from(ev.touches).filter(x=>svg.contains(x.target)).length<2)pinch=null;};
      |  svg.addEventListener('touchend',lift);svg.addEventListener('touchcancel',lift);
      |  let from=null,start=null;
      |  svg.addEventListener('pointerdown',ev=>{if(ev.button!==0||ev.pointerType==='touch'||z.mode!=='pan')return;
      |    svg._dragged=false;from=[ev.clientX,ev.clientY];start=z.win.slice();});
      |  svg.addEventListener('pointermove',ev=>{if(!from)return;
      |    if(!svg._dragged){if(Math.hypot(ev.clientX-from[0],ev.clientY-from[1])<5)return;svg._dragged=true;try{svg.setPointerCapture(ev.pointerId);}catch(e){}}
      |    const m=svg.getScreenCTM().inverse(),a=new DOMPoint(from[0],from[1]).matrixTransform(m),b=new DOMPoint(ev.clientX,ev.clientY).matrixTransform(m);
      |    const dx=(ax[0].rev?1:-1)*(b.x-a.x)/ax[0].n*(start[1]-start[0]),dy=(ax[1].rev?-1:1)*(b.y-a.y)/ax[1].n*(start[3]-start[2]);
      |    setWindow(z,[start[0]+dx,start[1]+dx,start[2]+dy,start[3]+dy]);});
      |  svg.addEventListener('pointerup',ev=>{if(!from)return;from=null;try{svg.releasePointerCapture(ev.pointerId);}catch(e){}});});
      |const api={root:root,controls:spec,selections:choices,
      |  get:n=>sel[n]?keysOf(n):state[n],
      |  set:(n,v)=>sel[n]?pinAll(n,v):put(ctl[n],v),
      |  select:pinAll,
      |  hover:hoverKeys,
      |  click:clickKeys,
      |  selection:keysOf,
      |  onChange:(n,f)=>{(watch[n]=watch[n]||[]).push(f);f(sel[n]?keysOf(n):state[n]);},
      |  reactor:(k,f)=>{reactors[k]=f;for(const n in ctl)apply(n);for(const n in sel)custom(n);},
      |  zoom:(i,w)=>{if(!zooms[i])throw new Error('no zoomable panel '+i);setWindow(zooms[i],toSpace(zooms[i],w));},
      |  // the rectangle of a brushable panel, a window in the data's units (none clears it), drawn as a drag draws it; the keys it touches
      |  // are chosen with `select`. `pointAt` is the data under a client point of a panel that zooms or brushes.
      |  brush:(i,w)=>{const b=brushes[i];if(!b)throw new Error('no brushable panel '+i);clearBoxes();if(!w)return;
      |    if(!w.every(isFinite))throw new Error('the brush '+w.join(', ')+' has an end that is not a number');
      |    const v=viewOf(b.svg,zooms[i]);b.show({x:v.x(w[0]),y:v.y(w[2])},{x:v.x(w[1]),y:v.y(w[3])});},
      |  pointAt:(i,cx,cy)=>{const svg=brushes[i]?brushes[i].svg:zooms[i]&&zooms[i].svg;if(!svg)throw new Error('no panel '+i+' that zooms or brushes');
      |    const u=spaceAt(svg,zooms[i],cx,cy),ax=axesOf(svg);return [inv[ax[0].t](u[0],ax[0].c),inv[ax[1].t](u[1],ax[1].c)];},
      |  window:i=>zooms[i]?toData(zooms[i],zooms[i].win):null,
      |  text:{format:javaFormat,number:numberText,localize:localize,ticks:(i,axis)=>{const z=zooms[i],k=axis==='x'?0:1,A=z.ax[k];return ticksFor(z.spec[axis],A.t,A.c,z.win[2*k],z.win[2*k+1]);}},
      |  resetZoom:i=>{if(zooms[i])setWindow(zooms[i],zooms[i].full);},
      |  items:items};
      |root.tda4j=api;
      |choices.forEach(c=>{const box=root.querySelector('.tda4j-selection[data-selection="'+c.n+'"]');
      |  if(box){sel[c.n].out=box.querySelector('output');listOut(c.n);}});
      |spec.forEach(c=>{ctl[c.n]=c;
      |  const box=root.querySelector('.tda4j-control[data-control="'+c.n+'"]');
      |  c.input=box.querySelector('input');c.out=box.querySelector('output');c.btn=box.querySelector('.tda4j-play');
      |  c.input.addEventListener('input',()=>put(c,c.stops?c.stops[+c.input.value]:+c.input.value,true));
      |  if(c.btn)c.btn.addEventListener('click',()=>playing(c));
      |  put(c,c.v);});
      |__SCRIPTS__
      |})();""".stripMargin
