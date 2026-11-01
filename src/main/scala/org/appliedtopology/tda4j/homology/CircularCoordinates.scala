package org.appliedtopology.tda4j

import scala.collection.mutable

/** The chosen cocycle has no integer lift at the chosen `prime`: either the class is torsion (as on RP²) and has no
  * circle-valued coordinate at all, or `prime` is too small for the integer cocycle's entries; in the second case a
  * larger prime helps.
  */
open class NoIntegerCocycleException(message: String) extends RuntimeException(message)

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
  *
  * There are two ways in. `compute(metricSpace, r, ...)` takes the points and does everything itself, on the
  * Vietoris-Rips complex. `CircularCoordinates(diagram, input, ...)` takes a [[PersistenceDiagram]] you already have,
  * of any complex and from any engine, together with what it was computed from, and reads the bar, its field and (when
  * the diagram carries cocycles) its cocycle from the diagram.
  *
  * '''Sparse coordinates''' (Perea, "Sparse Circular Coordinates via Principal Z-Bundles", Abel Symposia 15, 2020,
  * arXiv:1809.09269) need only a few landmarks for any number of points. The loop is found on the nerve of the balls of
  * radius `r` around the landmarks, which is the Dowker complex of the landmark-to-point distances; the harmonic
  * coordinate `g` is computed on the landmarks, and a point `x` gets `g(j) - sum_k phi_k(x) h(j, k)`, where `j` is its
  * nearest landmark, `h` the harmonic cocycle and `phi_k(x)` proportional to `max(r - d(x, l_k), 0)`. The cocycle
  * condition on the nerve makes this independent of the choice of `j`, so the map is continuous. `numLandmarks = n` on
  * [[h1Bars]], [[compute]] and [[computeToroidal]] chooses it with `n` maxmin landmarks; [[sparse]] and
  * [[sparseToroidal]] take a Dowker diagram and its relation, for landmarks or a cover of your own.
  */
