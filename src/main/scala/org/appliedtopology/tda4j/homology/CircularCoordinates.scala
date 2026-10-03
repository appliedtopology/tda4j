package org.appliedtopology.tda4j

import org.apache.commons.math3.linear.{ArrayRealVector, ConjugateGradient, RealLinearOperator, RealVector}

import scala.collection.mutable

/** The chosen cocycle has no integer lift at the chosen `prime`: either the class is torsion (as on RP²) and has no
  * circle-valued coordinate at all, or `prime` is too small for the integer cocycle's entries; in the second case a
  * larger prime helps.
  */
class NoIntegerCocycleException(message: String) extends RuntimeException(message)

/** Circular coordinates (de Silva, Morozov, Vejdemo-Johansson, "Persistent Cohomology and Circular Coordinates",
  * Discrete & Computational Geometry 45:737-759, 2011): a map from the points to the circle `R/Z` that represents a
  * persistent H¹ class of the Vietoris-Rips complex.
  *
  * Pick a scale `r` inside the class's bar (see [[h1Bars]]). The class is then an essential class of the fixed complex
  * `K_r`, the complex truncated at `r`; it is matched to the bar of the full computation with the same birth, since
  * truncation never changes a birth. Its cocycle, computed over `F_prime` and lifted to the integers, is smoothed to
  * the harmonic representative: `g` minimizing `||z - d0 g||²` over the connected component of `K_r` carrying the
  * class, solved by conjugate gradients on the normal equations with one vertex fixed at `0`. The coordinate of a point
  * is `g(v) mod 1`. The solve follows DREiMac's `toroidalcoords.py`.
  */
