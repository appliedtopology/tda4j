package org.appliedtopology.tda4j

import scala.collection.mutable

/** The cycles of the bars of a [[ParallelRipserRun]], by involuted persistent homology ([[Involution]]) on the
  * simplices' keys, with the shortcut of `CellularCohomologyEngine`: a death simplex whose youngest face is its birth
  * simplex needs no reduction (its boundary is already the reduced column), so only the other death columns are
  * reduced, one death dimension to a thread (the scan for them is parallel over the death simplices).
  */
// Restricted: a public top-level `ParallelRipserCycles` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object ParallelRipserCycles:
  private val ScanGrain = 4096

  /** The cycle of bar `i` of `run.bars`, as terms over the simplices' keys, at index `i`. The run must hold its pairs.
    */
  def cycles[C: Field as fr](run: ParallelRipserRun, one: C, minusOne: C): IndexedSeq[Seq[(Long, C)]] =
    given youngestFirst: Ordering[Long] = Ordering.Long.reverse
    val chainRM = summon[Chain[Long, C] is RingModule]
    import chainRM.*
    val pairs = run.pairs
    val topDim = pairs.iterator.map(_._1).maxOption.getOrElse(0) + 1
    // pairOfBirth(d)(s): the index in `pairs` of the pair whose birth is s, of dimension d; deathsOf(d): the indices of
    // the pairs whose death simplex has dimension d.
    val pairOfBirth = Array.fill(topDim + 1)(LongIntMap(pairs.length / (topDim + 1)))
    val deathsOf: Array[Array[Int]] =
      val counts = new Array[Int](topDim + 1)
      for (dim, _, death) <- pairs if death >= 0 do counts(dim + 1) += 1
      val indices = Array.tabulate(topDim + 1)(d => new Array[Int](counts(d)))
      val at = new Array[Int](topDim + 1)
      var i = 0
      while i < pairs.length do
        val (dim, _, death) = pairs(i)
        if death >= 0 then
          indices(dim + 1)(at(dim + 1)) = i
          at(dim + 1) += 1
        i += 1
      indices

    def boundary(key: Long, dim: Int): Seq[(Long, C)] =
      run.faces(key, dim).toSeq.zipWithIndex.map((face, i) => (face, if i % 2 == 0 then one else minusOne))

    // One death dimension's tables: the columns that needed a reduction (reduced and V-column, under their birth
    // simplex); `processed`: death simplices of this dimension below it have been reduced.
    final class Dimension(val d: Int):
      val basis = mutable.Map.empty[Long, Chain[Long, C]]
      val generators = mutable.Map.empty[Long, Chain[Long, C]]
      var processed = Long.MinValue
      // (birth, death) of the death simplices that need a reduction, oldest death first.
      var hard = Array.empty[(Long, Long)]

      // Keys of a dimension ascend with age, so the youngest face is the one with the largest key (`Ordering.Long`
      // explicitly: the reversed order in scope is the chains').
      def youngestFace(tau: Long): Long = run.faces(tau, d).max(using Ordering.Long)

      def trivialDeath(sigma: Long): Long =
        val tau = if pairOfBirth(d - 1).contains(sigma) then pairs(pairOfBirth(d - 1)(sigma))._3 else -1L
        if tau >= 0 && tau < processed && youngestFace(tau) == sigma then tau else -1L

      def unreduced(tau: Long): Chain[Long, C] =
        val c = Chain.from(boundary(tau, d))
        c.collapseAll()
        c

      def reduce(z: Chain[Long, C], seed: Long): (Chain[Long, C], Chain[Long, C]) =
        def fallback(sigma: Long): Option[Chain[Long, C]] =
          val tau = trivialDeath(sigma)
          Option.when(tau >= 0)(unreduced(tau))
        val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty, fallback)
        val vcol = log.rawEntries.foldLeft(Chain(seed)) { case (acc, (pivot, coeff)) =>
          acc - coeff ⊠ generators.getOrElse(
            pivot, {
              val tau = trivialDeath(pivot)
              if tau < 0 then
                throw new IllegalStateException(s"Involution: pivot $pivot has a reduced column but no V-column")
              val g = Chain[Long, C](tau)
              g.collapseAll()
              g
            }
          )
        }
        vcol.collapseAll()
        reduced.collapseAll()
        (reduced, vcol)

      // A parallel scan for the death simplices that need a reduction (the pure `faces` of each death is most of the
      // work); the reductions themselves share the tables and run in order.
      def scan(): Unit =
        val here = deathsOf(d)
        val needsReduction = new Array[Boolean](here.length)
        Parallel.ranges(here.length, ScanGrain) { (from, until) =>
          var i = from
          while i < until do
            val (_, birth, death) = pairs(here(i))
            needsReduction(i) = youngestFace(death) != birth
            i += 1
        }
        hard = here.indices.filter(needsReduction(_)).map(i => (pairs(here(i))._3, pairs(here(i))._2)).toArray
        java.util.Arrays.sort(hard, (x, y) => java.lang.Long.compare(x._1, y._1))

      def reduceDeaths(): Unit =
        for (tau, sigma) <- hard do
          processed = tau
          val (reduced, vcol) = reduce(Chain.from(boundary(tau, d)), tau)
          reduced.leadingCell match
            case Some(pivot) if pivot == sigma =>
              basis(sigma) = reduced
              generators(sigma) = vcol
            case other =>
              throw new IllegalStateException(
                s"Involution: the boundary of $tau reduces to pivot $other, not to its paired birth cell $sigma " +
                  "(the pairing and the order disagree)"
              )
        processed = Long.MaxValue

    val dimensions = (1 to topDim).map(Dimension(_)).toVector
    Parallel.both(
      Parallel.foreach(dimensions)(_.scan()),
      for ((dim, birth, death), i) <- pairs.zipWithIndex if death >= 0 do pairOfBirth(dim)(birth) = i
    )
    Parallel.foreach(dimensions)(_.reduceDeaths())

    run.bars.indices.map { i =>
      val (dim, birth, death) = (run.bars(i)._1, pairs(run.pairOfBar(i))._2, pairs(run.pairOfBar(i))._3)
      if death >= 0 then
        dimensions(dim).basis.get(birth) match // death dimension dim + 1 is `dimensions(dim)`
          case Some(reduced) => reduced.terms
          case None          => boundary(death, dim + 1).sortBy(_._1)(using youngestFirst)
      else if dim == 0 then Seq((birth, one))
      else
        val (reduced, vcol) = dimensions(dim - 1).reduce(Chain.from(boundary(birth, dim)), birth)
        if !reduced.isZero() then
          throw new IllegalStateException(
            s"Involution: the boundary of the essential cell $birth does not reduce to zero"
          )
        vcol.terms
    }
