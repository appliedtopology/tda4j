package org.appliedtopology.tda4j

import scala.collection.mutable

/** Harmonic representatives of the bars of the ORDINARY barcode (Basu and Cox, "Harmonic persistent homology"): each
  * bar `[s, t)` gets the harmonic cycle at its birth value `s`, a chain that is a cycle and orthogonal to every
  * boundary of the complex at `s`, picked out by the persistence module alone. A bar is a class born at `s` that dies
  * at `t`, which is a quotient of subspaces of the harmonic chains `H_p(K_s)` (the classes born by `s` and dying by
  * `t`, modulo those dying before `t`), and its harmonic representative is that quotient written as the orthogonal
  * complement of the one inside the other. So the representative depends on the filtration only through its values:
  * unlike the [[HarmonicChainBarcode]], it does not depend on how cells of equal value are ordered.
  *
  * {{{
  * val rips = VietorisRips(points, maxDimension = 1)
  * val diagram = HarmonicRepresentatives(rips, maxDimension = 1)
  * diagram.longest.get.representative   // the harmonic cycle of the longest bar, as a chain at its birth value
  * }}}
  *
  * The bars are those of [[Persistence]] for the same stream (in the coefficients given: reals, the default, or the
  * exact `Coefficients.Rationals`; harmonic chains need an inner product, so a finite field is refused), with the
  * representative replaced. A bar of multiplicity one has a harmonic subspace of dimension one, so its chain is unique
  * up to a scalar: it is scaled to have 1 at its first cell in the chain order. A group of `m` bars with the same
  * endpoints has an `m`-dimensional subspace, and its bars get an orthogonal basis of it (which basis is not canonical,
  * the subspace is). By their Theorem 4.8, Basu and Cox show that among all cycles representing a bar the harmonic one
  * puts the most weight on the cells essential to the bar.
  *
  * The chains are computed in coordinates of the harmonic chains that the [[HarmonicChainBarcode]] run keeps alive at
  * the values involved, with a small linear system for each bar (its size is the Betti number at the birth and the
  * death), so the cost is that of the barcode run plus a Betti-number-sized elimination per bar.
  */
