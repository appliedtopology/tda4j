package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.apache.commons.math3.linear.{ArrayRealVector, ConjugateGradient, RealLinearOperator, RealVector}

import scala.collection.mutable

/** No valid `Z`-lift of the chosen cocycle exists for the chosen `prime` -- either the underlying cohomology class is
  * genuinely torsion (no real/integer lift can exist at any prime -- an RP²-type class is the standard example), or
  * `prime` was too small relative to the true integer cocycle's own magnitudes for the mod-`prime` reduction to be
  * injective on the relevant range (retry with a larger prime). Thrown rather than silently coordinatizing against a
  * mod-`prime` mirage -- see `.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 2's own framing: "`∂(ℤ-lift) =
  * 0` must be a runtime check, not assumed."
  */
class NoIntegerCocycleException(message: String) extends RuntimeException(message)

/** Circular coordinates (de Silva, Morozov, Vejdemo-Johansson, "Persistent Cohomology and Circular Coordinates,"
  * Discrete & Computational Geometry 45:737-759, 2011): given a persistent H¹ class of a Vietoris-Rips complex, produce
  * a map from (a connected subset of) the point cloud to the circle `R/Z` representing that class -- a genuinely
  * topological coordinate capturing periodic/cyclic structure in data. `.claude/WORKLOG-mainstream-
  * feature-gap-analysis.md` item 2, including the user's own reframing of the original open question (see that worklog
  * for the full derivation this implementation follows) and cross-checked against a real reference implementation
  * (`scikit-tda/DREiMac`'s `toroidalcoords.py`, fetched directly -- not recalled from memory, matching this codebase's
  * own io-module verification ethos) for the exact harmonic-smoothing linear system and the "coordinate is literally
  * the smoothed potential itself, mod 1" formula, which is less obvious from the paper's own more abstract framing than
  * it looks once seen written out as code.
  *
  * '''The reframing''' (this is what makes the construction tractable): rather than asking whether a *finite* H¹ bar's
  * representative restricts to a nonzero cocycle on some sub-level complex `K_r` (an open question about an
  * already-computed representative), fix `r` inside the target bar's own `[birth, death)` range up front, build the
  * *static* truncated complex `K_r` (`maxFiltrationValue = Some(r)`, the same knob that already implements
  * enclosing-radius truncation, plus a cell-dimension cap so `CellularCohomologyContext` -- which fully materializes
  * its input, no `maxDim` of its own -- doesn't build cells above what H¹ needs), and compute cohomology of *that fixed
  * complex* directly. The target class is essential there *by construction* (nothing survives past `r` in a view that
  * stops at `r`) -- the verification question dissolves rather than needing an answer. Matching multiple
  * simultaneously-alive classes at `K_r` back to a specific full-filtration bar turns out to need only a birth-value
  * comparison, not a more elaborate algorithm: `K_r`'s own persistent cohomology (fed the same filtration values, just
  * cut off at `r`) assigns every bar the SAME birth it would have in the full computation (truncating the end of a
  * filtration cannot change how early something is born), so an essential bar at `K_r` with birth `b` is unambiguously
  * "the same" class as a full-computation bar with that same birth `b`, found by direct comparison -- no separate
  * matching machinery needed.
  *
  * '''Harmonic smoothing''': the chosen cocycle `z` (an integer 1-cochain, lifted from a large-prime field
  * representative -- see `prime`'s own doc) is smoothed by solving `min_g ||z - d0 g||^2` for a real-valued vertex
  * function `g` (`d0`, the 0-coboundary map, is `(d0 g)(edge [i,j]) = g(j) - g(i)`), via the normal equations
  * `d0^T d0 g = d0^T z` -- a sparse SPD least-squares solve, not "optimization" in the LP/QP sense. Solved matrix-free
  * (`org.apache.commons.math3.linear.ConjugateGradient` against a `RealLinearOperator` built directly from
  * `Simplex.boundary[Double]`, no dense matrix ever materialized, no new dependency -- `commons-math3` is already
  * vendored) over the connected component of `K_r`'s 1-skeleton containing the cocycle's own support (a class is only
  * meaningful there -- other components have no path along which it could be defined at all), with one
  * arbitrarily-chosen vertex in that component anchored at `g = 0` to make the reduced system genuinely positive
  * *definite*, not just semi-definite (the unreduced graph Laplacian is singular on constants, one dimension of null
  * space per connected component -- anchoring one vertex removes exactly that one dimension, rather than disabling
  * `ConjugateGradient`'s own positive-definiteness check and hoping).
  *
  * The output coordinate is then, remarkably directly, `theta(v) = frac(g(v))`: no separate path-integration step is
  * needed (confirmed against DREiMac's own code, not derived from the paper's more abstract statement alone).
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

  /** The `(birth, death)` range of every persistent H¹ class of `metricSpace`'s Vietoris-Rips complex, sorted by
    * persistence descending -- index `i` here is exactly `compute`'s own `cocycleIndex = i`. A caller has no way to
    * pick a meaningful `r` for `compute` without first knowing a target bar's own range, so this is the intended first
    * call, not merely a diagnostic. Computed over `Double` coefficients (this library's usual default for reading off
    * bar values) regardless of the `prime` a later `compute` call will use -- the `(birth, death)` values themselves
    * agree across coefficient fields for any class `compute` could actually succeed on (a genuinely torsion class,
    * where they might not, is exactly the case `compute` itself reports via [[NoIntegerCocycleException]] rather than
    * silently coordinatizing).
    */
  def h1Bars(
    metricSpace: FiniteMetricSpace[Int],
    maxFiltrationValue: Option[Double] = None
  ): IndexedSeq[(Double, Double)] =
    given Double is Field = Field.DoubleApproximated(1e-9)
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = maxFiltrationValue),
      2
    )
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
    *   `[birth, death)` of the `cocycleIndex`-th class (checked; an actionable message names the valid range, since
    *   picking `r` is a real, data-dependent choice this method cannot make for the caller -- see the class doc's
    *   "reframing" paragraph for why this parameter exists at all).
    * @param cocycleIndex
    *   selects which persistent H¹ class to coordinatize, `0` = the most persistent (matching DREiMac's own
    *   `cocycle_idx` convention, checked directly rather than assumed) -- ties broken by this codebase's own
    *   `Ordering`/sort stability, not meaningful to rely on.
    * @param prime
    *   the field cohomology is computed over before lifting to an integer cocycle -- must be an ODD prime (not the
    *   library-wide default of `2`: an RP²-type class exists over `F_2` with no real/integer lift at all, so a mod-2
    *   "cocycle" can be a mirage for coordinatization here specifically, even though `F_2` is perfectly fine for
    *   ordinary barcodes). "Large-ish" per the originating worklog: large enough that the true integer cocycle's own
    *   entries don't exceed the field's centered representative range and wrap around -- `47` is an unremarkable
    *   default, not a value with any special significance; raise it if [[NoIntegerCocycleException]] is thrown and the
    *   class is not, in fact, torsion.
    * @param maxFiltrationValue
    *   truncation for the FULL computation used only to pick the target bar (`None` defaults to the metric space's own
    *   minimum enclosing radius, this library's usual convention) -- unrelated to `r`, which truncates the separate,
    *   smaller complex actually used for cohomology.
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
  private[homology] def harmonicSmoothOnComponent(
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
    * Circular Coordinates With Lattice Reduction," arXiv:2212.07201): circular coordinates for SEVERAL
    * simultaneously-alive persistent H¹ classes, combined into one torus-valued map, with the combination chosen by
    * [[LatticeReduction]] rather than left to whatever a cohomology computation's pivot order happened to produce.
    *
    * '''Why this exists''' (Edelsbrunner's own point in the original circular-coordinates Q&A, per the project lead):
    * given `k` independent H¹ generators, ANY unimodular integer combination of them is an equally valid choice of
    * generators for the same rank-`k` sublattice of `H^1(K_r; Z)` -- so "the" `k` circular coordinates a cohomology
    * computation hands back are arbitrary, not canonical, and could in principle be an arbitrarily skewed mix of
    * whatever a data set's "obviously" independent cycles are. This picks the combination that's shortest and most
    * nearly orthogonal under the classes' own harmonic-representative inner product (the paper's dSMV form: the plain
    * sum-over-edges dot product of two harmonic 1-cochains, i.e. the discrete Dirichlet form up to a constant) via LLL.
    *
    * @param cocycleIndices
    *   which persistent H¹ classes to combine (same indexing as `compute`'s own `cocycleIndex` / `h1Bars`), at least
    *   one, no duplicates. Every chosen class's own `[birth, death)` must contain `r` (checked -- an actionable message
    *   names the empty intersection otherwise), generalizing `compute`'s own single-class requirement to
    *   "simultaneously alive," not a new constraint. The chosen classes must also all be supported on the SAME
    *   connected component of `K_r`'s 1-skeleton (checked -- two classes native to two different components of a
    *   disconnected `K_r` have no joint domain to be coordinatized on at all).
    * @param reduce
    *   `true` (default) applies the lattice reduction; `false` returns the SAME `k` coordinates un-reduced (still
    *   validated/matched/lifted identically, `basisChange` the identity) -- so a caller can compare directly against
    *   the reduced version, or opt out entirely if the raw persistent-cohomology basis is already what they want.
    */
  def computeToroidal(
    metricSpace: FiniteMetricSpace[Int],
    r: Double,
    cocycleIndices: Seq[Int],
    prime: Int = 47,
    reduce: Boolean = true,
    maxFiltrationValue: Option[Double] = None
  ): ToroidalResult =
    require(
      prime % 2 != 0 && isPrime(prime),
      s"circular coordinates need an odd prime (p=2 can hide torsion classes with no real/integer lift), got $prime"
    )
    val ff = new FiniteField(prime)
    import ff.given
    computeToroidalGeneric[ff.Fp](metricSpace, r, cocycleIndices, prime, reduce, maxFiltrationValue, _.toInt)

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

    val ctx = CellularCohomologyContext[Simplex[Int], C, Double]()

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
