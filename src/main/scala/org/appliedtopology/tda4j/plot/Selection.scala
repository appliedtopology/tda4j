package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** A set of things the viewer picks by pointing at them: the classes of a barcode, the points of a cloud. Items of a
  * [[Panel]] name a `key` (a whole number, the index of the thing they stand for) and choose it with
  * [[Behavior.Selects]]; other items appear while a key is chosen with [[Behavior.Selected]], so that pointing at a bar
  * of the barcode lights up its point in the diagram and its cycle on the input. A selection is to the viewer's pointer
  * what a [[Control]] is to a slider: a named input that behaviors listen to.
  *
  * Pointing at an item chooses its key for as long as the pointer stays (`hover`); a click pins it until it is clicked
  * again (a shift-click adds to the pinned keys, a click on the background of a panel lets them all go); a
  * [[Panel.Flat.brushable]] panel chooses by dragging a rectangle.
  *
  * @param name
  *   what behaviors call it (a letter, then letters, digits, `_` or `-`)
  * @param labels
  *   what to write for each key, `labels(key)`, in the list under the panels and in the heading of a still picture
  * @param hover
  *   whether pointing at an item chooses it
  * @param click
  *   whether clicking an item pins it
  * @param listed
  *   whether the page lists the labels of the chosen keys under the panels
  * @param title
  *   what the page writes beside that list, and in the heading of a still picture; the name when empty
  */
final case class Selection(
  name: String,
  labels: Vector[String] = Vector.empty,
  hover: Boolean = true,
  click: Boolean = true,
  listed: Boolean = false,
  title: String = ""
):
  require(
    name.matches("[A-Za-z][A-Za-z0-9_-]*"),
    s"Selection: the name \"$name\" should be a letter and then letters, digits, _ or -"
  )

  /** What the page writes beside the list of the chosen keys. */
  def shownName: String = if title.isEmpty then name else title

  /** This selection written `text` beside its list. */
  def withTitle(text: String): Selection = copy(title = text)

  /** What to write for `key`: its label, or the number when there is none. */
  def labelOf(key: Int): String = labels.lift(key).getOrElse(key.toString)

  /** This selection with `texts` as the labels of keys 0, 1, 2, ... */
  def withLabels(texts: Seq[String]): Selection = copy(labels = texts.toVector)

  /** This selection listed under the panels. */
  def withList: Selection = copy(listed = true)

  /** This selection chosen only by clicking: pointing at an item does nothing. */
  def clickOnly: Selection = copy(hover = false, click = true)

  /** This selection chosen only by pointing at an item: clicking does nothing. */
  def hoverOnly: Selection = copy(hover = true, click = false)

object Selection:
  /** A selection called `name`, listed under the panels, with a label for each key. */
  def listing(name: String, labels: Seq[String]): Selection = Selection(name, labels.toVector, listed = true)

/** Where the controls, selections and views of an [[Interactive]] page are, for a still picture of it (`at`): the value
  * of each slider, and the keys chosen in each selection. What a viewer has done on the page, written down.
  *
  * {{{
  * page.at(PageState().value("t", 0.6).select("class", 3))
  * }}}
  *
  * @param values
  *   the sliders that are not at their starting values
  * @param selected
  *   the keys chosen in each selection (none by default)
  * @param windows
  *   the range of each zoomed panel, by the panel's number (none by default: every panel shows all of its picture)
  * @param brushes
  *   the rectangle dragged out in each brushable panel, by the panel's number (none by default)
  */
final case class PageState(
  values: Map[String, Double] = Map.empty,
  selected: Map[String, Set[Int]] = Map.empty,
  windows: Map[Int, Window] = Map.empty,
  brushes: Map[Int, Window] = Map.empty
):
  /** This state with the zoomable panel number `panel` (counting every panel of the page from 0) showing `window`. */
  def zoom(panel: Int, window: Window): PageState = copy(windows = windows.updated(panel, window))

  /** This state with the rectangle `window` (in the data's units) dragged out in the panel number `panel`, which has to
    * be brushable. It draws the rectangle only: what it touches is chosen with [[select]], as the page chooses it.
    */
  def brush(panel: Int, window: Window): PageState = copy(brushes = brushes.updated(panel, window))

  /** This state with the slider `name` at `v`. */
  def value(name: String, v: Double): PageState = copy(values = values.updated(name, v))

  /** This state with exactly `keys` chosen in the selection `name`. */
  def select(name: String, keys: Int*): PageState = copy(selected = selected.updated(name, keys.toSet))

  /** This state with exactly `keys` chosen in the selection `name`. */
  def select(name: String, keys: Set[Int]): PageState = copy(selected = selected.updated(name, keys))

  /** The keys chosen in the selection `name`. */
  def chosen(name: String): Set[Int] = selected.getOrElse(name, Set.empty)
