package org.appliedtopology.tda4j

import scala.collection.mutable

/** Experimental: [[CellularCohomologyEngine]]'s algorithm on dense cell numbers. Each dimension's cells are numbered
  * `0, 1, 2, ...` in the engine's own order (oldest first), and coboundaries are precomputed as arrays of those numbers,
  * so the reduction compares and hashes `Int`s instead of cells. Same bars and same representatives as the general
  * engine, term for term. For measurement only; not wired into `Persistence`.
  */
private[tda4j] class DenseCohomologyEngine[CellT: OrderedCell, CoefficientT: Field]:

  /** Wall-clock nanoseconds of the last run's numbering and coboundary precompute, and of its reduction. */
  var lastPrecomputeNanos: Long = 0L
  var lastReductionNanos: Long = 0L

  def persistentCohomology(
    stream: StratifiedCellStream[CellT, Double],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] =
    val t0 = System.nanoTime()
    val fv = stream.filtrationValue
    def cellFv(c: CellT): Double = fv.applyOrElse(c, (_: CellT) => Double.NegativeInfinity)
    // The general engine's order: ascending value, then the stream's own ordering.
    val olderFirst: Ordering[CellT] = Ordering.by[CellT, Double](cellFv).orElse(stream.filtrationOrdering)

    val cellsByDim: IndexedSeq[Vector[CellT]] =
      val grouped = stream.iterator.toVector.groupBy(_.dim)
      if grouped.isEmpty then IndexedSeq.empty
      else (0 to grouped.keys.max(using Ordering.Int)).map(d => grouped.getOrElse(d, Vector.empty).sorted(using olderFirst))
    val valuesByDim: IndexedSeq[Array[Double]] = cellsByDim.map(_.map(cellFv).toArray)
    val idByDim: IndexedSeq[mutable.HashMap[CellT, Int]] = cellsByDim.map { cells =>
      val m = new mutable.HashMap[CellT, Int](cells.length * 2, mutable.HashMap.defaultLoadFactor)
      cells.indices.foreach(i => m(cells(i)) = i)
      m
    }
    // coboundary(d)(i): the (cofacet id, coefficient) pairs of cell i of dimension d.
    val coboundary: IndexedSeq[Array[mutable.ArrayBuffer[(Int, CoefficientT)]]] =
      cellsByDim.indices.map { d =>
        val table = Array.fill(cellsByDim(d).length)(mutable.ArrayBuffer.empty[(Int, CoefficientT)])
        if d + 1 < cellsByDim.length then
          val up = cellsByDim(d + 1)
          up.indices.foreach { j =>
            up(j).boundary[CoefficientT].foreach { case (face, c) => table(idByDim(d)(face)) += ((j, c)) }
          }
        table
      }
    val t1 = System.nanoTime()
    lastPrecomputeNanos = t1 - t0

    val idOrder: Ordering[Int] = Ordering.Int // ascending id = oldest first = a coboundary column's leading term
    given Ordering[Int] = idOrder
    val fr = summon[CoefficientT is Field]

    val bars = mutable.ArrayDeque.empty[PersistenceBar[Double, Chain[Int, CoefficientT]]]
    val barDims = mutable.ArrayDeque.empty[Int]
    var cleared = Array.emptyBooleanArray
    for d <- cellsByDim.indices do
      val n = cellsByDim(d).length
      val clearedHere = if cleared.length == n then cleared else Array.fill(n)(false)
      val nextCleared = Array.fill(if d + 1 < cellsByDim.length then cellsByDim(d + 1).length else 0)(false)
      val basis = mutable.HashMap.empty[Int, Chain[Int, CoefficientT]]
      val generators = mutable.HashMap.empty[Int, Chain[Int, CoefficientT]]
      var i = n - 1
      while i >= 0 do // youngest first
        if !clearedHere(i) then
          val z = Chain.from(coboundary(d)(i).toSeq)(using idOrder)
          val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty[Int, CoefficientT](using idOrder))(using idOrder)
          val acc = mutable.HashMap(i -> fr.one)
          log.rawEntries.foreach { case (pivot, coeff) =>
            generators(pivot).terms.foreach { case (cell, c) =>
              acc(cell) = fr.minus(acc.getOrElse(cell, fr.zero), fr.times(coeff, c))
            }
          }
          val vcol = Chain.from(acc.toSeq.filterNot((_, c) => fr.isEqual(c, fr.zero)))(using idOrder)
          val birth = valuesByDim(d)(i)
          if reduced.isZero() then bars.append(PersistenceBar(d, ClosedEndpoint(birth), PositiveInfinity(), Some(vcol)))
          else
            val pivot = reduced.leadingCell.get
            basis(pivot) = reduced
            generators(pivot) = vcol
            nextCleared(pivot) = true
            bars.append(
              PersistenceBar(d, ClosedEndpoint(birth), OpenEndpoint(valuesByDim(d + 1)(pivot)), Some(vcol))
            )
          barDims.append(d)
        i -= 1
      cleared = nextCleared
    lastReductionNanos = System.nanoTime() - t1

    // Back to cells, in the general engine's own ordering.
    val out: List[PersistenceBar[Double, Chain[CellT, CoefficientT]]] = bars.zip(barDims).toList.map { case (bar, d) =>
      val chain = Chain.from(bar.representative.terms.map((id, c) => (cellsByDim(d)(id), c)))(using olderFirst)
      new PersistenceBar(bar.dim, bar.lower, bar.upper, Some(chain))
    }
    PersistenceBar.dropZeroLength(out, includeZeroLength)
