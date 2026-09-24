package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.apache.commons.math3.linear.{ArrayRealVector, ConjugateGradient, RealLinearOperator, RealVector}

import scala.collection.mutable

/** No valid `Z`-lift of the chosen cocycle exists for the chosen `prime` -- either the underlying cohomology class
  * is genuinely torsion (no real/integer lift can exist at any prime -- an RP²-type class is the standard example),
  * or `prime` was too small relative to the true integer cocycle's own magnitudes for the mod-`prime` reduction to
  * be injective on the relevant range (retry with a larger prime). Thrown rather than silently coordinatizing
  * against a mod-`prime` mirage -- see `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 2's own framing:
  * "`∂(ℤ-lift) = 0` must be a runtime check, not assumed."
  */
class NoIntegerCocycleException(message: String) extends RuntimeException(message)

/** Circular coordinates (de Silva, Morozov, Vejdemo-Johansson, "Persistent Cohomology and Circular Coordinates,"
  * Discrete & Computational Geometry 45:737-759, 2011): given a persistent H¹ class of a Vietoris-Rips complex,
  * produce a map from (a connected subset of) the point cloud to the circle `R/Z` representing that class -- a
  * genuinely topological coordinate capturing periodic/cyclic structure in data. `.claude/WORKLOG-mainstream-
  * feature-gap-analysis.md` item 2, including the user's own reframing of the original open question (see that
  * worklog for the full derivation this implementation follows) and cross-checked against a real reference
  * implementation (`scikit-tda/DREiMac`'s `toroidalcoords.py`, fetched directly -- not recalled from memory,
  * matching this codebase's own io-module verification ethos) for the exact harmonic-smoothing linear system and
  * the "coordinate is literally the smoothed potential itself, mod 1" formula, which is less obvious from the
  * paper's own more abstract framing than it looks once seen written out as code.
  *
  * '''The reframing''' (this is what makes the construction tractable): rather than asking whether a *finite* H¹
  * bar's representative restricts to a nonzero cocycle on some sub-level complex `K_r` (an open question about an
  * already-computed representative), fix `r` inside the target bar's own `[birth, death)` range up front, build the
  * *static* truncated complex `K_r` (`maxFiltrationValue = Some(r)`, the same knob that already implements
  * enclosing-radius truncation, plus a cell-dimension cap so `CellularCohomologyContext` -- which fully
  * materializes its input, no `maxDim` of its own -- doesn't build cells above what H¹ needs), and compute
  * cohomology of *that fixed complex* directly. The target class is essential there *by construction* (nothing
  * survives past `r` in a view that stops at `r`) -- the verification question dissolves rather than needing an
  * answer. Matching multiple simultaneously-alive classes at `K_r` back to a specific full-filtration bar turns out
  * to need only a birth-value comparison, not a more elaborate algorithm: `K_r`'s own persistent cohomology (fed
  * the same filtration values, just cut off at `r`) assigns every bar the SAME birth it would have in the full
  * computation (truncating the end of a filtration cannot change how early something is born), so an essential
  * bar at `K_r` with birth `b` is unambiguously "the same" class as a full-computation bar with that same birth
  * `b`, found by direct comparison -- no separate matching machinery needed.
  *
  * '''Harmonic smoothing''': the chosen cocycle `z` (an integer 1-cochain, lifted from a large-prime field
  * representative -- see `prime`'s own doc) is smoothed by solving `min_g ||z - d0 g||^2` for a real-valued vertex
  * function `g` (`d0`, the 0-coboundary map, is `(d0 g)(edge [i,j]) = g(j) - g(i)`), via the normal equations
  * `d0^T d0 g = d0^T z` -- a sparse SPD least-squares solve, not "optimization" in the LP/QP sense. Solved
  * matrix-free (`org.apache.commons.math3.linear.ConjugateGradient` against a `RealLinearOperator` built directly
  * from `Simplex.boundary[Double]`, no dense matrix ever materialized, no new dependency -- `commons-math3` is
  * already vendored) over the connected component of `K_r`'s 1-skeleton containing the cocycle's own support (a
  * class is only meaningful there -- other components have no path along which it could be defined at all), with
  * one arbitrarily-chosen vertex in that component anchored at `g = 0` to make the reduced system genuinely
  * positive *definite*, not just semi-definite (the unreduced graph Laplacian is singular on constants, one
  * dimension of null space per connected component -- anchoring one vertex removes exactly that one dimension,
  * rather than disabling `ConjugateGradient`'s own positive-definiteness check and hoping).
  *
  * The output coordinate is then, remarkably directly, `theta(v) = frac(g(v))`: no separate path-integration step
  * is needed (confirmed against DREiMac's own code, not derived from the paper's more abstract statement alone).
  */
