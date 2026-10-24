package org.appliedtopology.tda4j

import scala.collection.mutable

/** The cycles of the bars of a [[ParallelRipserRun]], by involuted persistent homology ([[Involution]]) on the
  * simplices' keys, with the shortcut of `CellularCohomologyEngine`: a death simplex whose youngest face is its birth
  * simplex needs no reduction (its boundary is already the reduced column), so only the other death columns are
  * reduced, one death dimension to a thread.
  */
// Restricted: a public top-level `ParallelRipserCycles` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object ParallelRipserCycles:
  /** The cycle of bar `i` of `run.bars`, as terms over the simplices' keys, at index `i`. The run must hold its pairs.
    */
  def cycles[C: Field as fr](run: ParallelRipserRun, one: C, minusOne: C): IndexedSeq[Seq[(Long, C)]] =
    given youngestFirst: Ordering[Long] = Ordering.Long.reverse
    val chainRM = summon[Chain[Long, C] is RingModule]
    import chainRM.*
    val pairs = run.pairs
    val topDim = pairs.iterator.map(_._1).maxOption.getOrElse(0) + 1
    // pairedBirth(d)(t): the birth paired with death simplex t of dimension d; deathOf(d)(s): the death paired with
    // birth s of dimension d.
    val pairedBirth = Array.fill(topDim + 1)(mutable.LongMap.empty[Long])
    val deathOf = Array.fill(topDim + 1)(mutable.LongMap.empty[Long])
    for (dim, birth, death) <- pairs if death >= 0 do
      pairedBirth(dim + 1)(death) = birth
      deathOf(dim)(birth) = death

    def boundary(key: Long, dim: Int): Seq[(Long, C)] =
      run.faces(key, dim).toSeq.zipWithIndex.map((face, i) => (face, if i % 2 == 0 then one else minusOne))

    // One death dimension's tables: the columns that needed a reduction (reduced and V-column, under their birth
    // simplex); `processed`: death simplices of this dimension below it have been reduced.
    final class Dimension(val d: Int):
      val basis = mutable.Map.empty[Long, Chain[Long, C]]
      val generators = mutable.Map.empty[Long, Chain[Long, C]]
      var processed = Long.MinValue

      // Keys of a dimension ascend with age, so the youngest face is the one with the largest key (`Ordering.Long`
      // explicitly: the reversed order in scope is the chains').
      def youngestFace(tau: Long): Long = run.faces(tau, d).max(using Ordering.Long)

      def trivialDeath(sigma: Long): Long =
        val tau = deathOf(d - 1).getOrElse(sigma, -1L)
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

      def reduceDeaths(): Unit =
        for tau <- pairedBirth(d).keys.toArray.sorted(using Ordering.Long)
        do // oldest death first, not the chains' order
          val sigma = pairedBirth(d)(tau)
          processed = tau
          if youngestFace(tau) != sigma then
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
