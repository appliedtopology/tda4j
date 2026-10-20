package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** Where a panel of a [[Board]] sits on its [[Layout]]: the row and column of its top left cell (counted from 0) and
  * how many rows and columns it spans. A panel that spans cells is as large as those cells and the gaps between them,
  * so its axes run across all of them.
  */
final case class Slot(row: Int, col: Int, rowSpan: Int = 1, colSpan: Int = 1):
  require(row >= 0 && col >= 0, s"Slot: rows and columns count from 0, not row $row, column $col")
  require(
    rowSpan >= 1 && colSpan >= 1,
    s"Slot: a panel spans at least one row and one column, not $rowSpan by $colSpan"
  )

  /** The row after the last one the panel covers. */
  def rowEnd: Int = row + rowSpan

  /** The column after the last one the panel covers. */
  def colEnd: Int = col + colSpan

  /** Whether the two panels cover a cell in common. */
  def overlaps(that: Slot): Boolean =
    row < that.rowEnd && that.row < rowEnd && col < that.colEnd && that.col < colEnd

  override def toString: String =
    val rows = if rowSpan == 1 then s"row $row" else s"rows $row-${rowEnd - 1}"
    val cols = if colSpan == 1 then s"column $col" else s"columns $col-${colEnd - 1}"
    s"$rows, $cols"

/** A grid of `rows` by `cols` cells that a [[Board]] fills with panels, in a drawing `width` by `height` pixels with
  * `gap` pixels between cells. The columns share the width in proportion to `widths` (equal when empty) and the rows
  * the height in proportion to `heights`; a weight is a relative size, `Vector(1, 3)` makes the second column three
  * times as wide as the first.
  */
final case class Layout(
  rows: Int,
  cols: Int,
  width: Int = 960,
  height: Int = 600,
  gap: Int = 12,
  widths: Vector[Double] = Vector.empty,
  heights: Vector[Double] = Vector.empty
):
  require(rows >= 1 && cols >= 1, s"Layout: a layout has at least one row and one column, not $rows by $cols")
  require(rows <= 50 && cols <= 50, s"Layout: $rows by $cols cells is more than a plot can show (50 at most each way)")
  require(width >= 100 && width <= 20000, s"Layout: the width should be between 100 and 20000 pixels, not $width")
  require(height >= 100 && height <= 20000, s"Layout: the height should be between 100 and 20000 pixels, not $height")
  require(gap >= 0 && gap <= 200, s"Layout: the gap between cells should be between 0 and 200 pixels, not $gap")
  require(
    widths.isEmpty || widths.size == cols,
    s"Layout: ${widths.size} column weights for $cols columns; give one for each, or none for equal columns"
  )
  require(
    heights.isEmpty || heights.size == rows,
    s"Layout: ${heights.size} row weights for $rows rows; give one for each, or none for equal rows"
  )
  require(
    (widths ++ heights).forall(w => w > 0 && w.isFinite),
    "Layout: a row or column weight is a relative size, a positive number"
  )

  /** The pixels of each track, the weights' shares of what the gaps leave, rounded so that they add up exactly. */
  private def tracks(count: Int, total: Int, weights: Vector[Double], what: String): Vector[Int] =
    val shares = if weights.isEmpty then Vector.fill(count)(1.0) else weights
    val available = total - gap * (count - 1)
    val edge = shares.scanLeft(0.0)(_ + _)
    val px = Vector.tabulate(count)(i =>
      math.round(edge(i + 1) / edge.last * available).toInt - math.round(edge(i) / edge.last * available).toInt
    )
    require(
      px.min >= 50,
      s"Layout: a $what would be ${px.min} pixels, too small to draw in; make the layout larger or give it fewer ${what}s"
    )
    px

  /** The width in pixels of each column. */
  val columnWidths: Vector[Int] = tracks(cols, width, widths, "column")

  /** The height in pixels of each row. */
  val rowHeights: Vector[Int] = tracks(rows, height, heights, "row")

  /** Where `slot` is drawn: its left, top, width and height in pixels. */
  def box(slot: Slot): (Int, Int, Int, Int) =
    require(
      slot.rowEnd <= rows && slot.colEnd <= cols,
      s"Layout: the panel at ${slot} does not fit a layout of $rows rows by $cols columns"
    )
    (
      columnWidths.take(slot.col).sum + gap * slot.col,
      rowHeights.take(slot.row).sum + gap * slot.row,
      columnWidths.slice(slot.col, slot.colEnd).sum + gap * (slot.colSpan - 1),
      rowHeights.slice(slot.row, slot.rowEnd).sum + gap * (slot.rowSpan - 1)
    )