object HarmonicRepresentatives:

  /** The ordinary barcode of `stream` in degrees `0..maxDimension` (default as for [[Persistence]]), each bar with the
    * Basu-Cox harmonic cycle of its birth as its representative.
    */
  def apply[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Optional[Int] = Optional.empty,
    coefficients: Optional[Coefficients] = Optional.empty
  ): PersistenceDiagram[CellT] =
    val degree = HarmonicChainBarcode.degreeFor(stream, maxDimension)
    val field = HarmonicChainBarcode.zeroCharacteristic(coefficients)
    val ordinary = Persistence(
      stream,
      maxDimension = degree,
      coefficients = field,
      engine = Persistence.Engine.Cohomology
    )
    ofDiagram(stream, ordinary, field)

  /** The bars of `diagram` (which must be the barcode of `stream` in `coefficients`, from any engine: only the
    * intervals and cells are read, and the numbers of bars with each interval are checked against the harmonic
    * subspaces) with their representatives replaced by the harmonic cycles at their births.
    */
  def ofDiagram[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    diagram: PersistenceDiagram[CellT],
    coefficients: Coefficients
  ): PersistenceDiagram[CellT] =
    HarmonicChainBarcode.zeroCharacteristic(Optional(Some(coefficients)))
    import coefficients.given
    val degree = diagram.maxDimension
    val order = HarmonicChainBarcode.trackedOrder(HarmonicChainBarcode.insertionOrder(stream, degree), degree)
    val values = order.map(_._2).distinct
    val levelOf = values.zipWithIndex.toMap
    def level(value: Double, what: String): Int =
      levelOf.getOrElse(
        value,
        throw new IllegalArgumentException(
          s"HarmonicRepresentatives: the $what $value of a bar is not a filtration value of the stream; " +
            "the diagram must be the barcode of this stream"
        )
      )
    val groups = diagram.bars.groupBy(bar => (bar.dim, bar.birth, bar.death))
    val wanted = groups.keys.flatMap { (_, birth, death) =>
      val (j, k) = (level(birth, "birth"), if death.isPosInfinity then values.size - 1 else level(death, "death"))
      Seq(j, j - 1, k, k - 1).filter(_ >= 0).map(values)
    }.toSet
    val run = new HarmonicChainBarcode.Run[CellT, coefficients.C](degree, order, includeZeroLength = true, wanted)
    run.execute()
    val solver = new Solver[coefficients.C]
    val chains = groups.map { case (key @ (p, birth, death), bars) =>
      val j = levelOf(birth)
      val k = if death.isPosInfinity then None else Some(levelOf(death))
      def basisAt(level: Int): Vector[mutable.HashMap[Int, coefficients.C]] =
        if level < 0 then Vector.empty else run.snapshots((p, values(level)))
      val atBirth = basisAt(j)
      val before = basisAt(j - 1)
      // M^{s,t'}: the harmonic chains at s that, at t', are the image of a class born before s
      def killedBy(level: Int): Vector[Vector[coefficients.C]] =
        val atLevel = basisAt(level)
        solver.preimageOfImage(atBirth, before, atLevel)
      val (larger, smaller) = k match
        case Some(death) => (killedBy(death), killedBy(death - 1))
        case None        => (solver.unitVectors(atBirth.size), killedBy(values.size - 1))
      val subspace = solver.complement(atBirth, larger, smaller)
      if subspace.size != bars.size then
        throw new IllegalStateException(
          s"HarmonicRepresentatives: ${bars.size} bar(s) of degree $p over [$birth, $death) in the diagram, but the " +
            s"harmonic subspace has dimension ${subspace.size}; the diagram must be the barcode of this stream in these " +
            "coefficients (with real coefficients, a tolerance problem is also possible: try Coefficients.Rationals)"
        )
      val vectors = subspace.map(coordinates =>
        val sum = new run.Vec
        for (x, h) <- coordinates.zip(atBirth) do
          for (cell, y) <- h do
            val updated = summon[coefficients.C is Field].plus(
              sum.getOrElse(cell, summon[coefficients.C is Field].zero),
              summon[coefficients.C is Field].times(x, y)
            )
            sum.update(cell, updated)
        scaled(run, sum)
      )
      key -> vectors.map(run.chainOf)
    }
    val used = mutable.HashMap.empty[(Int, Double, Double), Int]
    val bars = diagram.bars.map { bar =>
      val key = (bar.dim, bar.birth, bar.death)
      val position = used.getOrElse(key, 0)
      used(key) = position + 1
      new PersistenceBar[Double, Chain[CellT, coefficients.C]](
        bar.dim,
        bar.lower,
        bar.upper,
        Some(chains(key)(position)),
        bar.cells
      )
    }
    PersistenceDiagram[CellT, coefficients.C](
      bars,
      diagram.maxDimension,
      diagram.lastFiltrationValue,
      diagram.scale,
      representatives = Some(Representatives.Cycles)
    )

  // `sum` scaled so that its first cell in the cells' own order has coefficient 1, zeros dropped.
  private def scaled[CellT, F](run: HarmonicChainBarcode.Run[CellT, F], sum: mutable.HashMap[Int, F])(using
    cells: CellT is OrderedCell,
    fr: F is Field
  ): mutable.HashMap[Int, F] =
    val nonzero = sum.filterNot((_, x) => fr.isEqual(x, fr.zero))
    val first = nonzero.keys.minBy(id => run.order(id)._1)(using cells.ordering)
    val factor = fr.invert(nonzero(first))
    mutable.HashMap.from(nonzero.map((id, x) => id -> fr.times(x, factor)))

  // Small linear algebra over F on coordinates in a basis of harmonic chains at one value (a few dozen at most).
  private final class Solver[F](using fr: F is Field):
    private type Vec = collection.Map[Int, F]

    private def dot(a: Vec, b: Vec): F =
      val (small, large) = if a.size <= b.size then (a, b) else (b, a)
      small.foldLeft(fr.zero) { case (sum, (k, x)) => large.get(k).fold(sum)(y => fr.plus(sum, fr.times(x, y))) }

    def unitVectors(n: Int): Vector[Vector[F]] =
      Vector.tabulate(n)(i => Vector.tabulate(n)(j => if i == j then fr.one else fr.zero))

    private def gram(basis: Vector[Vec]): Vector[Vector[F]] = basis.map(a => basis.map(b => dot(a, b)))

    /** The matrix, with one column for each chain of `from` and one row for each chain `u` of `to` (a basis of a
      * harmonic space), of the inner products `<u, h>`. For `h` a cycle these are the coordinates, multiplied by the
      * Gram matrix of `to` (invertible), of the orthogonal projection of `h` onto the span of `to`, which is the map
      * from the harmonic chains at one value to those at a later one. Questions about it that are invariant under an
      * invertible change of coordinates on the target (is this image inside that one) need nothing more.
      */
    private def projection(from: Vector[Vec], to: Vector[Vec]): Vector[Vector[F]] =
      to.map(u => from.map(h => dot(u, h)))

    private def rowSpace(vectors: Seq[Vector[F]]): Vector[Vector[F]] =
      if vectors.isEmpty then Vector.empty else LinearAlgebra.rref[F](vectors)._1

    /** The `x` (coordinates in `atBirth`) whose image at `atLevel` is the image of a class born before: `M^{s,t}`. */
    def preimageOfImage(atBirth: Vector[Vec], before: Vector[Vec], atLevel: Vector[Vec]): Vector[Vector[F]] =
      if atBirth.isEmpty then Vector.empty
      else
        val image = projection(atBirth, atLevel)
        val earlier = projection(before, atLevel)
        val rows = image.indices.map(i => image(i) ++ earlier(i).map(fr.negate))
        val kernel = LinearAlgebra.nullspace[F](rows, atBirth.size + before.size)
        rowSpace(kernel.map(_.take(atBirth.size)))

    /** `larger ∩ smaller^⊥` for subspaces of the span of `atBirth` given by coordinates, `smaller ⊆ larger`, the
      * orthogonality being that of the chains; an orthogonal basis of it, in coordinates.
      */
    def complement(
      atBirth: Vector[Vec],
      larger: Vector[Vector[F]],
      smaller: Vector[Vector[F]]
    ): Vector[Vector[F]] =
      if larger.isEmpty then Vector.empty
      else
        val g = gram(atBirth)
        def inner(x: Vector[F], y: Vector[F]): F =
          x.indices.foldLeft(fr.zero)((sum, i) =>
            y.indices.foldLeft(sum)((s, j) => fr.plus(s, fr.times(x(i), fr.times(g(i)(j), y(j)))))
          )
        val rows = smaller.map(n => larger.map(m => inner(m, n)))
        val combinations = LinearAlgebra.nullspace[F](rows, larger.size)
        val spanned = combinations.map(c =>
          Vector.tabulate(atBirth.size)(i =>
            c.indices.foldLeft(fr.zero)((sum, a) => fr.plus(sum, fr.times(c(a), larger(a)(i))))
          )
        )
        // Gram-Schmidt in the chains' inner product (no square roots, so exact over the rationals)
        spanned.foldLeft(Vector.empty[Vector[F]]) { (done, v) =>
          val orthogonal = done.foldLeft(v) { (w, u) =>
            val factor = fr.divide(inner(w, u), inner(u, u))
            w.zip(u).map((a, b) => fr.minus(a, fr.times(factor, b)))
          }
          done :+ orthogonal
        }
