package org.appliedtopology.tda4j

import scala.collection.mutable

/** The harmonic chain barcode of a filtered complex (Hou, Parsa and Wang, "Tracking the persistence of harmonic
  * chains", SoCG 2025): the barcode that follows the harmonic chains `H_p(K_t) = ker ∂_p ∩ ker ∂_{p+1}^T` (cycles
  * orthogonal to all boundaries, the kernel of the Hodge Laplacian) along the filtration. Each bar is a harmonic cycle
  * that stays harmonic for the bar's whole life, so it needs no choice of representative, and the representative the
  * bar carries is that one cycle.
  *
  * {{{
  * val rips = VietorisRips(points, maxDimension = 1)
  * val diagram = HarmonicChainBarcode(rips, maxDimension = 1)
  * diagram.bars.head.representative   // a chain that is a cycle and a cocycle at every value of its bar
  * }}}
  *
  * It is NOT the ordinary barcode. When a cell enters, the harmonic chains of the complex change by a whole class at a
  * time, and a cycle stops being harmonic as soon as it is no longer orthogonal to the boundaries, which happens before
  * it becomes a boundary: a filtration in which the ordinary barcode has the bars `[4, 9), [5, 10), [7, 8)` can have
  * the harmonic bars `[4, 8), [5, 9), [7, 10)` (the example of the paper, a spec of this library). The number of bars
  * alive at each value is the Betti number there, as for the ordinary barcode. A bar `[b, d)` is born when the cell
  * that creates its cycle enters (at `b`) and ends when the cell enters that makes the cycle non-orthogonal to a
  * boundary (at `d`); the two cells are the bar's [[PersistenceBar.birthCell]] and [[PersistenceBar.deathCell]].
  *
  * The barcode is defined for a simplex-wise filtration, one cell entering at a time, and the harmonic chains of the
  * complexes in between depend on the order in which cells of equal filtration value enter.
  * `HarmonicChainBarcode(stream)` takes them in the order value, then dimension (so a face comes before its cofaces),
  * then the stream's own order within a dimension ([[insertionOrder]]), as the paper fixes an order too; [[ofOrder]]
  * takes any order. A bar born and gone at one value is left out unless `includeZeroLength = true`.
  *
  * The representative of a bar (Algorithm 1 of the paper): when a cell creates a cycle, the harmonic chain that is new
  * is the one orthogonal to every earlier cycle (the Gram-Schmidt step; the paper's coboundary basis finds the same
  * chain), and when a cell closes a class, the oldest harmonic chain that is no longer orthogonal to its boundary ends
  * the bar, and the other chains that are not orthogonal have a multiple of it subtracted. It is the bar's chain at the
  * moment the bar ends, and it was harmonic from the moment the bar began.
  *
  * In a Vietoris-Rips filtration a triangle enters at the value of its longest edge: if that edge starts a new loop,
  * the triangle ends the oldest loop it is not orthogonal to, so a long ordinary bar is cut there and appears as
  * consecutive bars. For the ordinary bars with harmonic representatives use [[HarmonicRepresentatives]]. The births
  * and deaths are always those of the ordinary barcode, paired differently.
  *
  * Harmonic means an inner product, so the coefficients are the real numbers (`Coefficients(0)`, the default; a
  * tolerance decides which entries are zero) or the exact rationals (`Coefficients.Rationals`, no tolerance: the
  * numbers can grow), and a finite field is refused: the dot product is not positive definite there, so a chain can be
  * orthogonal to itself and the algorithm divides by its length. The result is an ordinary [[PersistenceDiagram]] whose
  * bars carry their cells, so every tool for diagrams applies; its representatives are chains that are both cycles and
  * cocycles. The cost is `O(m^3)` for `m` cells in the worst case (the paper's bound) and the chains are kept sparse.
  */
