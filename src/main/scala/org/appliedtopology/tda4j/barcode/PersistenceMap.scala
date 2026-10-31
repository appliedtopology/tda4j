package org.appliedtopology.tda4j

import scala.collection.mutable

/** The map on persistent homology that a map of filtered complexes induces, and its image, kernel and cokernel as
  * barcodes: what survives from the smaller space in the larger, what dies on the way in, and what only the larger one
  * sees.
  *
  * {{{
  * val sub = Persistence(points.take(30), maxDimension = 2, includeZeroLength = true)
  * val whole = Persistence(points, maxDimension = 2, includeZeroLength = true)
  * val inclusion = PersistenceMap.inclusion(sub, whole)
  * inclusion.image(1)      // the loops of the subsample, as far as they stay loops in the whole
  * inclusion.cokernel(1)   // the loops that only the whole sample has
  * }}}
  *
  * For each degree the matrix has a row for each bar of the target and a column for each bar of the source: column `i`
  * is the pushforward of the source bar's representative cycle, written in the target's bars at the time the source bar
  * is born. The bars are those of the diagrams without the zero-length ones (a class that never exists), and
  * [[sourceBars]] and [[targetBars]] give them in the order the matrix uses.
  *
  * The map must send each cell to a chain of cells that have already entered the target by the time the cell enters the
  * source (it preserves the filtration). Both diagrams need representative cycles and the cells of their bars (the
  * default of `Persistence`); the target must keep its zero-length bars, `includeZeroLength = true`, because their
  * representatives are part of what the reduction reads. They must be over the same field: the same `characteristic`
  * (or the same `coefficients`).
  *
  * @see
  *   [[MapAlgebra]] for the image, kernel and cokernel of a map of barcodes that you write down yourself
  */
// Not `open`: the constructor takes the matrices unchecked (invariant-bearing); the checked ways to make one are the
// `PersistenceMap(...)` constructors.
class PersistenceMap[S, T, C] private (
  val source: PersistenceDiagram.Of[S, C],
  val target: PersistenceDiagram.Of[T, C],
  private val degrees: Map[Int, PersistenceMap.Degree[S, T, C]]
):
  import PersistenceMap.*

  private given field: (C is Field) = source.coefficientField

  /** The top degree the map was computed in: the smaller of the two diagrams' `maxDimension`s. */
  def maxDimension: Int = degrees.keys.max

  /** The bars of the source in degree `k` that the matrix has a column for, in that order. */
  def sourceBars(k: Int): List[PersistenceBar[Double, Chain[S, C]]] = degree(k).sourceBars.toList

  /** The bars of the target in degree `k` that the matrix has a row for, in that order. */
  def targetBars(k: Int): List[PersistenceBar[Double, Chain[T, C]]] = degree(k).targetBars.toList

  /** The nonzero entries of the matrix in degree `k` as `(source bar, target bar, coefficient)`, the bars as positions
    * in [[sourceBars]] and [[targetBars]], by source bar and then target bar.
    */
  def entries(k: Int): List[(Int, Int, C)] =
    degree(k).columns.zipWithIndex.toList.flatMap((column, i) => column.toList.sortBy(_._1).map((j, c) => (i, j, c)))

  /** The bars of the image in degree `k`: the classes of the source that are not zero in the target, from the birth of
    * the class to the death of the component of the target it lands in.
    */
  def image(k: Int): List[PersistenceBar[Double, Nothing]] =
    barsOf(k, MapAlgebra.image(degree(k).sourceIntervals, degree(k).targetIntervals, degree(k).columns))

  /** The bars of the kernel in degree `k`: what the source has that the map sends to zero, at the time it does. */
  def kernel(k: Int): List[PersistenceBar[Double, Nothing]] =
    barsOf(k, MapAlgebra.kernel(degree(k).sourceIntervals, degree(k).targetIntervals, degree(k).columns))

  /** The bars of the cokernel in degree `k`: what the target has that does not come from the source. */
  def cokernel(k: Int): List[PersistenceBar[Double, Nothing]] =
    barsOf(k, MapAlgebra.cokernel(degree(k).sourceIntervals, degree(k).targetIntervals, degree(k).columns))

  private def degree(k: Int): Degree[S, T, C] =
    degrees.getOrElse(
      k,
      throw IllegalArgumentException(
        s"PersistenceMap: no degree $k; the map was computed in degrees 0 to $maxDimension"
      )
    )

  private def barsOf(k: Int, found: List[(Int, MapAlgebra.Interval)]): List[PersistenceBar[Double, Nothing]] =
    found.map((_, bar) =>
      new PersistenceBar[Double, Nothing](
        k,
        ClosedEndpoint(bar.birth),
        if bar.death.isInfinite then PositiveInfinity[Double]() else OpenEndpoint(bar.death)
      )
    )

  override def toString: String =
    val perDegree =
      (0 to maxDimension).map(k => s"H$k: ${degree(k).sourceBars.size} -> ${degree(k).targetBars.size} bars")
    s"PersistenceMap(${perDegree.mkString(", ")})"