object CircularCoordinates:

  /** `theta`: ambient point index (matching the `metricSpace` passed to `compute`) to its circle coordinate in `[0, 1)`
    * -- only for points in the connected component of `K_r` containing the chosen class (see the class doc); a point
    * outside that component has no entry at all, not a sentinel value. For sparse coordinates, the points that the
    * balls of radius `r` around that component's landmarks cover. `birth`/`death` are the chosen bar's own
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
    * first: index `i` is `cocycleIndex = i` in [[compute]]. Call this first to choose `r`. With `numLandmarks = n`,
    * those of the nerve of the balls around `n` maxmin landmarks, which [[compute]] with the same `numLandmarks` uses:
    * there a scale is a ball's radius.
    */
  def h1Bars(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Optional[Double] = Optional.empty,
    numLandmarks: Int = 0
  ): IndexedSeq[(Double, Double)] =
    if numLandmarks > 0 then h1Bars(landmarkCover(metricSpace, numLandmarks, maxFiltrationValue.toOption, 47)._1)
    else vietorisRipsH1Bars(metricSpace, maxFiltrationValue)

  private def vietorisRipsH1Bars(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Optional[Double]
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
    *   the truncation of the full computation that finds the classes (default: the minimum enclosing radius; with
    *   landmarks, the radius at which one point sees them all).
    * @param numLandmarks
    *   `0` (the default) for the coordinates of the complex on every point; `n` for Perea's sparse coordinates over `n`
    *   maxmin landmarks (the class doc), much cheaper for many points. The classes and `r` are then those of
    *   `h1Bars(metricSpace, numLandmarks = n)`.
    */
  def compute(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int = 0,
    prime: Int = 47,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    numLandmarks: Int = 0
  ): Result =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    if numLandmarks > 0 then
      val (diagram, relation) = landmarkCover(metricSpace, numLandmarks, maxFiltrationValue.toOption, prime)
      sparse(diagram, relation, cocycleIndex, r)
    else
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
  def harmonicSmoothOnComponent(
    krStream: CellStream[Simplex[Int], Double],
    zInt: Map[Simplex[Int], Int]
  )(using Double is Field): (Map[Int, Double], Map[Simplex[Int], Double], Set[Simplex[Int]]) =
    harmonicSmooth(krStream.iterator.filter(_.dim == 1).toVector, zInt)

  /** [[harmonicSmoothOnComponent]] on the edges of `K_r` alone: the smoothing reads nothing else. */
  private def harmonicSmooth(
    allEdges: Vector[Simplex[Int]],
    zInt: Map[Simplex[Int], Int]
  )(using Double is Field): (Map[Int, Double], Map[Simplex[Int], Double], Set[Simplex[Int]]) =
    val adjacency: Map[Simplex[Int], Vector[Simplex[Int]]] =
      allEdges.flatMap(edge => edge.boundary[Double].map(_._1).map(v => v -> edge)).groupMap(_._1)(_._2)
    val seedVertex: Simplex[Int] = zInt.keys.head.boundary[Double].head._1
    val component: Set[Simplex[Int]] = bfsComponent(seedVertex, adjacency)
    val componentEdges: Vector[Simplex[Int]] =
      allEdges.filter(e => e.boundary[Double].forall((v, _) => component.contains(v)))

    val anchor: Simplex[Int] = component.head
    val reducedVertices: IndexedSeq[Simplex[Int]] = (component - anchor).toIndexedSeq
    val reducedIndexOf: Map[Simplex[Int], Int] = reducedVertices.zipWithIndex.toMap

    val laplacian: DenseVector => DenseVector = x =>
      val g: Map[Simplex[Int], Double] = reducedIndexOf.view.mapValues(i => x(i)).toMap
      def gOf(v: Simplex[Int]): Double = g.getOrElse(v, 0.0) // anchor (and anything outside the component) is 0
      val acc = mutable.Map.empty[Simplex[Int], Double].withDefaultValue(0.0)
      for edge <- componentEdges do
        val terms = edge.boundary[Double]
        val edgeValue = terms.map((v, c) => c * gOf(v)).sum
        for (v, c) <- terms do acc(v) = acc(v) + c * edgeValue
      DenseVector.tabulate(reducedVertices.length)(i => acc.getOrElse(reducedVertices(i), 0.0))

    val rhsAcc = mutable.Map.empty[Simplex[Int], Double].withDefaultValue(0.0)
    for edge <- componentEdges do
      val terms = edge.boundary[Double]
      val zValue = zInt.getOrElse(edge, 0).toDouble
      for (v, c) <- terms do rhsAcc(v) = rhsAcc(v) + c * zValue
    val rhs = DenseVector.tabulate(reducedVertices.length)(i => rhsAcc.getOrElse(reducedVertices(i), 0.0))

    val gReduced = ConjugateGradient.solve(laplacian, rhs, maxIterations = 1000, tolerance = 1e-10, check = true)

    def gOf(v: Simplex[Int]): Double = if v == anchor then 0.0 else gReduced(reducedIndexOf(v))
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
    * @param numLandmarks
    *   `0` (the default) for the complex on every point, `n` for Perea's sparse coordinates over `n` maxmin landmarks,
    *   as in [[compute]].
    */
  def computeToroidal(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndices: Seq[Int],
    prime: Int = 47,
    reduce: Boolean = true,
    maxFiltrationValue: Optional[Double] = Optional.empty,
    numLandmarks: Int = 0
  ): ToroidalResult =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    if numLandmarks > 0 then
      val (diagram, relation) = landmarkCover(metricSpace, numLandmarks, maxFiltrationValue.toOption, prime)
      sparseToroidal(diagram, relation, cocycleIndices, r, reduce)
    else
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

    combineClasses(classData, cocycleIndices, reduceFlag, r, prime)

  /** One smoothed class on `K_r`: the connected component it lives on, its circle coordinate and its harmonic cochain.
    */
  private case class ClassData(
    component: Set[Simplex[Int]],
    theta: Map[Int, Double],
    harmonic: Map[Simplex[Int], Double]
  )

  /** The classes' joint torus map: the Gram matrix of their harmonic cochains, its lattice reduction, the unimodular
    * change of basis applied to the circle coordinates. The part of [[computeToroidal]] after each class is known.
    */
  private def combineClasses(
    classData: IndexedSeq[ClassData],
    cocycleIndices: Seq[Int],
    reduceFlag: Boolean,
    r: Double,
    prime: Int
  ): ToroidalResult =
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

    // The points with a coordinate: the component's vertices, or for sparse coordinates the points its balls cover.
    val points: IndexedSeq[Int] = classData.head.theta.keys.toIndexedSeq
    val newTheta: IndexedSeq[Map[Int, Double]] = (0 until k).map { col =>
      points.map { point =>
        val combined = (0 until k).map(row => basisChange(row)(col) * classData(row).theta(point)).sum
        point -> (combined - math.floor(combined))
      }.toMap
    }

    ToroidalResult(newTheta, cocycleIndices.toIndexedSeq, basisChange, gram, reducedGram, r, prime)

  // ---- Coordinates from a persistence diagram -------------------------------------------------------------------------

  /** The `(birth, death)` of every H¹ bar of `diagram`, most persistent first: index `i` is `bar = i` in [[apply]] and
    * [[toroidal]]. Call this first to choose `r`.
    */
  def h1Bars(diagram: PersistenceDiagram[Simplex[Int]]): IndexedSeq[(Double, Double)] =
    h1BarsOf[diagram.Coefficient](diagram).map(b => (endpointValue(b.lower), endpointValue(b.upper))).toIndexedSeq

  private def h1BarsOf[C](
    diagram: PersistenceDiagram.Of[Simplex[Int], C]
  ): List[PersistenceBar[Double, Chain[Simplex[Int], C]]] =
    diagram.bars.filter(_.dim == 1).sortBy(b => -persistenceOf(b))

  /** Circular coordinates for one H¹ bar of a persistence diagram: the loop `diagram.dim(1)` found, whichever complex
    * and engine found it.
    *
    * {{{
    * val diagram = Persistence(points, maxDimension = 1)
    * val coordinate = CircularCoordinates(diagram, points)       // the most persistent loop, at the middle of its bar
    * CircularCoordinates(diagram, points, bar = 1, r = 0.6)      // another loop, at a chosen scale
    * CircularCoordinates(cechDiagram, points, complex = Cech)    // a Cech diagram: say which complex it is of
    * }}}
    *
    * The scale `r` and the cocycle are read off the diagram where they can be. With `representatives =
    * Representatives.Cocycles` the bar's own cocycle is restricted to the complex at `r` and used as it is. A diagram
    * of cycles (the default of [[Persistence]]), or one that does not say, has its class recovered by the cohomology of
    * that complex over the diagram's field, matched to the bar by its birth. Either way the cocycle is lifted to the
    * integers, checked there, and smoothed to the harmonic representative as in [[compute]].
    *
    * @param diagram
    *   a diagram of degrees up to at least 1, over `Z/p` for an odd prime `p` (the `characteristic` it was computed
    *   with), of a complex on integer vertices.
    * @param input
    *   what the diagram was computed from, as `Persistence` took it: points, a metric space or a stream.
    * @param bar
    *   the bar, counted among the diagram's H¹ bars from the most persistent, as in [[h1Bars]].
    * @param r
    *   the scale to compute at; it must lie in `[birth, death)` of the bar (default: the middle of the bar, or the end
    *   of the diagram for a bar that never dies).
    * @param complex
    *   the complex a point cloud is turned into, as in `Persistence(points, complex = ...)`.
    */
  def apply(
    diagram: PersistenceDiagram[Simplex[Int]],
    input: Persistence.Input[Simplex[Int]],
    bar: Int = 0,
    r: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips
  ): Result =
    import diagram.given
    val s = smoothClasses[diagram.Coefficient](diagram, input, Seq(bar), r.toOption, complex)
    Result(s.classes.head.theta, s.bounds.head._1, s.bounds.head._2, s.r, s.prime)

  /** Toroidal coordinates for several H¹ bars of a persistence diagram, as [[computeToroidal]] does for a metric space:
    * the bars must be alive together at `r` and on one connected piece of the complex.
    *
    * @param bars
    *   the bars, counted as in [[h1Bars]]; at least one, without repeats.
    * @param r
    *   the scale (default: the middle of the stretch where all the bars are alive).
    * @param reduce
    *   `false` keeps the classes as given, without the lattice reduction.
    */
  def toroidal(
    diagram: PersistenceDiagram[Simplex[Int]],
    input: Persistence.Input[Simplex[Int]],
    bars: Seq[Int],
    r: Optional[Double] = Optional.empty,
    complex: PointCloudComplex = VietorisRips,
    reduce: Boolean = true
  ): ToroidalResult =
    import diagram.given
    val s = smoothClasses[diagram.Coefficient](diagram, input, bars, r.toOption, complex)
    combineClasses(s.classes, bars, reduce, s.r, s.prime)

  private case class Smoothed(
    r: Double,
    prime: Int,
    bounds: IndexedSeq[(Double, Double)],
    classes: IndexedSeq[ClassData]
  )

  /** `stream` cut at filtration value `r` and simplex dimension 2: the static complex `K_r`, as a stream the cohomology
    * engine can read.
    */
  private class AtValue(stream: StratifiedCellStream[Simplex[Int], Double], r: Double)
      extends LevelwiseSimplexStream[Int, Double]
      with DoubleFiltration[Simplex[Int]]:
    override def homologyDegreeLimit: Option[Int] = Some(1)
    override def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
      case d if d >= 0 && d <= 2 && stream.iterateDimension.isDefinedAt(d) =>
        stream.iterateDimension(d).filter(cell => stream.filtrationValue(cell) <= r)
    }
    override def filtrationOrdering: Ordering[Simplex[Int]] = stream.filtrationOrdering
    override def filtrationValue: PartialFunction[Simplex[Int], Double] = stream.filtrationValue

  /** The characteristic of `C` by repeated addition: the prime `p` with `p * 1 = 0`, `0` when that never happens within
    * the largest prime a [[FiniteField]] has.
    */
  private def characteristicOf[C](using field: C is Field): Int =
    var sum = field.one
    var n = 1
    while n <= FiniteField.MaxPrime && !field.isEqual(sum, field.zero) do
      sum = field.plus(sum, field.one)
      n += 1
    if field.isEqual(sum, field.zero) then n else 0

  /** The integer `k`, `|k| <= (p - 1) / 2`, with `k * 1 = x`, for each element `x` of the prime field `C`. */
  private def centeredResidues[C](p: Int)(using field: C is Field): C => Int =
    val table = mutable.HashMap.empty[Int, List[(C, Int)]]
    def put(x: C, k: Int): Unit =
      val h = field.hash(x)
      table.update(h, (x, k) :: table.getOrElse(h, Nil))
    var multiple = field.zero
    put(multiple, 0)
    for k <- 1 to (p - 1) / 2 do
      multiple = field.plus(multiple, field.one)
      put(multiple, k)
      put(field.negate(multiple), -k)
    x =>
      table
        .getOrElse(field.hash(x), Nil)
        .collectFirst { case (y, k) if field.isEqual(x, y) => k }
        .getOrElse(throw new IllegalStateException(s"a coefficient of the diagram is not a residue mod $p"))

  private def sameValue(a: Double, b: Double): Boolean = a == b || math.abs(a - b) <= 1e-9 * math.max(1.0, math.abs(b))

  /** The chosen H¹ bars of `diagram`, each as a smoothed class on `K_r`: the work [[apply]] and [[toroidal]] share. */
  private def smoothClasses[C: Field](
    diagram: PersistenceDiagram.Of[Simplex[Int], C],
    input: Persistence.Input[Simplex[Int]],
    indices: Seq[Int],
    requestedR: Option[Double],
    complex: PointCloudComplex
  ): Smoothed =
    given realField: (Double is Field) = Field.DoubleApproximated(1e-9)
    val prime = characteristicOf[C]
    require(
      prime != 0,
      "circular coordinates need coefficients in Z/p for an odd prime p, to lift the cocycle to the integers, and this " +
        "diagram has real coefficients. Compute it with a prime `characteristic` (the default, 17, will do)."
    )
    require(
      prime % 2 != 0,
      "circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), and this " +
        "diagram is over Z/2. Compute it with `characteristic = 3` or larger (the default, 17, will do)."
    )
    require(indices.nonEmpty, "the bars to compute coordinates for must be non-empty")
    require(indices.distinct.length == indices.length, s"the bars must not contain duplicates, got $indices")
    require(
      diagram.maxDimension >= 1,
      s"circular coordinates come from degree 1, and this diagram has degrees up to ${diagram.maxDimension}: compute " +
        "it with `maxDimension = 1` or more"
    )
    val h1 = h1BarsOf(diagram).toIndexedSeq
    require(
      indices.forall(i => i >= 0 && i < h1.length),
      s"a bar must be in [0, ${h1.length}) -- this diagram has ${h1.length} H^1 bar(s) (`CircularCoordinates.h1Bars`), " +
        s"got $indices"
    )

    val chosen = indices.map(h1(_))
    val births = chosen.map(b => endpointValue(b.lower))
    val deaths = chosen.map(b => endpointValue(b.upper))
    val lo = births.max
    val hi = deaths.min
    val r = requestedR.getOrElse(
      if hi.isFinite then lo + (hi - lo) / 2 else math.max(lo, diagram.lastFiltrationValue)
    )
    require(
      r >= lo && r < hi,
      s"r=$r must lie in [birth, death) of every chosen bar -- they are alive together on [$lo, $hi)"
    )

    // K_r: the complex the diagram was computed on, cut at r. Only its edges and triangles are read.
    val stream = input.stream(1, Some(r), complex)
    require(
      stream.homologyDegreeLimit.forall(_ >= 1),
      "this complex was built for degree 0 only, without the triangles that tell cocycles from cochains: build it " +
        "with maxDimension = 1 or more"
    )
    val kr = new AtValue(stream, r)
    def cells(dimension: Int): Iterator[Simplex[Int]] =
      kr.iterateDimension.applyOrElse(dimension, (_: Int) => Iterator.empty[Simplex[Int]])
    val edges = cells(1).toVector
    val edgeSet = edges.toSet
    require(edges.nonEmpty, s"the complex has no edges at r=$r: is it the complex the diagram was computed on?")

    val toInt = centeredResidues[C](prime)
    def asIntegers(chain: Chain[Simplex[Int], C]): Map[Simplex[Int], Int] =
      chain.terms.map((cell, coefficient) => cell -> toInt(coefficient)).filter(_._2 != 0).toMap

    // A diagram of cocycles carries the class on each bar; otherwise it is recovered from the cohomology of K_r, where
    // the class is essential and found again by its birth. Computed once, for however many bars are asked for.
    val cocyclesOnBars = diagram.representatives.contains(Representatives.Cocycles)
    lazy val essentialAtKr: List[PersistenceBar[Double, Chain[Simplex[Int], C]]] =
      CellularCohomologyEngine[Simplex[Int], C, Double]()
        .persistentCohomology(kr)
        .filter(b => b.dim == 1 && !endpointValue(b.upper).isFinite)

    val classes: IndexedSeq[ClassData] =
      indices.zip(chosen).zip(births).toIndexedSeq.map { case ((index, bar), birth) =>
        val z: Map[Simplex[Int], Int] =
          bar.annotation match
            case Some(cocycle) if cocyclesOnBars =>
              require(
                cocycle.terms.forall(_._1.dim == 1),
                s"bar $index: its representative has cells that are not edges, so it is not a degree-1 cocycle"
              )
              // Restriction to K_r: the cells it has past r (up to the bar's death) are not in the complex.
              asIntegers(cocycle).filter((edge, _) => edgeSet.contains(edge))
            case _ =>
              val matches = essentialAtKr.filter(b => sameValue(endpointValue(b.lower), birth))
              require(
                matches.nonEmpty,
                s"bar $index: no essential H^1 class of the complex at r=$r is born at $birth. Is it the complex the " +
                  "diagram was computed on (the same input and `complex = ...`)?"
              )
              require(
                matches.length == 1,
                s"bar $index: ${matches.length} essential H^1 classes at K_r (r=$r) are born at $birth -- cannot " +
                  "disambiguate which one is the bar; this is a genuine tie in the data, not resolvable automatically"
              )
              asIntegers(
                matches.head.annotation.getOrElse(
                  throw new IllegalStateException(
                    "essential bar has no recorded cocycle representative -- an engine bug"
                  )
                )
              )
        require(
          z.nonEmpty,
          s"bar $index: its cocycle is zero on every edge of K_r (r=$r), so there is no class to compute. Is the complex " +
            "the one the diagram was computed on?"
        )
        try verifyIntegerCocycleOn(cells(2), z, prime)
        catch
          case e: NoIntegerCocycleException =>
            throw new NoIntegerCocycleException(s"bar $index: ${e.getMessage}")
        val (theta, harmonic, component) = harmonicSmooth(edges, z)
        require(
          harmonic.values.exists(h => math.abs(h) > 1e-9),
          s"bar $index: its class is zero in the cohomology of K_r (r=$r), so it has no circle coordinate. Is the " +
            "complex the one the diagram was computed on?"
        )
        ClassData(component, theta, harmonic)
      }

    Smoothed(r, prime, births.zip(deaths).toIndexedSeq, classes)

  // ---- Sparse coordinates (Perea) ------------------------------------------------------------------------------------

  /** Perea's sparse circular coordinates for one H¹ bar of the Dowker diagram of `relation` (see the class doc): rows
    * are landmarks, or any cover, columns the points, and `relation(l)(x) < r` puts point `x` in the cover set of `l`.
    * The coordinate is computed on the nerve of the cover at `r`, and every point that cover sets of the loop's
    * connected component hold gets one.
    *
    * {{{
    * val geometry = WitnessGeometry(metricSpace, landmarks)   // the distances from each landmark to every point
    * val diagram = Persistence(Dowker(geometry.D, maxDimension = 1))
    * CircularCoordinates.sparse(diagram, geometry.D).theta     // a coordinate for every point
    * }}}
    *
    * @param diagram
    *   the diagram of `Dowker(relation, ...)` (on the rows, `dual = false`), with degrees up to at least 1, over `Z/p`
    *   for an odd prime `p`. With cocycles its own are used, otherwise they are recovered as in [[apply]].
    * @param bar
    *   the bar, counted among the diagram's H¹ bars from the most persistent, as in [[h1Bars]].
    * @param r
    *   the radius of the cover sets; it must lie in `[birth, death)` of the bar (default: the middle of the bar).
    */
  def sparse(
    diagram: PersistenceDiagram[Simplex[Int]],
    relation: Array[Array[Double]],
    bar: Int = 0,
    r: Optional[Double] = Optional.empty
  ): Result =
    import diagram.given
    val s = sparseClasses[diagram.Coefficient](diagram, relation, Seq(bar), r.toOption)
    Result(s.classes.head.theta, s.bounds.head._1, s.bounds.head._2, s.r, s.prime)

  /** [[toroidal]] with sparse coordinates: several H¹ bars of the Dowker diagram of `relation`, alive together at `r`,
    * combined into one torus-valued map on every point the cover holds, as [[sparse]] does for one.
    */
  def sparseToroidal(
    diagram: PersistenceDiagram[Simplex[Int]],
    relation: Array[Array[Double]],
    bars: Seq[Int],
    r: Optional[Double] = Optional.empty,
    reduce: Boolean = true
  ): ToroidalResult =
    import diagram.given
    val s = sparseClasses[diagram.Coefficient](diagram, relation, bars, r.toOption)
    combineClasses(s.classes, bars, reduce, s.r, s.prime)

  /** The classes smoothed on the nerve of the cover at `r`, each extended to the points. */
  private def sparseClasses[C: Field](
    diagram: PersistenceDiagram.Of[Simplex[Int], C],
    relation: Array[Array[Double]],
    indices: Seq[Int],
    requestedR: Option[Double]
  ): Smoothed =
    val geometry = DowkerGeometry(relation) // checks the relation
    val nerve = new Persistence.Input[Simplex[Int]]:
      def stream(maxDimension: Int, maxFiltrationValue: Option[Double], complex: PointCloudComplex) =
        Dowker(relation, maxDimension, maxFiltrationValue.getOrElse(Double.PositiveInfinity))
      def scale = None
      def cells = summon[Simplex[Int] is OrderedCell]
    val onNerve = smoothClasses[C](diagram, nerve, indices, requestedR, VietorisRips)
    val onPoints = onNerve.classes.map(extendToPoints(_, geometry.relation, onNerve.r))
    require(
      onPoints.forall(_.theta.nonEmpty),
      s"no point is within r=${onNerve.r} of a landmark of the loop: choose a larger r inside the bar"
    )
    onNerve.copy(classes = onPoints)

  /** Perea's formula (11), in this file's orientation (`g(b) - g(a) = z - h` on the edge `[a, b]`): the point `x`, in
    * the cover set of its nearest landmark `j`, gets `g(j) - sum_k phi_k(x) h(j -> k)`, with the partition of unity
    * `phi_k(x)` proportional to `max(r - relation(k)(x), 0)` (his (13)). Any `j` whose set holds `x` gives the same
    * value mod 1, because `h` is a cocycle on the triangles the sets of `x` span.
    */
  private def extendToPoints(cls: ClassData, relation: Array[Array[Double]], r: Double): ClassData =
    val rows = relation.length
    val columns = relation(0).length
    val inComponent = Array.tabulate(rows)(l => cls.component.contains(Simplex(l)))
    def harmonicFrom(j: Int, k: Int): Double =
      val h = cls.harmonic.getOrElse(
        Simplex(math.min(j, k), math.max(j, k)),
        throw new IllegalStateException(s"the edge {$j, $k} of the nerve at r=$r has no harmonic value")
      )
      if j < k then h else -h
    val weight = new Array[Double](rows)
    val theta = mutable.HashMap.empty[Int, Double]
    var x = 0
    while x < columns do
      var total = 0.0
      var nearest = -1
      var l = 0
      while l < rows do
        val w = r - relation(l)(x)
        weight(l) = if w > 0 then w else 0.0
        total += weight(l)
        if weight(l) > 0 && (nearest < 0 || weight(l) > weight(nearest)) then nearest = l
        l += 1
      if nearest >= 0 && inComponent(nearest) then
        var value = cls.theta(nearest)
        l = 0
        while l < rows do
          if l != nearest && weight(l) > 0 then value -= weight(l) / total * harmonicFrom(nearest, l)
          l += 1
        theta(x) = value - math.floor(value)
      x += 1
    cls.copy(theta = theta.toMap)

  /** `n` maxmin landmarks of `metricSpace`, their distances to every point, and the Dowker diagram of those distances
    * with cocycles over `Z/prime`: what `numLandmarks = n` computes on. Cut at `maxFiltrationValue`, or where one point
    * is within reach of every landmark, after which the nerve is a cone and has no loops.
    */
  private def landmarkCover(
    metricSpace: FiniteMetricSpace[Int],
    numLandmarks: Int,
    maxFiltrationValue: Option[Double],
    prime: Int
  ): (PersistenceDiagram[Simplex[Int]], Array[Array[Double]]) =
    require(
      numLandmarks <= metricSpace.size,
      s"numLandmarks must be at most the number of points, ${metricSpace.size}, got $numLandmarks"
    )
    val relation = WitnessGeometry(metricSpace, LandmarkSelector.maxmin(metricSpace, numLandmarks).landmarks).D
    val cone = relation(0).indices.map(x => relation.iterator.map(_(x)).max).min
    val diagram = Persistence(
      Dowker(relation, maxDimension = 1, maxFiltrationValue = maxFiltrationValue.getOrElse(cone)),
      characteristic = prime,
      representatives = Representatives.Cocycles
    )
    (diagram, relation)

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
    verifyIntegerCocycleOn(stream.iterator.filter(_.dim == 2), z, prime)

  /** [[verifyIntegerCocycle]] on the triangles themselves. A cochain that is not even a cocycle mod `prime` (a
    * representative of another complex, or a cycle rather than a cocycle) is a different error from a class without an
    * integer lift, and is reported as one.
    */
  private def verifyIntegerCocycleOn(
    triangles: Iterator[Simplex[Int]],
    z: Map[Simplex[Int], Int],
    prime: Int
  )(using Double is Field): Unit =
    for triangle <- triangles do
      val boundaryEdges = triangle.boundary[Double] // (edge, +-1.0) pairs -- sign only, magnitude always 1 here
      val total = boundaryEdges.map((edge, sign) => math.round(sign).toInt * z.getOrElse(edge, 0)).sum
      if total % prime != 0 then
        throw new IllegalArgumentException(
          "the representative is not a cocycle of this complex: its coboundary on triangle " +
            s"${triangle.underlying.mkString("[", ",", "]")} is $total mod $prime, not 0. Pass the complex the " +
            "diagram was computed on (the same `complex = ...`, or the same stream)."
        )
      if total != 0 then
        throw new NoIntegerCocycleException(
          s"the chosen cocycle has no exact integer lift at prime=$prime: triangle " +
            s"${triangle.underlying.mkString("[", ",", "]")}'s integer boundary sums to $total, not 0. Either the " +
            "underlying cohomology class is genuinely torsion (no real/integer lift exists at any prime), or this " +
            "prime is too small relative to the true integer cocycle's own magnitudes -- retry with a larger prime."
        )