object HarmonicChainBarcode:

  /** How the chain a positive cell creates is made orthogonal to every older cycle (the step of Algorithm 1 that costs
    * memory). Both give the same chain: the one cycle with coefficient 1 on the cell that is orthogonal to the cycles
    * of the complex before it. Over the rationals the two agree term for term. Over the reals every decision of the
    * algorithm is a test against the field's tolerance, so on a large complex with many near ties a cycle that is
    * almost orthogonal to a boundary can be judged differently by the two, and a few bars are then paired differently
    * (the births and deaths are the same); `Coefficients.Rationals` settles which pairing is right.
    *
    *   - `GramSchmidt`: the orthogonal basis of all the cycles of the degree is kept, and the new chain is
    *     orthogonalised against it. The basis grows with every positive cell and holds a dense chain for each.
    *   - `LaplacianSolve`: nothing is kept but the boundaries and an index of the cofaces. With `z = e_σ + ∂ᵀ y`, `y`
    *     solves `(∂ ∂ᵀ) y = -∂σ` on the cells of the degree below, the boundary `∂` being that of the cells of `σ`'s
    *     dimension that entered before it (conjugate gradient, which finishes exactly over the rationals). The memory
    *     is a fraction of the basis; over the reals the solve stops when its residual is zero to the field's tolerance,
    *     and gives up with an error that names the alternatives if it does not get there.
    */
  enum Orthogonalization:
    case GramSchmidt, LaplacianSolve

  /** The harmonic chain barcode of `stream` in degrees `0..maxDimension` (default: the stream's own degree limit if it
    * has one, else 2, as for [[Persistence]]). The cells of dimension `maxDimension + 1` are the ones that close
    * classes of degree `maxDimension`, so a stream built with `VietorisRips(points, maxDimension = k)` holds them for
    * `k`.
    */
  def apply[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Optional[Int] = Optional.empty,
    coefficients: Optional[Coefficients] = Optional.empty,
    includeZeroLength: Boolean = false,
    orthogonalization: Orthogonalization = Orthogonalization.GramSchmidt
  ): PersistenceDiagram[CellT] =
    val degree = degreeFor(stream, maxDimension)
    ofOrder(insertionOrder(stream, degree), degree, coefficients, includeZeroLength, orthogonalization)

  // The top degree asked for, checked against what the stream was built for (as the verb does).
  private[tda4j] def degreeFor[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Optional[Int]
  ): Int =
    val requested = maxDimension.toOption
    requested.foreach(k => require(k >= 0, s"HarmonicChainBarcode: maxDimension must be >= 0, got $k"))
    (requested, stream.homologyDegreeLimit) match
      case (Some(k), Some(limit)) if k > limit =>
        throw new IllegalArgumentException(
          s"HarmonicChainBarcode: this complex was built for degrees 0..$limit, so degree $k would be wrong; " +
            s"build it with maxDimension = $k"
        )
      case (Some(k), _)        => k
      case (None, Some(limit)) => limit
      case (None, None)        => Persistence.DefaultMaxDimension

  /** A basis of the harmonic chains of degree `dimension` of the complex at filtration value `value` (all the cells of
    * the stream with value at most `value`): as many chains as the Betti number there, each a cycle orthogonal to every
    * boundary, in the order of their births. A lab hook for what the barcode follows: the chains the algorithm keeps
    * alive after the last cell of that value (the order of the cells is [[insertionOrder]]). The chains' coefficients
    * are `coefficients.C`: `harmonicChains(stream, 1, 0.5, Coefficients.Rationals)` has exact rational ones.
    */
  def harmonicChains[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    dimension: Int,
    value: Double,
    coefficients: Coefficients,
    orthogonalization: Orthogonalization = Orthogonalization.GramSchmidt
  ): Vector[Chain[CellT, coefficients.C]] =
    zeroCharacteristic(Optional(Some(coefficients)))
    import coefficients.given
    val degree = degreeFor(stream, dimension)
    val order = trackedOrder(insertionOrder(stream, degree), degree)
    order.map(_._2).filter(_ <= value).lastOption match
      case None        => Vector.empty
      case Some(level) =>
        val run =
          new Run[CellT, coefficients.C](degree, order, includeZeroLength = true, Set(level), orthogonalization)
        run.execute()
        run.chainsAt(dimension, level)

  /** The order in which [[apply]] inserts the cells of `stream` up to dimension `maxDimension + 1`: by filtration
    * value, then dimension, then the position in the stream's own order of the dimension. Each cell comes with its
    * filtration value.
    */
  def insertionOrder[CellT: OrderedCell](
    stream: StratifiedCellStream[CellT, Double],
    maxDimension: Int
  ): Vector[(CellT, Double)] =
    val value = stream.filtrationValue
    val layers = Iterator
      .from(0)
      .takeWhile(d => d <= maxDimension + 1 && stream.iterateDimension.isDefinedAt(d))
      .map(d => stream.iterateDimension(d).toVector)
      .toVector
    layers.zipWithIndex
      .flatMap((layer, d) => layer.zipWithIndex.map((cell, k) => (value(cell), d, k, cell)))
      .sortBy(entry => (entry._1, entry._2, entry._3))
      .map(entry => (entry._4, entry._1))

  /** The harmonic chain barcode of the filtration that inserts `order`, one cell at a time, each with its filtration
    * value (which must not decrease along the order; a cell's faces must come before it). A bar lasts from the value of
    * the cell that begins it to the value of the cell that ends it. Cells of dimension above `maxDimension + 1` are
    * ignored: they cannot change the harmonic chains of degrees up to `maxDimension`.
    */
  def ofOrder[CellT: OrderedCell](
    order: Seq[(CellT, Double)],
    maxDimension: Int,
    coefficients: Optional[Coefficients] = Optional.empty,
    includeZeroLength: Boolean = false,
    orthogonalization: Orthogonalization = Orthogonalization.GramSchmidt
  ): PersistenceDiagram[CellT] =
    require(maxDimension >= 0, s"HarmonicChainBarcode: maxDimension must be >= 0, got $maxDimension")
    val field = zeroCharacteristic(coefficients)
    import field.given
    val tracked = trackedOrder(order, maxDimension)
    val bars =
      new Run[CellT, field.C](maxDimension, tracked, includeZeroLength, orthogonalization = orthogonalization).execute()
    PersistenceDiagram[CellT, field.C](
      bars,
      maxDimension,
      tracked.lastOption.fold(0.0)(_._2),
      representatives = Some(Representatives.Cycles)
    )

  // The coefficients, which must be real or rational: the harmonic condition is orthogonality.
  private[tda4j] def zeroCharacteristic(coefficients: Optional[Coefficients]): Coefficients =
    val field = coefficients.getOrElse(Coefficients(0))
    require(
      field.characteristic == 0,
      "HarmonicChainBarcode: harmonic chains need an inner product on the chains, so the coefficients must be the real " +
        s"or the rational numbers (characteristic 0), not a field of characteristic ${field.characteristic}; " +
        "pass coefficients = Coefficients(0) or Coefficients.Rationals"
    )
    field

  // The cells the run needs (dimension at most maxDimension + 1), with their values checked not to decrease.
  private[tda4j] def trackedOrder[CellT: OrderedCell](
    order: Seq[(CellT, Double)],
    maxDimension: Int
  ): Vector[(CellT, Double)] =
    val tracked = order.filter((cell, _) => summon[CellT is OrderedCell].dim(cell) <= maxDimension + 1).toVector
    tracked.zip(tracked.drop(1)).foreach { case ((_, before), (_, after)) =>
      require(
        before <= after,
        s"HarmonicChainBarcode: the filtration values decrease along the order ($before, then $after)"
      )
    }
    tracked

  // A harmonic chain that is alive: `rep` is its chain now, and `birth` the number of the cell whose entry created it.
  private final class Born[F](val birth: Int, val rep: mutable.HashMap[Int, F])

  // The state of one run. Cells are numbered by their place in the order, and every chain is a sparse vector over those numbers.
  // `private[tda4j]` because it holds the mutable state of a run: HarmonicRepresentatives reads the snapshots it takes.
  private[tda4j] final class Run[CellT, F](
    maxDimension: Int,
    val order: Vector[(CellT, Double)],
    includeZeroLength: Boolean,
    snapshotValues: Set[Double] = Set.empty,
    orthogonalization: Orthogonalization = Orthogonalization.GramSchmidt
  )(using
    cells: CellT is OrderedCell,
    fr: F is Field
  ):
    type Vec = mutable.HashMap[Int, F]

    // The harmonic chains of each degree after the last cell of each filtration value in `snapshotValues`.
    val snapshots = mutable.HashMap.empty[(Int, Double), Vector[Vec]]

    private val dims = order.map((cell, _) => cells.dim(cell))
    private val ids = order.zipWithIndex.map((entry, i) => entry._1 -> i).toMap

    // The boundary of the cell numbered `i`: its faces' numbers with their coefficients. A face must be there already.
    private def faces(i: Int): Vec =
      val result = new Vec
      for (face, coefficient) <- cells.boundary(order(i)._1)[F] do
        val id = ids.getOrElse(
          face,
          throw new IllegalArgumentException(
            s"HarmonicChainBarcode: the cell ${order(i)._1} has a face, $face, that is not in the filtration"
          )
        )
        require(
          id < i,
          s"HarmonicChainBarcode: the cell ${order(i)._1} comes before its face $face; a face must be inserted first"
        )
        if !fr.isEqual(coefficient, fr.zero) then result.update(id, coefficient)
      result

    // The orthogonal cycle basis of each degree: one chain for each cell that created a cycle, each orthogonal to the
    // ones before (so a basis of the cycles of the complex now), with its squared length. Only `GramSchmidt` fills it.
    private val cycleBasis = Array.fill(maxDimension + 1)(mutable.ArrayBuffer.empty[(Vec, F)])

    // `LaplacianSolve` keeps the boundaries instead: of each cell of dimension 1 to `maxDimension` entered so far, and
    // for each face the cells that have it (with the coefficient), which are the two halves of `∂ ∂ᵀ`.
    private val boundaries = mutable.HashMap.empty[Int, Vec]
    private val cofaces = mutable.HashMap.empty[Int, mutable.ArrayBuffer[(Int, F)]]
    private val entered = new Array[Int](maxDimension + 2) // cells entered so far, by dimension

    // The reduced boundaries of the cells of dimension `d + 1`, by pivot (their highest face), each with the chain of
    // `d + 1`-cells that has it as its boundary (the standard reduction of the boundary matrix, the second part kept).
    private val reduced = Array.fill(maxDimension + 1)(mutable.HashMap.empty[Int, (Vec, Vec)])

    // The harmonic chains of each degree that are alive, oldest first, each with the cell that created it.
    private val alive = Array.fill(maxDimension + 1)(mutable.ArrayBuffer.empty[Born[F]])

    private val bars = mutable.ListBuffer.empty[PersistenceBar[Double, Chain[CellT, F]]]

    private def vec(entries: IterableOnce[(Int, F)] = Nil): Vec = mutable.HashMap.from(entries)

    // into += factor * from, dropping the entries that become zero
    private def add(into: Vec, factor: F, from: collection.Map[Int, F]): Unit =
      for (k, x) <- from do
        val updated = fr.plus(into.getOrElse(k, fr.zero), fr.times(factor, x))
        if fr.isEqual(updated, fr.zero) then into.remove(k) else into.update(k, updated)

    private def dot(a: collection.Map[Int, F], b: collection.Map[Int, F]): F =
      val (small, large) = if a.size <= b.size then (a, b) else (b, a)
      small.foldLeft(fr.zero) { case (sum, (k, x)) => large.get(k).fold(sum)(y => fr.plus(sum, fr.times(x, y))) }

    def chainOf(rep: Vec): Chain[CellT, F] =
      Chain.from(rep.toSeq.map((id, x) => (order(id)._1, x)))(using cells.ordering)

    def chainsAt(dimension: Int, value: Double): Vector[Chain[CellT, F]] =
      snapshots((dimension, value)).map(chainOf)

    def execute(): List[PersistenceBar[Double, Chain[CellT, F]]] =
      for i <- order.indices do
        insert(i)
        if snapshotValues(order(i)._2) && (i == order.size - 1 || order(i + 1)._2 > order(i)._2) then
          for p <- 0 to maxDimension do snapshots((p, order(i)._2)) = alive(p).map(born => born.rep.clone()).toVector
      // what is alive at the end never ends
      for p <- 0 to maxDimension; born <- alive(p) do
        bars += PersistenceBar(
          p,
          ClosedEndpoint(order(born.birth)._2),
          PositiveInfinity[Double](),
          Some(chainOf(born.rep)),
          Some(Involution.Pair[CellT](p, order(born.birth)._1, None))
        )
      bars.toList.sortBy(b => (b.dim, b.birth, b.death))

    // Algorithm 1 of the paper, for the cell numbered `i`.
    private def insert(i: Int): Unit =
      val p = dims(i)
      val boundary = faces(i)
      // reduce the boundary against the earlier cells' reduced boundaries, keeping the chain that has the result as its boundary
      val residue = vec(boundary)
      val chain = vec(Seq(i -> fr.one))
      var pivot = if residue.isEmpty then -1 else residue.keysIterator.max
      while pivot >= 0 && reduced(p - 1).contains(pivot) do
        val (column, preimage) = reduced(p - 1)(pivot)
        val factor = fr.negate(fr.divide(residue(pivot), column(pivot)))
        add(residue, factor, column)
        add(chain, factor, preimage)
        pivot = if residue.isEmpty then -1 else residue.keysIterator.max
      if residue.isEmpty then
        // `chain` is a new cycle, and the cell is positive: it begins a bar of degree p (past the top degree nothing is tracked)
        if p <= maxDimension then
          orthogonalization match
            case Orthogonalization.GramSchmidt =>
              val basis = cycleBasis(p)
              for (z, norm) <- basis do add(chain, fr.negate(fr.divide(dot(chain, z), norm)), z)
              basis += ((vec(chain), dot(chain, chain)))
              alive(p) += Born(i, chain)
            case Orthogonalization.LaplacianSolve =>
              alive(p) += Born(i, cycleBySolve(i, p, boundary))
      else
        reduced(p - 1)(pivot) = (residue, chain)
        close(i, p - 1, boundary)
      if orthogonalization == Orthogonalization.LaplacianSolve && p >= 1 && p <= maxDimension then
        boundaries(i) = boundary
        for (face, coefficient) <- boundary do
          cofaces.getOrElseUpdate(face, mutable.ArrayBuffer.empty) += ((i, coefficient))
      entered(p) += 1

    // The cycle through the cell `i` (of dimension `p`, a positive cell) that is orthogonal to the cycles of the cells
    // before it: `e_i + ∂ᵀ y` with `(∂ ∂ᵀ) y = -∂ e_i`, `∂` on the cells of dimension `p` entered before `i`. It
    // is a cycle because `∂ (e_i + ∂ᵀ y) = ∂ e_i + ∂ ∂ᵀ y = 0`, orthogonal to the older cycles because `∂ᵀ y` is in the
    // row space of `∂`, which is their orthogonal complement among the chains of the older cells, and it is the one
    // chain with those properties and coefficient 1 on `e_i` (two of them differ by an older cycle orthogonal to
    // all of them), so it is the Gram-Schmidt step's chain.
    private def cycleBySolve(i: Int, p: Int, boundary: Vec): Vec =
      val chain = vec(Seq(i -> fr.one))
      if p >= 1 then
        val y = solveLaplacian(vec(boundary.map((face, x) => face -> fr.negate(x))), entered(p - 1))
        for (face, potential) <- y; (cell, coefficient) <- cofaces.getOrElse(face, Nil) do
          val updated = fr.plus(chain.getOrElse(cell, fr.zero), fr.times(coefficient, potential))
          if fr.isEqual(updated, fr.zero) then chain.remove(cell) else chain.update(cell, updated)
      chain

    // `(∂ ∂ᵀ) v` on the cells of the degree below `p`, `∂` on the cells of dimension `p` entered so far: through the
    // cofaces of the support of `v`, then the boundaries of the cells reached; the work follows the support.
    private def laplacian(v: Vec): Vec =
      val through = new Vec
      for (face, x) <- v; (cell, coefficient) <- cofaces.getOrElse(face, Nil) do
        through.update(cell, fr.plus(through.getOrElse(cell, fr.zero), fr.times(coefficient, x)))
      val result = new Vec
      for (cell, y) <- through; (face, coefficient) <- boundaries(cell) do
        result.update(face, fr.plus(result.getOrElse(face, fr.zero), fr.times(coefficient, y)))
      result.filterInPlace((_, x) => x != fr.zero)

    // `L y = b` for the symmetric positive semidefinite `L` of `laplacian` and a `b` in its range (so a solution
    // exists), by conjugate gradient from `y = 0` until every entry of the residual is zero for the field: exactly
    // over the rationals, in at most `rank L <= unknowns` steps; to the field's tolerance over the reals, where the
    // recurrence must not drop small entries (it would stop the directions being conjugate: only exact zeros leave a
    // vector) and may take a few more steps than unknowns. `unknowns` is the number of cells below.
    private def solveLaplacian(b: Vec, unknowns: Int): Vec =
      // into += factor * from, dropping only the entries that are exactly zero
      def accumulate(into: Vec, factor: F, from: collection.Map[Int, F]): Unit =
        for (k, x) <- from do
          val updated = fr.plus(into.getOrElse(k, fr.zero), fr.times(factor, x))
          if updated == fr.zero then into.remove(k) else into.update(k, updated)
      // Zero for the field after scaling by 2^10: exact fields are unmoved, and the tolerance of an approximate one
      // tightens a thousandfold, so that the cycle is orthogonal to the older ones well inside the tolerance with which
      // `close` later tells whether a boundary is orthogonal to a cycle.
      val scale = (1 to 10).foldLeft(fr.one)((x, _) => fr.plus(x, x))
      def converged(r: Vec): Boolean = r.valuesIterator.forall(x => fr.isEqual(fr.times(x, scale), fr.zero))
      val y = new Vec
      val r = vec(b)
      val direction = vec(r)
      var rho = dot(r, r)
      var steps = 0
      while !converged(r) do
        steps += 1
        if steps > 2 * unknowns + 50 then
          throw new IllegalStateException(
            s"HarmonicChainBarcode: the conjugate gradient solve for a new cycle did not converge in $steps steps; " +
              "try Coefficients.Rationals, or Orthogonalization.GramSchmidt"
          )
        val image = laplacian(direction)
        val alpha = fr.divide(rho, dot(direction, image))
        accumulate(y, alpha, direction)
        accumulate(r, fr.negate(alpha), image)
        val next = dot(r, r)
        val beta = fr.divide(next, rho)
        for (k, x) <- direction.toSeq do direction.update(k, fr.times(beta, x))
        accumulate(direction, fr.one, r)
        rho = next
      y

    // The cell numbered `i` is negative: it closes a class of degree d. The oldest harmonic chain that is not orthogonal
    // to its boundary ends; the others that are not have a multiple of it subtracted, so that they are orthogonal.
    private def close(i: Int, d: Int, boundary: Vec): Unit =
      val candidates = alive(d).zipWithIndex.map((born, position) => (born, position, dot(born.rep, boundary)))
      val affected = candidates.filterNot((_, _, alpha) => fr.isEqual(alpha, fr.zero))
      if affected.isEmpty then
        throw new IllegalStateException(
          s"HarmonicChainBarcode: the cell ${order(i)._1} closes a class of degree $d, but no harmonic chain is not " +
            "orthogonal to its boundary; with real coefficients this is a tolerance problem, so try Coefficients.Rationals"
        )
      val (oldest, position, alphaOldest) = affected.head
      for (born, _, alpha) <- affected.tail do add(born.rep, fr.negate(fr.divide(alpha, alphaOldest)), oldest.rep)
      alive(d).remove(position)
      val (birth, death) = (order(oldest.birth)._2, order(i)._2)
      if includeZeroLength || birth != death then
        bars += PersistenceBar(
          d,
          ClosedEndpoint(birth),
          OpenEndpoint(death),
          Some(chainOf(oldest.rep)),
          Some(Involution.Pair[CellT](d, order(oldest.birth)._1, Some(order(i)._1)))
        )