object CircularCoordinates:

  /** `theta`: ambient point index (matching the `metricSpace` passed to `compute`) to its circle coordinate in `[0, 1)`
    * -- only for points in the connected component of `K_r` containing the chosen class (see the class doc); a point
    * outside that component has no entry at all, not a sentinel value. `birth`/`death` are the chosen bar's own
    * full-filtration endpoints (`death = Double.PositiveInfinity` for an essential bar); `r` and `prime` echo the
    * parameters `compute` was called with.
    */
  case class Result(theta: Map[Int, Double], birth: Double, death: Double, r: Double, prime: Int)

  private def isPrime(n: Int): Boolean =
    n >= 2 && (2 to math.sqrt(n.toDouble).toInt).forall(d => n % d != 0)

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v

  private def persistenceOf(bar: PersistenceBar[Double, ?]): Double =
    endpointValue(bar.upper) - endpointValue(bar.lower)

  /** The `(birth, death)` of every persistent H¹ class of the Vietoris-Rips complex of `metricSpace`, most persistent
    * first: index `i` is `cocycleIndex = i` in [[compute]]. Call this first to choose `r`.
    */
  def h1Bars(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): IndexedSeq[(Double, Double)] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue.toOption),
      2
    )
    val ctx = CellularCohomologyEngine[Simplex[Int], Double, Double]()
    ctx
      .persistentCohomology(stream)
      .filter(_.dim == 1)
      .sortBy(b => -persistenceOf(b))
      .map(b => (endpointValue(b.lower), endpointValue(b.upper)))
      .toIndexedSeq

  /** Circular coordinates for one persistent H¹ class of the Vietoris-Rips complex of `metricSpace`.
    *
    * @param metricSpace
    *   the points, or a metric space.
    * @param r
    *   the scale at which the coordinates are computed; it must lie in `[birth, death)` of the chosen class (the error
    *   names that range otherwise).
    * @param cocycleIndex
    *   the class, `0` being the most persistent (as in [[h1Bars]] and DREiMac's `cocycle_idx`).
    * @param prime
    *   the odd prime cohomology is computed over before the cocycle is lifted to the integers (`F_2` would admit
    *   classes with no lift). If [[NoIntegerCocycleException]] is thrown for a class that is not torsion, try a larger
    *   one.
    * @param maxFiltrationValue
    *   the truncation of the full computation that finds the classes (default: the minimum enclosing radius).
    */
  def compute(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int = 0,
    prime: Int = 47,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): Result =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    val ff = new FiniteField(prime)
    import ff.given
    computeGeneric[ff.Fp](metricSpace, r, cocycleIndex, prime, maxFiltrationValue.toOption, _.toInt)

  private def computeGeneric[C: Field](
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int,
    prime: Int,
    maxFiltrationValue: Option[Double],
    toInt: C => Int
  ): Result =
    given realField: (Double is Field) = Field.DoubleApproximated(1e-9)

    val ctx = CellularCohomologyEngine[Simplex[Int], C, Double]()

    // Full computation, dimension-capped one band above H^1 (triangles) so essential-vs-finite is resolved
    // correctly (H_1 needs 2-dimensional chains) -- the same "+1" every other maxDim-truncated engine in this
    // codebase needs, for the identical reason.
    val fullStream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
      2
    )
    val h1Bars = ctx
      .persistentCohomology(fullStream)
      .filter(_.dim == 1)
      .sortBy(b => -persistenceOf(b))
    require(
      cocycleIndex >= 0 && cocycleIndex < h1Bars.length,
      s"cocycleIndex must be in [0, ${h1Bars.length}) -- this metric space has ${h1Bars.length} H^1 bar(s), got " +
        s"cocycleIndex=$cocycleIndex"
    )
    val target = h1Bars(cocycleIndex)
    val birth = endpointValue(target.lower)
    val death = endpointValue(target.upper)
    require(r >= birth && r < death, s"r=$r must lie in [birth, death) of the chosen bar, got [$birth, $death)")

    // K_r: the SAME construction as the full stream above, just re-thresholded at r instead of maxFiltrationValue
    // -- a fixed, non-persistent-in-the-usual-sense complex, but still fed through the SAME filtered-stream/
    // persistent-cohomology machinery, which is exactly what makes birth-value matching below a direct
    // comparison rather than a separate algorithm (see the class doc).
    val krStream =
      LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(r)), 2)
    val krBars = ctx.persistentCohomology(krStream)
    val krEssentialH1 = krBars.filter(b => b.dim == 1 && !endpointValue(b.upper).isFinite)
    val matches = krEssentialH1.filter(b => endpointValue(b.lower) == birth)
    require(
      matches.nonEmpty,
      s"no essential H^1 class at K_r (r=$r) has birth $birth -- this should be structurally impossible " +
        "(truncating a filtration cannot change an already-born class's birth value); an engine bug, not a data issue"
    )
    require(
      matches.length == 1,
      s"${matches.length} essential H^1 classes at K_r (r=$r) share birth $birth -- cannot disambiguate which " +
        "one is the target bar; this is a genuine tie in the data, not resolvable automatically"
    )
    val cocycle: Chain[Simplex[Int], C] = matches.head.annotation.getOrElse(
      throw new IllegalStateException("essential bar has no recorded cocycle representative -- an engine bug")
    )

    // Integer lift + exact (not mod-prime) cocycle-condition check -- see NoIntegerCocycleException's own doc.
    val zInt: Map[Simplex[Int], Int] = cocycle.rawEntries.groupMapReduce(_._1)(t => toInt(t._2))(_ + _)
    verifyIntegerCocycle(krStream, zInt, prime)

    val (theta, _, _) = harmonicSmoothOnComponent(krStream, zInt)
    Result(theta, birth, death, r, prime)

  /** Given `K_r` and an ALREADY-VALIDATED integer 1-cochain `zInt` (a genuine integer cocycle on `krStream`, see
    * [[verifyIntegerCocycle]] -- this method itself does no cocycle-condition checking), performs the harmonic
    * smoothing (see class doc: `min_g ||z - d0 g||^2`, anchored at one component vertex) and returns the resulting
    * circular coordinate `theta(v) = frac(g(v))` alongside the harmonic cochain `h = z - d0 g` itself (as a value on
    * every edge of the component, including edges where `zInt` is zero -- `h` is generally NONZERO there too) and the
    * connected component of `krStream`'s 1-skeleton the whole computation was restricted to. Factored out of
    * `computeGeneric` (which calls this for a single already-matched class) so `computeToroidalGeneric` can call it
    * once per SIMULTANEOUSLY-chosen class on the SAME `krStream` -- the harmonic cochain `h` is exactly the vector
    * [[LatticeReduction]]'s Gram matrix is built from (the paper's own dSMV inner product is the plain sum-over-edges
    * dot product of these).
    */
  private[tda4j] def harmonicSmoothOnComponent(
    krStream: CellStream[Simplex[Int], Double],
    zInt: Map[Simplex[Int], Int]
  )(using Double is Field): (Map[Int, Double], Map[Simplex[Int], Double], Set[Simplex[Int]]) =
    val allEdges: Vector[Simplex[Int]] = krStream.iterator.filter(_.dim == 1).toVector
    val adjacency: Map[Simplex[Int], Vector[Simplex[Int]]] =
      allEdges.flatMap(edge => edge.boundary[Double].map(_._1).map(v => v -> edge)).groupMap(_._1)(_._2)
    val seedVertex: Simplex[Int] = zInt.keys.head.boundary[Double].head._1
    val component: Set[Simplex[Int]] = bfsComponent(seedVertex, adjacency)
    val componentEdges: Vector[Simplex[Int]] =
      allEdges.filter(e => e.boundary[Double].forall((v, _) => component.contains(v)))

    val anchor: Simplex[Int] = component.head
    val reducedVertices: IndexedSeq[Simplex[Int]] = (component - anchor).toIndexedSeq
    val reducedIndexOf: Map[Simplex[Int], Int] = reducedVertices.zipWithIndex.toMap

    val laplacian = new RealLinearOperator:
      override def getRowDimension: Int = reducedVertices.length
      override def getColumnDimension: Int = reducedVertices.length
      override def operate(x: RealVector): RealVector =
        val g: Map[Simplex[Int], Double] = reducedIndexOf.view.mapValues(i => x.getEntry(i)).toMap
        def gOf(v: Simplex[Int]): Double = g.getOrElse(v, 0.0) // anchor (and anything outside the component) is 0
        val acc = mutable.Map.empty[Simplex[Int], Double].withDefaultValue(0.0)
        for edge <- componentEdges do
          val terms = edge.boundary[Double]
          val edgeValue = terms.map((v, c) => c * gOf(v)).sum
          for (v, c) <- terms do acc(v) = acc(v) + c * edgeValue
        val out = new ArrayRealVector(reducedVertices.length)
        for i <- reducedVertices.indices do out.setEntry(i, acc.getOrElse(reducedVertices(i), 0.0))
        out

    val rhsAcc = mutable.Map.empty[Simplex[Int], Double].withDefaultValue(0.0)
    for edge <- componentEdges do
      val terms = edge.boundary[Double]
      val zValue = zInt.getOrElse(edge, 0).toDouble
      for (v, c) <- terms do rhsAcc(v) = rhsAcc(v) + c * zValue
    val rhs = new ArrayRealVector(reducedVertices.length)
    for i <- reducedVertices.indices do rhs.setEntry(i, rhsAcc.getOrElse(reducedVertices(i), 0.0))

    val cg = new ConjugateGradient(1000, 1e-10, true)
    val gReduced = cg.solve(laplacian, rhs)

    def gOf(v: Simplex[Int]): Double = if v == anchor then 0.0 else gReduced.getEntry(reducedIndexOf(v))
    def frac(x: Double): Double = x - math.floor(x)

    val theta: Map[Int, Double] = component.toIndexedSeq.map(v => v.underlying.head -> frac(gOf(v))).toMap
    val harmonic: Map[Simplex[Int], Double] = componentEdges.map { edge =>
      val terms = edge.boundary[Double]
      val dg = terms.map((v, c) => c * gOf(v)).sum
      edge -> (zInt.getOrElse(edge, 0).toDouble - dg)
    }.toMap

    (theta, harmonic, component)

  /** `theta`: one map per combined coordinate (same shape/semantics as [[Result]]'s own `theta`, in the SAME order as
    * `cocycleIndices`), giving a joint torus-valued map `K_r`'s shared connected component -> `(R/Z)^k`. `basisChange`
    * is [[LatticeReduction.Result.basisChange]]: column `c`'s coordinates express the `c`-th returned coordinate as an
    * integer combination of the ORIGINAL `cocycleIndices`-ordered classes (the identity matrix if `reduce = false` or
    * only one class was chosen). `originalGram`/`reducedGram` are the chosen classes' own harmonic-representative Gram
    * matrix before/after reduction -- smaller off-diagonal entries in `reducedGram` is the evidence the reduction
    * actually decorrelated the coordinates on this data, and `originalGram == reducedGram` (up to a signed permutation)
    * means the raw persistent-cohomology basis was already about as good as it gets here.
    */
  case class ToroidalResult(
    theta: IndexedSeq[Map[Int, Double]],
    cocycleIndices: IndexedSeq[Int],
    basisChange: Array[Array[Int]],
    originalGram: Array[Array[Double]],
    reducedGram: Array[Array[Double]],
    r: Double,
    prime: Int
  )

  /** Toroidal coordinates (Scoccola, Gakhar, Bush, Schonsheck, Rask, Zhou, Perea, "Toroidal Coordinates: Decorrelating
    * Circular Coordinates With Lattice Reduction", arXiv:2212.07201): circular coordinates for several H¹ classes alive
    * at `r`, combined into one map to a torus.
    *
    * Any unimodular integer recombination of `k` classes generates the same lattice of classes, so the `k` coordinates
    * a cohomology computation returns are an arbitrary choice. This picks the shortest, most nearly orthogonal
    * recombination ([[LatticeReduction]], LLL) under the inner product of the harmonic representatives.
    *
    * @param cocycleIndices
    *   the classes (indexed as in [[h1Bars]]), at least one and without repeats. Each bar must contain `r`, and all of
    *   them must live on the same connected component of `K_r`.
    * @param reduce
    *   `false` returns the coordinates of the classes as given, without recombining them (`basisChange` is then the
    *   identity).
    */
  def computeToroidal(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndices: Seq[Int],
    prime: Int = 47,
    reduce: Boolean = true,
    maxFiltrationValue: Optional[Double] = Optional.empty
  ): ToroidalResult =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    val ff = new FiniteField(prime)
    import ff.given
    computeToroidalGeneric[ff.Fp](metricSpace, r, cocycleIndices, prime, reduce, maxFiltrationValue.toOption, _.toInt)

  private def computeToroidalGeneric[C: Field](
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndices: Seq[Int],
    prime: Int,
    reduceFlag: Boolean,
    maxFiltrationValue: Option[Double],
    toInt: C => Int
  ): ToroidalResult =
    given realField: (Double is Field) = Field.DoubleApproximated(1e-9)
    require(cocycleIndices.nonEmpty, "cocycleIndices must be non-empty")
    require(
      cocycleIndices.distinct.length == cocycleIndices.length,
      s"cocycleIndices must not contain duplicates, got $cocycleIndices"
    )

    val ctx = CellularCohomologyEngine[Simplex[Int], C, Double]()

    // Shared, computed ONCE regardless of how many classes are requested -- only the per-class tail below
    // (birth-match, lift+verify, harmonic smoothing) genuinely needs to run once per chosen index.
    val fullStream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
      2
    )
    val fullH1Bars = ctx.persistentCohomology(fullStream).filter(_.dim == 1).sortBy(b => -persistenceOf(b))
    require(
      cocycleIndices.forall(i => i >= 0 && i < fullH1Bars.length),
      s"cocycleIndices must all be in [0, ${fullH1Bars.length}) -- this metric space has ${fullH1Bars.length} " +
        s"H^1 bar(s), got $cocycleIndices"
    )

    val targets = cocycleIndices.map(fullH1Bars(_))
    val births = targets.map(t => endpointValue(t.lower))
    val deaths = targets.map(t => endpointValue(t.upper))
    val loR = births.max
    val hiR = deaths.min
    require(
      r >= loR && r < hiR,
      s"r=$r must lie in the intersection of every chosen class's own [birth, death) -- classes $cocycleIndices " +
        s"are simultaneously alive only on [$loR, $hiR)"
    )

    val krStream =
      LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(r)), 2)
    val krEssentialH1 = ctx.persistentCohomology(krStream).filter(b => b.dim == 1 && !endpointValue(b.upper).isFinite)

    case class ClassData(component: Set[Simplex[Int]], theta: Map[Int, Double], harmonic: Map[Simplex[Int], Double])

    val classData: IndexedSeq[ClassData] = cocycleIndices
      .zip(births)
      .map { case (idx, birth) =>
        val matches = krEssentialH1.filter(b => endpointValue(b.lower) == birth)
        require(
          matches.nonEmpty,
          s"no essential H^1 class at K_r (r=$r) has birth $birth (cocycleIndex=$idx) -- structurally impossible " +
            "(truncating a filtration cannot change an already-born class's birth value); an engine bug, not a data issue"
        )
        require(
          matches.length == 1,
          s"${matches.length} essential H^1 classes at K_r (r=$r) share birth $birth (cocycleIndex=$idx) -- cannot " +
            "disambiguate which one is the target bar; this is a genuine tie in the data, not resolvable automatically"
        )
        val cocycle: Chain[Simplex[Int], C] = matches.head.annotation.getOrElse(
          throw new IllegalStateException("essential bar has no recorded cocycle representative -- an engine bug")
        )
        val zInt: Map[Simplex[Int], Int] = cocycle.rawEntries.groupMapReduce(_._1)(t => toInt(t._2))(_ + _)
        try verifyIntegerCocycle(krStream, zInt, prime)
        catch
          case e: NoIntegerCocycleException =>
            throw new NoIntegerCocycleException(s"cocycleIndex=$idx: ${e.getMessage}")

        val (theta, harmonic, component) = harmonicSmoothOnComponent(krStream, zInt)
        ClassData(component, theta, harmonic)
      }
      .toIndexedSeq

    val sharedComponent = classData.head.component
    require(
      classData.forall(_.component == sharedComponent),
      s"chosen classes $cocycleIndices do not all live on the same connected component of K_r's 1-skeleton " +
        s"(r=$r) -- a joint torus coordinate needs every one of them supported on the same component"
    )

    val k = cocycleIndices.length
    def dot(a: Map[Simplex[Int], Double], b: Map[Simplex[Int], Double]): Double =
      a.foldLeft(0.0) { case (acc, (edge, va)) => acc + va * b.getOrElse(edge, 0.0) }

    val gram: Array[Array[Double]] = Array.ofDim[Double](k, k)
    for i <- 0 until k; j <- i until k do
      val v = dot(classData(i).harmonic, classData(j).harmonic)
      gram(i)(j) = v
      gram(j)(i) = v

    val identity: Array[Array[Int]] = Array.tabulate(k, k)((i, j) => if i == j then 1 else 0)
    val (basisChange, reducedGram) =
      if k == 1 || !reduceFlag then (identity, gram)
      else
        val result = LatticeReduction.reduce(gram)
        (result.basisChange, result.reducedGram)

    val points: IndexedSeq[Int] = sharedComponent.toIndexedSeq.map(_.underlying.head)
    val newTheta: IndexedSeq[Map[Int, Double]] = (0 until k).map { col =>
      points.map { point =>
        val combined = (0 until k).map(row => basisChange(row)(col) * classData(row).theta(point)).sum
        point -> (combined - math.floor(combined))
      }.toMap
    }

    ToroidalResult(newTheta, cocycleIndices.toIndexedSeq, basisChange, gram, reducedGram, r, prime)

  private def bfsComponent(
    seed: Simplex[Int],
    adjacencyByEdge: Map[Simplex[Int], Vector[Simplex[Int]]]
  )(using Double is Field): Set[Simplex[Int]] =
    val visited = mutable.Set(seed)
    val queue = mutable.Queue(seed)
    while queue.nonEmpty do
      val v = queue.dequeue()
      for edge <- adjacencyByEdge.getOrElse(v, Vector.empty) do
        for (neighbor, _) <- edge.boundary[Double] if !visited.contains(neighbor) do
          visited += neighbor
          queue.enqueue(neighbor)
    visited.toSet

  /** Requires `delta(z) = 0` EXACTLY as integers for every triangle of `stream` -- not merely mod `prime` (which `z`'s
    * own construction, a cohomology computation over a prime field, already guarantees trivially and proves nothing) --
    * throwing [[NoIntegerCocycleException]] naming the offending triangle if it fails anywhere.
    */
  private def verifyIntegerCocycle(
    stream: CellStream[Simplex[Int], Double],
    z: Map[Simplex[Int], Int],
    prime: Int
  )(using Double is Field): Unit =
    val triangles = stream.iterator.filter(_.dim == 2)
    for triangle <- triangles do
      val boundaryEdges = triangle.boundary[Double] // (edge, +-1.0) pairs -- sign only, magnitude always 1 here
      val total = boundaryEdges.map((edge, sign) => math.round(sign).toInt * z.getOrElse(edge, 0)).sum
      if total != 0 then
        throw new NoIntegerCocycleException(
          s"the chosen cocycle has no exact integer lift at prime=$prime: triangle " +
            s"${triangle.underlying.mkString("[", ",", "]")}'s integer boundary sums to $total, not 0. Either the " +
            "underlying cohomology class is genuinely torsion (no real/integer lift exists at any prime), or this " +
            "prime is too small relative to the true integer cocycle's own magnitudes -- retry with a larger prime."
        )
