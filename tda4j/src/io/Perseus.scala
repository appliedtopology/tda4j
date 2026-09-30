package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import scala.io.Source
import java.io.PrintWriter

/** Perseus's own cubical toplex ("dense grid") input format, and its per-dimension persistence-interval output format
  * -- verified against Perseus's own documentation page and cross-checked against GUDHI's own
  * `Bitmap_cubical_complex_base.h` reader source (GUDHI's cubical-complex module reads real Perseus files directly),
  * not reconstructed from memory. See `.claude/WORKLOG-io-module.md`.
  *
  * '''Axis order''': "lexicographic order" in Perseus's own sense has the FIRST declared axis fastest-varying --
  * confirmed from GUDHI's own `compute_position_in_bitmap`/multiplier construction (`multipliers[0] = 1`), the only
  * place either project's exact stride arithmetic is spelled out in source rather than prose. This is the OPPOSITE of
  * `CubicalImage.fromFlatArray`'s own last-axis-fastest convention -- the same reversal `Dipha.readImageData` already
  * needs, for the same underlying reason, and handled the same way here.
  *
  * '''Periodic boundaries''' (a negative grid size, GUDHI's own extension to this format) are not representable by
  * `CubicalGridStream` and are rejected outright with a clear error rather than silently misinterpreted.
  *
  * '''Missing cubes''': Perseus reserves filtration value `-1` for a cube absent from the complex entirely --
  * `CubicalGridStream` has no "absent cell" concept, so `-1` is mapped to `Double.PositiveInfinity` (GUDHI's own
  * documented convention for the same gap), which has the intended effect: the cell exists in the stream but is never
  * reached by any finite-threshold computation.
  *
  * '''Perseus's own simplicial toplex format is deliberately not implemented here''' -- no primary-source verification
  * of it was done (see `.claude/WORKLOG-io-module.md`), and a wrong parser for it would be worse than none.
  */
object Perseus:

  /** Line 1: dimension `d`. Next `d` lines: grid size along each axis (all positive -- see the class doc on periodic
    * boundaries). Remaining tokens: `d`-many products worth of filtration values, in Perseus's own lexicographic
    * (first-axis-fastest) order; `-1` means "this cube is absent." Returns the raw `(shape, flatValues)` pair in
    * `CubicalImage.fromFlatArray`'s own last-axis-fastest convention (axes already reversed from Perseus's own order,
    * `-1` already mapped to `Double.PositiveInfinity`) -- mirrors `Dipha.readImageData`'s own raw-array shape, so a
    * caller that only needs the raw grid (not an already-built stream, e.g. to round-trip it through a different
    * loader) isn't forced to build a `CubicalGridStream` just to immediately flatten it back out.
    */
  def readCubicalImageData(path: String): (IndexedSeq[Int], IndexedSeq[Double]) =
    val src = Source.fromFile(path)
    try
      val tokens = src.getLines().flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)
      val d = tokens.next().toInt
      val sizes = new Array[Int](d)
      var i = 0
      while i < d do
        sizes(i) = tokens.next().toInt
        i += 1
      require(
        sizes.forall(_ > 0),
        s"periodic boundaries (a negative grid size) are not supported by CubicalGridStream: sizes = ${sizes.toSeq}"
      )
      val n = sizes.product
      val raw = new Array[Double](n)
      i = 0
      while i < n do
        val v = tokens.next().toDouble
        raw(i) = if v == -1.0 then Double.PositiveInfinity else v
        i += 1
      (sizes.reverse.toIndexedSeq, raw.toIndexedSeq)
    finally src.close()

  def readCubicalToplex(path: String, sublevel: Boolean = true): CubicalGridStream =
    val (shape, flatValues) = readCubicalImageData(path)
    CubicalImage.fromFlatArray(shape, flatValues, sublevel)

  /** `shape`/`flatValues` in `CubicalImage.fromFlatArray`'s own last-axis-fastest convention -- reversed internally to
    * Perseus's own first-axis-fastest convention before writing. `Double.PositiveInfinity` round-trips back to `-1`.
    */
  def writeCubicalToplex(path: String, shape: IndexedSeq[Int], flatValues: IndexedSeq[Double]): Unit =
    require(flatValues.size == shape.product, "flatValues size must equal the product of shape")
    val sizes = shape.reverse
    val out = new PrintWriter(path)
    try
      out.println(shape.size)
      sizes.foreach(out.println)
      flatValues.foreach(v => out.println(if v == Double.PositiveInfinity then "-1" else v.toString))
    finally out.close()

  private def endpointToStep(e: BarcodeEndpoint[Double]): Long = e match
    case ClosedEndpoint(v)  => math.round(v)
    case OpenEndpoint(v)    => math.round(v)
    case NegativeInfinity() =>
      throw new IllegalArgumentException("cannot write a -infinity endpoint in Perseus's integer-step format")
    case PositiveInfinity() =>
      throw new IllegalArgumentException("a +infinity endpoint should already have been handled as essential (-1)")

  /** Perseus's own output convention: one file per dimension (`<pathPrefix>_<dim>.txt`), two whitespace-separated
    * integer columns per line, `birth death` -- a `death` of `-1` means the class is essential. Perseus's own
    * birth/death values are filtration STEP indices (integers), not raw filtration values -- this writer rounds
    * whatever `Double`s it's given to their nearest integer; callers wanting genuine step-index semantics should
    * convert their own filtration values to step indices first.
    */
  def writePersistenceIntervals(pathPrefix: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    bars.groupBy(_.dim).foreach { case (dim, dimBars) =>
      val out = new PrintWriter(s"${pathPrefix}_$dim.txt")
      try
        dimBars.foreach { bar =>
          val birth = endpointToStep(bar.lower)
          val death = bar.upper match
            case PositiveInfinity() => -1L
            case other              => endpointToStep(other)
          out.println(s"$birth $death")
        }
      finally out.close()
    }

  /** Reads one Perseus output file (`<...>_<dim>.txt`) back into bars of the given dimension -- `dim` must be supplied
    * by the caller, since Perseus's own per-dimension output files don't repeat it inside the file.
    */
  def readPersistenceIntervals(path: String, dim: Int): Seq[PersistenceBar[Double, Nothing]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { line =>
          val fields = line.split("\\s+")
          require(fields.length == 2, s"expected 2 fields (birth,death), got ${fields.length} in line '$line'")
          val birth = fields(0).toDouble
          if fields(1) == "-1" then PersistenceBar[Double](dim, birth)
          else PersistenceBar[Double](dim, birth, fields(1).toDouble)
        }
        .toSeq
    finally src.close()