end PersistenceMap

object PersistenceMap:

  /** One degree of the map: the bars as the matrix indexes them, and its columns, a column for each source bar from the
    * position of a target bar to the coefficient there.
    */
  // Private: the pieces of the matrix are read through `sourceBars`, `targetBars` and `entries`.
  private[PersistenceMap] final case class Degree[S, T, C](
    sourceBars: Vector[PersistenceBar[Double, Chain[S, C]]],
    targetBars: Vector[PersistenceBar[Double, Chain[T, C]]],
    columns: IndexedSeq[Map[Int, C]]
  ):
    def sourceIntervals: IndexedSeq[MapAlgebra.Interval] = sourceBars.map(b => MapAlgebra.Interval(b.birth, b.death))
    def targetIntervals: IndexedSeq[MapAlgebra.Interval] = targetBars.map(b => MapAlgebra.Interval(b.birth, b.death))

  /** The map induced by the map of complexes that sends the cell `c` of the source to the chain `cellMap(c)` of the
    * target, a list of cells with their integer coefficients (empty for a cell that goes to zero). It has to be a chain
    * map -- compatible with the boundaries -- and preserve the filtration.
    */
  def apply[S, T](source: PersistenceDiagram[S], target: PersistenceDiagram[T])(
    cellMap: S => Seq[(T, Int)]
  ): PersistenceMap[S, T, source.Coefficient] =
    type C = source.Coefficient
    given (C is Field) = source.coefficientField
    for (name, diagram) <- List("source" -> source, "target" -> target) do
      require(
        diagram.representatives != Some(Representatives.Cocycles),
        s"PersistenceMap: the $name diagram carries cocycles; compute it with `representatives = Representatives.Cycles`"
      )
      require(
        diagram.representativesDieWithTheirBars,
        s"PersistenceMap: the representatives of the $name diagram are not the cycles that the death cells of its bars bound, which the map is read from (the Naive and Chunks engines give others); compute it with the default engine, Engine.Cohomology, Engine.Ripser or Engine.ParallelRipser"
      )
    val (p, q) = (
      characteristicOf[C](using source.coefficientField),
      characteristicOf[target.Coefficient](using target.coefficientField)
    )
    require(
      p == q,
      s"PersistenceMap: the source is over ${describe(p)} and the target over ${describe(q)}; compute both diagrams with the same `characteristic`"
    )
    // Characteristic 0 is the reals and the rationals alike, which are different types: the cast below needs one field.
    require(
      p != 0 || source.coefficientField.getClass == target.coefficientField.getClass,
      "PersistenceMap: the source and the target are over different fields of characteristic 0 (the real and the rational numbers); compute both diagrams with the same `characteristic` or `coefficients`"
    )
    // The same prime, so the coefficients of the target are those of the source: `Z/p` is `Int` underneath whichever
    // `FiniteField` instance made it.
    val sameField = target.asInstanceOf[PersistenceDiagram.Of[T, C]]
    val top = math.min(source.maxDimension, target.maxDimension)
    new PersistenceMap[S, T, C](
      source,
      sameField,
      (0 to top).map(k => k -> degreeOf(k, source, sameField, cellMap)).toMap
    )

  /** The map of an inclusion: the cells of `sub` are cells of `whole`, which has them no later. */
  def inclusion[CellT](
    sub: PersistenceDiagram[CellT],
    whole: PersistenceDiagram[CellT]
  ): PersistenceMap[CellT, CellT, sub.Coefficient] =
    apply(sub, whole)(cell => Seq((cell, 1)))

  /** The map of simplicial complexes that sends the simplex on the vertices `v0 < ... < vk` to the simplex on
    * `vertexMap(v0), ..., vertexMap(vk)` -- with the sign of the permutation that sorts them, and to zero if two of
    * them agree. `vertexMap` has to preserve the filtration: each simplex of the source must have its image in the
    * target by the time it enters (for Vietoris-Rips complexes, a map that does not increase distances).
    */
  def simplicial[V, W: Ordering](
    source: PersistenceDiagram[Simplex[V]],
    target: PersistenceDiagram[Simplex[W]]
  )(vertexMap: V => W): PersistenceMap[Simplex[V], Simplex[W], source.Coefficient] =
    val order = summon[Ordering[W]]
    apply(source, target) { simplex =>
      val images = simplex.toList.map(vertexMap)
      val image = Simplex.from(images)
      if image.size < images.size then Seq.empty
      else
        val inversions =
          images.indices.iterator.map(i => (i + 1 until images.size).count(j => order.gt(images(i), images(j)))).sum
        Seq((image, if inversions % 2 == 0 then 1 else -1))
    }

  /** The smallest `n >= 1` with `n * 1 = 0` in the field, or 0 if there is none below the largest prime supported (the
    * real numbers).
    */
  private def characteristicOf[C](using fr: C is Field): Int =
    var n = 1
    var x = fr.one
    while n <= FiniteField.MaxPrime && !fr.isEqual(x, fr.zero) do
      x = fr.plus(x, fr.one)
      n += 1
    if fr.isEqual(x, fr.zero) then n else 0

  private def describe(characteristic: Int): String =
    if characteristic == 0 then "a field of characteristic 0" else s"Z/$characteristic"

  /** `n` in the field, the sum of `n` ones (or minus ones). */
  private def integer[C](n: Int)(using fr: C is Field): C =
    var magnitude = math.abs(n.toLong)
    var addend = if n >= 0 then fr.one else fr.negate(fr.one)
    var sum = fr.zero
    while magnitude > 0 do
      if (magnitude & 1L) != 0 then sum = fr.plus(sum, addend)
      magnitude = magnitude >>> 1
      if magnitude > 0 then addend = fr.plus(addend, addend)
    sum

  private def degreeOf[S, T, C](
    k: Int,
    source: PersistenceDiagram.Of[S, C],
    target: PersistenceDiagram.Of[T, C],
    cellMap: S => Seq[(T, Int)]
  )(using fr: C is Field): Degree[S, T, C] =
    val everyTarget = target.bars.filter(_.dim == k).toVector
    val targetBars = everyTarget.filterNot(_.isZeroLength)
    val moduleIndex = everyTarget.zipWithIndex.collect { case (b, j) if !b.isZeroLength => j }.zipWithIndex.toMap
    val sourceBars = source.bars.filter(b => b.dim == k && !b.isZeroLength).toVector

    val birthCells: Vector[T] = everyTarget.map { bar =>
      bar.birthCell.getOrElse(
        throw IllegalArgumentException(
          s"PersistenceMap: the target bar $bar does not say which cell opens it; compute the target with `Persistence(...)`, which records the cells"
        )
      )
    }
    val birthIndex: Map[T, Int] = birthCells.zipWithIndex.toMap
    val representatives: Vector[Map[T, C]] = everyTarget.map(_.representative.terms.toMap)

    // The birth cells in the support of each representative, the representative's own aside: the cells the reduction
    // has to clear after this one. Ranked so that a representative only contains birth cells of lower rank than its own.
    val needs: Vector[Vector[Int]] =
      representatives.indices
        .map(j => representatives(j).keysIterator.flatMap(birthIndex.get).filter(_ != j).toVector)
        .toVector
    val dependents = Array.fill(everyTarget.size)(mutable.ListBuffer.empty[Int])
    for (needed, j) <- needs.zipWithIndex; n <- needed do dependents(n) += j
    val waiting = needs.map(_.size).toArray
    val rank = new Array[Int](everyTarget.size)
    val ready = mutable.Queue.from(waiting.indices.filter(waiting(_) == 0))
    var ranked = 0
    while ready.nonEmpty do
      val j = ready.dequeue()
      ranked += 1
      for d <- dependents(j) do
        rank(d) = math.max(rank(d), rank(j) + 1)
        waiting(d) -= 1
        if waiting(d) == 0 then ready.enqueue(d)
    require(
      ranked == everyTarget.size,
      s"PersistenceMap: the representatives of the degree-$k bars of the target do not reduce one into the next, so the map cannot be read off them; compute the target with another `engine`"
    )

    val columns = sourceBars.map { bar =>
      val t = bar.birth
      // The pushforward of the source representative.
      val y = mutable.HashMap.empty[T, C]
      for (cell, coefficient) <- bar.representative.terms; (image, n) <- cellMap(cell) do
        val sum = fr.plus(y.getOrElse(image, fr.zero), fr.times(coefficient, integer(n)))
        if fr.isEqual(sum, fr.zero) then y.remove(image) else y(image) = sum
      // Clear the birth cells of the target one by one, the highest rank first. A bar alive at `t` is a class of the
      // target at `t`, and its coefficient is the entry; a bar that has died by `t` is a cycle that is a boundary
      // there, which changes nothing.
      val queue = mutable.PriorityQueue.empty[(Int, T)](using Ordering.by(_._1))
      for cell <- y.keysIterator; j <- birthIndex.get(cell) do queue.enqueue((rank(j), cell))
      val entry = mutable.HashMap.empty[Int, C]
      while queue.nonEmpty do
        val (_, cell) = queue.dequeue()
        for lead <- y.get(cell) do
          val j = birthIndex(cell)
          val bar = everyTarget(j)
          require(
            bar.birth <= t,
            s"PersistenceMap: a cycle of the source born at $t goes to a chain with a cell of the target that enters only at ${bar.birth}; the map does not preserve the filtration"
          )
          val factor = fr.divide(lead, representatives(j)(cell))
          for (c, v) <- representatives(j) do
            val updated = fr.minus(y.getOrElse(c, fr.zero), fr.times(factor, v))
            if fr.isEqual(updated, fr.zero) then y.remove(c)
            else
              y(c) = updated
              if c != cell then for other <- birthIndex.get(c) do queue.enqueue((rank(other), c))
          if bar.death > t then entry(moduleIndex(j)) = fr.plus(entry.getOrElse(moduleIndex(j), fr.zero), factor)
      require(
        y.isEmpty,
        s"PersistenceMap: the image of the source bar $bar in the target does not reduce to the bars of the target; compute the target with `includeZeroLength = true` and the same `characteristic`, and check that the map is a chain map"
      )
      entry.filterNot((_, c) => fr.isEqual(c, fr.zero)).toMap
    }
    val result = Degree(sourceBars, targetBars, columns)
    val (from, to) = (result.sourceIntervals, result.targetIntervals)
    for (column, i) <- columns.zipWithIndex; j <- column.keysIterator do
      require(
        MapAlgebra.isLegal(from(i), to(j)),
        s"PersistenceMap: the source bar ${sourceBars(i)} goes to the target bar ${targetBars(j)}, which is not a map of persistence modules; the map does not preserve the filtration"
      )
    result
end PersistenceMap
