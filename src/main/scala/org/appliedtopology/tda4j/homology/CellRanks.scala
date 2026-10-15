package org.appliedtopology.tda4j

/** The cells of a stream, ranked once by the stream's own ordering, so that a reduction (which compares cells
  * constantly) compares two integers instead of asking the stream for the value of each cell. For a Vietoris-Rips
  * stream each of those questions is a hash lookup keyed by a `TreeSet`.
  *
  * `ordering` answers as `streamOrdering` does for every pair: the ranks are a sort by it (cells it calls equal share a
  * rank), looked up by object identity, and any cell that is not one of `cells` (or not the very object) is compared by
  * `streamOrdering` itself. `canonical` gives the stream's own object for a cell equal to it, which a cell fresh from
  * `boundary` needs before the identity lookup can find it.
  *
  * The ranks are used only when every cell has a filtration value (`inDomain`). `FiltrationOrdering.canonical` orders a
  * cell outside the stream's `filtrationValue` by dimension and tie-break alone, so with such a cell the ordering can
  * fail to be transitive, and a sort of it is not the ordering. Then `ordering` is `streamOrdering` and `canonical` the
  * identity.
  */
// private[tda4j]: a public top-level name joins every user's wildcard import and silently shadows their own definition (the
// flat namespace, CLAUDE.md "Openness principle", reason 3). It is machinery of the engines, not an entry point.
private[tda4j] final class CellRanks[CellT](
  cells: IndexedSeq[CellT],
  index: Map[CellT, Int],
  streamOrdering: Ordering[CellT],
  inDomain: CellT => Boolean
):
  /** Whether the ranks stand for the stream's ordering (every cell has a value). */
  val ranked: Boolean = cells.forall(inDomain)

  private val ranks: java.util.IdentityHashMap[CellT, Integer] =
    val map = new java.util.IdentityHashMap[CellT, Integer](2 * cells.size + 1)
    if ranked then
      var rank = 0
      var previous: Option[CellT] = None
      cells.sorted(using streamOrdering).foreach { cell =>
        previous.foreach(prev => if streamOrdering.compare(prev, cell) != 0 then rank += 1)
        map.put(cell, Integer.valueOf(rank))
        previous = Some(cell)
      }
    map

  /** The stream's ordering, by rank where both cells are the stream's own objects. */
  val ordering: Ordering[CellT] =
    if !ranked then streamOrdering
    else
      new Ordering[CellT]:
        def compare(x: CellT, y: CellT): Int =
          val rx = ranks.get(x)
          val ry = ranks.get(y)
          if rx != null && ry != null then Integer.compare(rx.intValue, ry.intValue)
          else streamOrdering.compare(x, y)

  /** The stream's own object for a cell equal to `cell`, else `cell`. */
  def canonical(cell: CellT): CellT =
    if !ranked then cell
    else
      index.get(cell) match
        case Some(i) => cells(i)
        case None    => cell