object CircularCoordinates:

  /** `theta`: ambient point index (matching the `metricSpace` passed to `compute`) to its circle coordinate in
    * `[0, 1)` -- only for points in the connected component of `K_r` containing the chosen class (see the class
    * doc); a point outside that component has no entry at all, not a sentinel value. `birth`/`death` are the
    * chosen bar's own full-filtration endpoints (`death = Double.PositiveInfinity` for an essential bar); `r` and
    * `prime` echo the parameters `compute` was called with.
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

  /** The `(birth, death)` range of every persistent H¹ class of `metricSpace`'s Vietoris-Rips complex, sorted by
    * persistence descending -- index `i` here is exactly `compute`'s own `cocycleIndex = i`. A caller has no way
    * to pick a meaningful `r` for `compute` without first knowing a target bar's own range, so this is the
    * intended first call, not merely a diagnostic. Computed over `Double` coefficients (this library's usual
    * default for reading off bar values) regardless of the `prime` a later `compute` call will use -- the
    * `(birth, death)` values themselves agree across coefficient fields for any class `compute` could actually
    * succeed on (a genuinely torsion class, where they might not, is exactly the case `compute` itself reports via
    * [[NoIntegerCocycleException]] rather than silently coordinatizing).
    */
  def h1Bars(metricSpace: FiniteMetricSpace[Int], maxFiltrationValue: Option[Double] = None): IndexedSeq[(Double, Double)] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val stream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue), 2)
    val ctx = CellularCohomologyContext[Simplex[Int], Double, Double]()
    ctx
      .persistentCohomology(stream)
      .filter(_.dim == 1)
      .sortBy(b => -persistenceOf(b))
      .map(b => (endpointValue(b.lower), endpointValue(b.upper)))
      .toIndexedSeq

  /** Circular coordinates for one persistent H¹ class of `metricSpace`'s Vietoris-Rips complex.
    *
    * @param metricSpace
    *   the point cloud (or precomputed distance data) to coordinatize.
    * @param r
    *   the fixed threshold defining the static complex `K_r` cohomology is actually computed on -- must lie in
    *   `[birth, death)` of the `cocycleIndex`-th class (checked; an actionable message names the valid range,
    *   since picking `r` is a real, data-dependent choice this method cannot make for the caller -- see the class
    *   doc's "reframing" paragraph for why this parameter exists at all).
    * @param cocycleIndex
    *   selects which persistent H¹ class to coordinatize, `0` = the most persistent (matching DREiMac's own
    *   `cocycle_idx` convention, checked directly rather than assumed) -- ties broken by this codebase's own
    *   `Ordering`/sort stability, not meaningful to rely on.
    * @param prime
    *   the field cohomology is computed over before lifting to an integer cocycle -- must be an ODD prime (not the
    *   library-wide default of `2`: an RP²-type class exists over `F_2` with no real/integer lift at all, so a
    *   mod-2 "cocycle" can be a mirage for coordinatization here specifically, even though `F_2` is perfectly fine
    *   for ordinary barcodes). "Large-ish" per the originating worklog: large enough that the true integer
    *   cocycle's own entries don't exceed the field's centered representative range and wrap around -- `47` is an
    *   unremarkable default, not a value with any special significance; raise it if [[NoIntegerCocycleException]]
    *   is thrown and the class is not, in fact, torsion.
    * @param maxFiltrationValue
    *   truncation for the FULL computation used only to pick the target bar (`None` defaults to the metric space's
    *   own minimum enclosing radius, this library's usual convention) -- unrelated to `r`, which truncates the
    *   separate, smaller complex actually used for cohomology.
    */
  def compute(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int = 0,
    prime: Int = 47,
    maxFiltrationValue: Option[Double] = None
  ): Result =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    val ff = new FiniteField(prime)
    import ff.given
    computeGeneric[ff.Fp](metricSpace, r, cocycleIndex, prime, maxFiltrationValue, _.toInt)

  private def computeGeneric[C: Field](
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndex: Int,
    prime: Int,
    maxFiltrationValue: Option[Double],
    toInt: C => Int
  ): Result =
    given realField: (Double is Field) = Field.DoubleApproximated(1e-9)

    val ctx = CellularCohomologyContext[Simplex[Int], C, Double]()

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

    // Connected component of K_r's 1-skeleton containing the cocycle's own support -- a class is only meaningful
    // (has a path to be integrated along) within it; other components get no theta value at all, not a sentinel.
    val allEdges: Vector[Simplex[Int]] = krStream.iterator.filter(_.dim == 1).toVector
    val adjacency: Map[Simplex[Int], Vector[Simplex[Int]]] =
      allEdges.flatMap(edge => edge.boundary[Double].map(_._1).map(v => v -> edge)).groupMap(_._1)(_._2)
    val seedVertex: Simplex[Int] = zInt.keys.head.boundary[Double].head._1
    val component: Set[Simplex[Int]] = bfsComponent(seedVertex, adjacency)
    val componentEdges: Vector[Simplex[Int]] =
      allEdges.filter(e => e.boundary[Double].forall((v, _) => component.contains(v)))

    // Harmonic smoothing: min_g ||z - d0 g||^2, anchoring one component vertex at g=0 (see class doc).
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

    def frac(x: Double): Double = x - math.floor(x)
    val theta: Map[Int, Double] =
      (reducedVertices.indices.map(i => reducedVertices(i) -> gReduced.getEntry(i)) :+ (anchor -> 0.0))
        .map((v, g) => v.underlying.head -> frac(g))
        .toMap

    Result(theta, birth, death, r, prime)

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

  /** Requires `delta(z) = 0` EXACTLY as integers for every triangle of `stream` -- not merely mod `prime` (which
    * `z`'s own construction, a cohomology computation over a prime field, already guarantees trivially and proves
    * nothing) -- throwing [[NoIntegerCocycleException]] naming the offending triangle if it fails anywhere.
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
