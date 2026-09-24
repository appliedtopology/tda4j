package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import org.appliedtopology.tda4j.barcode.*
import org.appliedtopology.tda4j.homology.HomologyFixtures
import org.appliedtopology.tda4j.matlab.TDA4j

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck
import org.scalacheck.*

import scala.collection.mutable as scmutable

/** `LandmarkSelector`/`WitnessGeometry`/`WitnessMetricSpace`/`LazyWitnessSimplexStream`/`WitnessCofaceSimplexStream`
  * correctness (`WitnessStream.scala`) -- the first exercise of a witness complex anywhere in this codebase, built on
  * `RipserCofaceSimplexStream`'s generic coface-generation loop (the lazy stream, exactly like
  * `CechCofaceSimplexStream` reuses it) plus a `filtrationValueOverride` (the general stream). Checked against
  * JavaPlex's own `LazyWitnessStream`/`WitnessStream` Java source, not just the De Silva-Carlsson paper's prose -- see
  * `.claude/WORKLOG-witness-complex.md`.
  *
  * Validation order follows this codebase's established convention for a new complex type (see `CechStreamSpec`'s own
  * header): a hand-derived fixture chosen to discriminate a specific wrong-implementation shape (forgetting the "max
  * with facets" step), an independent from-scratch brute-force reimplementation of the De Silva-Carlsson formula
  * cross-checked cell-for-cell, the general-vs-lazy(nu=2) cross-check the two constructions' shared math predicts,
  * monotonicity/downward-closure checks, the bars-account-for-cells structural invariant, and a cross-check that
  * `PackedRipserCohomologyContext` (proven only for genuine VR diameters -- CLAUDE.md) also happens to be valid for the
  * lazy stream specifically, since it IS a diameter under `WitnessMetricSpace`'s own "distance."
  */
class WitnessStreamSpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  given Arbitrary[Array[Array[Double]]] =
    Arbitrary(matrixGen[Double](Gen.double, Gen.const(2), Gen.chooseNum(3, 6)))

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived fixture: P0=(0,0), P1=(1,0), P2=(3,0), P3=(7,0) on a line (so every pairwise distance is an
  // exact integer, no floating-point tolerance needed anywhere below). Landmarks = {P0,P1,P2} (local indices
  // 0,1,2), witnesses = all four points (P3 witnesses but is never a landmark). Chosen specifically because the
  // triangle {0,1,2}'s OWN witness value (own_2 = 0) is STRICTLY LESS than its facets' max (1) -- a bug that
  // used raw own_k alone, without maxing against already-computed facet values, would report fv(triangle) = 0
  // instead of 1, silently violating monotonicity (a coface reported as EARLIER than one of its own faces).
  // ---------------------------------------------------------------------------------------------------------

  private val linePoints = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(3.0, 0.0), Array(7.0, 0.0))
  private val lineAmbient = EuclideanMetricSpace(linePoints)
  private val lineLandmarks = IndexedSeq(0, 1, 2)
  private val lineGeometry = WitnessGeometry(lineAmbient, lineLandmarks)

  "Hand-derived fixture: WitnessMetricSpace(nu=2) gives d(0,1)=0, d(1,2)=0, d(0,2)=1 -- and is NOT a metric " +
    "(the triangle inequality fails: 1 > 0 + 0)" >> {
      val wms = WitnessMetricSpace(lineGeometry, nu = 2)
      (wms.distance(0, 1) must beEqualTo(0.0)) and
        (wms.distance(1, 2) must beEqualTo(0.0)) and
        (wms.distance(0, 2) must beEqualTo(1.0)) and
        (wms.distance(0, 2) must be_>(wms.distance(0, 1) + wms.distance(1, 2)))
    }

  "Hand-derived fixture: the general witness complex's edges exactly match WitnessMetricSpace(nu=2)'s, and its " +
    "triangle's filtration value is 1 (the facet max), NOT 0 (its own raw witness value) -- the discriminator " +
    "against dropping the 'max with facets' step" >> {
      val fv = WitnessCofaceSimplexStream.recursiveFiltrationValue(lineGeometry)
      (fv(Simplex(0, 1)) must beEqualTo(0.0)) and
        (fv(Simplex(1, 2)) must beEqualTo(0.0)) and
        (fv(Simplex(0, 2)) must beEqualTo(1.0)) and
        (fv(Simplex(0, 1, 2)) must beEqualTo(1.0))
    }

  // ---------------------------------------------------------------------------------------------------------
  // LandmarkSelector: deterministic tie-breaking, and an independent recomputation of the covering radius the
  // greedy loop tracks incrementally.
  // ---------------------------------------------------------------------------------------------------------

  "maxmin breaks a covering-distance tie by choosing the LOWEST ambient index" >> {
    // Points at 0, +5, -5: from firstLandmark=0, points 1 and 2 are BOTH at distance 5 -- index 1 must win.
    val ms = EuclideanMetricSpace(Array(Array(0.0), Array(5.0), Array(-5.0)))
    val selection = LandmarkSelector.maxmin(ms, numLandmarks = 2, firstLandmark = 0)
    selection.landmarks must beEqualTo(IndexedSeq(0, 1))
  }

  "maxmin's own reported covering radius matches an independent brute-force recomputation, on random clouds" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(1, points.length - 1)
        val selection = LandmarkSelector.maxmin(ms, numLandmarks)
        val bruteRadius = ms.elements.map(x => selection.landmarks.map(l => ms.distance(x, l)).min).max
        (selection.landmarks.distinct.size must beEqualTo(numLandmarks)) and
          (selection.landmarks.forall(ms.contains) must beTrue) and
          (selection.coveringRadius must beCloseTo(bruteRadius, 1e-9))
      }
    }

  "random landmark selection is reproducible given the same seed, and gives the requested count of distinct, " +
    "in-range indices" >> {
      val ms = EuclideanMetricSpace(linePoints)
      val a = LandmarkSelector.random(ms, numLandmarks = 3, seed = 42L)
      val b = LandmarkSelector.random(ms, numLandmarks = 3, seed = 42L)
      (a.landmarks must beEqualTo(b.landmarks)) and
        (a.landmarks.distinct.size must beEqualTo(3)) and
        (a.landmarks.forall(ms.contains) must beTrue)
    }

  "WitnessGeometry/WitnessMetricSpace reject an out-of-range nu or an invalid landmark set" >> {
    (WitnessMetricSpace(lineGeometry, nu = 3) must throwAn[IllegalArgumentException]) and
      (WitnessMetricSpace(lineGeometry, nu = -1) must throwAn[IllegalArgumentException]) and
      (WitnessGeometry(lineAmbient, IndexedSeq(0, 99)) must throwAn[IllegalArgumentException])
  }

  // ---------------------------------------------------------------------------------------------------------
  // Independent brute-force oracle: reimplemented from scratch, straight from the formula (and cross-checked
  // against JavaPlex's own Java source, not shared code with WitnessGeometry/WitnessCofaceSimplexStream), so
  // this is a genuine second implementation, not a self-consistency check of the production code against itself.
  // ---------------------------------------------------------------------------------------------------------

  private def bruteDistance(a: Array[Double], b: Array[Double]): Double =
    math.sqrt(a.zip(b).map((x, y) => (x - y) * (x - y)).sum)

  private def bruteD(points: Array[Array[Double]], landmarks: IndexedSeq[Int]): Array[Array[Double]] =
    Array.tabulate(landmarks.size, points.length)((l, n) => bruteDistance(points(landmarks(l)), points(n)))

  private def bruteWitnessValue(D: Array[Array[Double]], sigma: Seq[Int], m: Int => Double): Double =
    val N = D(0).length
    (0 until N).map { n =>
      val dmax = sigma.map(l => D(l)(n)).max
      math.max(0.0, dmax - m(n))
    }.min

  private def bruteMDim(D: Array[Array[Double]], k: Int, witness: Int): Double =
    D.map(row => row(witness)).sorted.apply(k)

  private def bruteMNu(D: Array[Array[Double]], nu: Int, witness: Int): Double =
    if nu == 0 then 0.0 else bruteMDim(D, nu - 1, witness)

  private def bruteGeneralFv(D: Array[Array[Double]], sigma: Seq[Int]): Double =
    val memo = scmutable.HashMap.empty[Seq[Int], Double]
    def go(s: Seq[Int]): Double =
      memo.getOrElseUpdate(
        s.sorted,
        if s.size <= 1 then 0.0
        else
          val k = s.size - 1
          val own = bruteWitnessValue(D, s, n => bruteMDim(D, k, n))
          val facets = s.map(v => go(s.filterNot(_ == v)))
          math.max(own, facets.max)
      )
    go(sigma)

  "WitnessMetricSpace(nu).distance matches an independent brute-force recomputation of the same formula, for " +
    "every nu in {0,1,2} and every landmark pair, on random point clouds" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val landmarks = LandmarkSelector.maxmin(ms, numLandmarks).landmarks
        val geometry = WitnessGeometry(ms, landmarks)
        val D = bruteD(points, landmarks)
        (0 to 2).forall { nu =>
          val wms = WitnessMetricSpace(geometry, nu)
          landmarks.indices.forall { i =>
            landmarks.indices.forall { j =>
              i == j || math.abs(wms.distance(i, j) - bruteWitnessValue(D, Seq(i, j), n => bruteMNu(D, nu, n))) < 1e-9
            }
          }
        }
      }
    }

  "WitnessCofaceSimplexStream's recursive filtration value matches an independent brute-force recomputation, " +
    "over every non-empty subset of landmarks, on random point clouds" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val landmarks = LandmarkSelector.maxmin(ms, numLandmarks).landmarks
        val geometry = WitnessGeometry(ms, landmarks)
        val D = bruteD(points, landmarks)
        val fv = WitnessCofaceSimplexStream.recursiveFiltrationValue(geometry)
        (1 to landmarks.size)
          .flatMap(landmarks.indices.combinations)
          .forall { sigma =>
            math.abs(fv(Simplex(sigma*)) - bruteGeneralFv(D, sigma)) < 1e-9
          }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Cross-validation between the two constructions, both predicted by the shared math (see WitnessStream.scala's
  // own doc): the general complex's 1-skeleton is IDENTICAL to the lazy (nu=2) complex's, and every higher
  // general simplex's value is >= its lazy flag-extension value (equality iff the simplex's own raw witness
  // value never exceeds its facets' max).
  // ---------------------------------------------------------------------------------------------------------

  "the general witness complex's edges are IDENTICAL (same pairs, same values) to the lazy (nu=2) complex's " +
    "own 1-skeleton, on random point clouds" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val landmarks = LandmarkSelector.maxmin(ms, numLandmarks).landmarks
        val geometry = WitnessGeometry(ms, landmarks)
        val lazyMs = WitnessMetricSpace(geometry, nu = 2)
        val generalFv = WitnessCofaceSimplexStream.recursiveFiltrationValue(geometry)
        landmarks.indices.forall { i =>
          landmarks.indices.forall { j =>
            i == j || math.abs(lazyMs.distance(i, j) - generalFv(Simplex(i, j))) < 1e-9
          }
        }
      }
    }

  "every general witness complex simplex's filtration value is >= its lazy (nu=2) flag-complex extension's, " +
    "on random point clouds" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val landmarks = LandmarkSelector.maxmin(ms, numLandmarks).landmarks
        val geometry = WitnessGeometry(ms, landmarks)
        val lazyMs = WitnessMetricSpace(geometry, nu = 2)
        val lazyFlagFv = FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](lazyMs)
        val generalFv = WitnessCofaceSimplexStream.recursiveFiltrationValue(geometry)
        (1 to landmarks.size)
          .flatMap(landmarks.indices.combinations)
          .forall { sigma =>
            val spx = Simplex(sigma*)
            generalFv(spx) >= lazyFlagFv(spx) - 1e-9
          }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Downward closure / enumeration completeness (WitnessCofaceSimplexStream): the recursive max-with-facets
  // filtration value is what makes "the complex at threshold R" automatically downward-closed for every R (see
  // WitnessStream.scala's own doc for the proof) -- checked empirically here against brute-force combinatorial
  // search, mirroring CechStreamSpec's own pattern, not just trusted from the proof.
  // ---------------------------------------------------------------------------------------------------------

  "WitnessCofaceSimplexStream's coface-loop enumeration finds exactly the same simplices as brute-force " +
    "combinatorial search, at every dimension, and every emitted cell's every facet is also emitted one " +
    "dimension down" >> {
      val points =
        Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.3, 0.9), Array(-0.4, 0.2), Array(0.6, -0.5), Array(1.2, 0.4))
      val ms = EuclideanMetricSpace(points)
      val landmarks = LandmarkSelector.maxmin(ms, numLandmarks = 5).landmarks
      val geometry = WitnessGeometry(ms, landmarks)
      val D = bruteD(points, landmarks)
      val threshold = 0.9
      val stream = WitnessCofaceSimplexStream(geometry, maxFiltrationValue = threshold)
      val byDim = (0 until landmarks.size).map { d =>
        d -> stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
      }.toMap
      val enumerationOk = byDim.forall { case (d, fromStream) =>
        val bruteForce = landmarks.indices
          .combinations(d + 1)
          .map(vs => Simplex(vs*))
          .filter(vs => bruteGeneralFv(D, vs.underlying.toSeq) <= threshold)
          .toSet
        fromStream == bruteForce
      }
      val downwardClosureOk = byDim.forall { case (d, cells) =>
        d == 0 || cells.forall(spx => spx.forall(v => byDim(d - 1).contains((spx.underlying - v).asSimplex)))
      }
      enumerationOk must beTrue
      downwardClosureOk must beTrue
    }

  // ---------------------------------------------------------------------------------------------------------
  // Structural invariant (bars account for cells), on both stream flavors -- the standard ordering-contract
  // sanity net every stream in this codebase gets (CLAUDE.md's "#1 historical bug source").
  // ---------------------------------------------------------------------------------------------------------

  "the bars-account-for-cells structural invariant holds for the lazy witness complex, on random clouds" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val landmarks = LandmarkSelector.maxmin(ms, numLandmarks).landmarks
        val stream = LazyWitnessSimplexStream(ms, landmarks, maxFiltrationValue = Some(Double.PositiveInfinity))
        val barcode = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
        val cellCount = (0 until landmarks.size)
          .flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty))
          .size
        HomologyFixtures.totalBarsAccountForAllCells(barcode, cellCount)
      }
    }

  "the bars-account-for-cells structural invariant holds for the general witness complex, on random clouds" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val numLandmarks = math.max(2, points.length - 1)
        val geometry = WitnessGeometry(ms, LandmarkSelector.maxmin(ms, numLandmarks).landmarks)
        val stream = WitnessCofaceSimplexStream(geometry)
        val barcode = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
        val cellCount = (0 until geometry.L)
          .flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty))
          .size
        HomologyFixtures.totalBarsAccountForAllCells(barcode, cellCount)
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Cross-engine agreement, on BOTH stream flavors. Compared as SORTED LISTS, not `.toSet` -- nu=2's per-witness
  // clamp (`max(0, dmax - m)`) makes duplicate zero-length bars (birth == death == 0.0) genuinely common on a
  // tie-heavy witness complex (many witness points share their own two nearest landmarks), and `.toSet` would
  // silently collapse a multiplicity mismatch between two engines exactly where one is most likely. Two
  // landmark configurations are exercised: a proper subset (the common case) and every point as a landmark
  // (maximal ties, landmarks == witnesses) -- the latter mirroring the historical chunks-pairing-bug's own
  // tie-heavy-clique shape (.claude/WORKLOG-chunks-pairing-bug.md), never before exercised against a witness
  // stream specifically (chunks/cohomology have no witness-specific code path, but "no a priori reason to
  // expect a bug" is exactly the standing this codebase's own history warns against trusting unchecked).
  // ---------------------------------------------------------------------------------------------------------

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def sortedTriples(bars: List[(Int, Double, Double)]): List[(Int, Double, Double)] =
    bars.sortBy(t => (t._1, t._2, t._3))

  private def landmarkConfigs(ms: FiniteMetricSpace[Int]): Seq[IndexedSeq[Int]] =
    Seq(
      LandmarkSelector.maxmin(ms, math.max(2, ms.size - 1)).landmarks, // a proper subset
      ms.elements.toIndexedSeq // every point is a landmark -- maximal ties
    )

  "PackedRipserCohomologyContext, run directly on WitnessMetricSpace, agrees exactly (as a sorted list, not " +
    "just a set) with the naive engine's own diagram on LazyWitnessSimplexStream, on random clouds, for both " +
    "a proper-subset and a maximal (every point is a landmark) landmark configuration" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        landmarkConfigs(ms).forall { landmarks =>
          val geometry = WitnessGeometry(ms, landmarks)
          val wms = WitnessMetricSpace(geometry, nu = 2)
          val cap = math.max(1, landmarks.size - 2)
          val naive = sortedTriples(
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(
                LazyWitnessSimplexStream(ms, landmarks, maxFiltrationValue = Some(Double.PositiveInfinity))
              )
              .diagramAt(Double.PositiveInfinity)
              .filter(_._1 <= cap)
          )
          val ripser = sortedTriples(
            PackedRipserCohomologyContext[Double](wms, cap, maxFiltrationValue = Some(Double.PositiveInfinity))
              .persistentCohomology()
              .map(bar => (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper)))
              .filter(_._1 <= cap)
          )
          naive == ripser
        }
      }
    }

  "CellularPersistenceInChunksContext agrees exactly (as a sorted list) with the naive engine on " +
    "LazyWitnessSimplexStream, on random clouds, for both landmark configurations" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        landmarkConfigs(ms).forall { landmarks =>
          val stream = LazyWitnessSimplexStream(ms, landmarks, maxFiltrationValue = Some(Double.PositiveInfinity))
          val naive = sortedTriples(
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
          )
          val chunks = sortedTriples(
            CellularPersistenceInChunksContext[Simplex[Int], Double](landmarks.size)
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
          )
          naive == chunks
        }
      }
    }

  private def cohomologyTriples[CellT](
    bars: List[PersistenceBar[Double, Chain[CellT, Double]]]
  ): List[(Int, Double, Double)] =
    sortedTriples(bars.map(bar => (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))))

  "CellularCohomologyContext agrees exactly (as a sorted list) with the naive engine on LazyWitnessSimplexStream, " +
    "on random clouds, for both landmark configurations" >> AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        landmarkConfigs(ms).forall { landmarks =>
          val stream = LazyWitnessSimplexStream(ms, landmarks, maxFiltrationValue = Some(Double.PositiveInfinity))
          val naive = sortedTriples(
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
          )
          val cohomology =
            cohomologyTriples(CellularCohomologyContext[Simplex[Int], Double, Double]().persistentCohomology(stream))
          naive == cohomology
        }
      }
    }

  "CellularCohomologyContext agrees exactly (as a sorted list) with the naive engine on " +
    "WitnessCofaceSimplexStream (the general variant), on random clouds, for both landmark configurations" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        landmarkConfigs(ms).forall { landmarks =>
          val stream = WitnessCofaceSimplexStream(WitnessGeometry(ms, landmarks))
          val naive = sortedTriples(
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(stream)
              .diagramAt(Double.PositiveInfinity)
          )
          val cohomology =
            cohomologyTriples(CellularCohomologyContext[Simplex[Int], Double, Double]().persistentCohomology(stream))
          naive == cohomology
        }
      }
    }

  "complex=witness via computeFromDistanceMatrix agrees exactly with computeFromPoints on the same cloud's " +
    "own Euclidean distances, for both the lazy and general variants" >> {
      val points =
        Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0), Array(0.5, 2.0), Array(2.0, 0.5))
      val distances = Array.tabulate(points.length, points.length)((i, j) => bruteDistance(points(i), points(j)))
      def triples(m: Array[Array[Double]]): List[(Int, Double, Double)] =
        sortedTriples(m.toList.map(row => (row(0).toInt, row(1), row(2))))
      val lazyOpts = Array("complex", "witness", "numLandmarks", "4")
      val generalOpts = Array("complex", "witness", "numLandmarks", "4", "witnessVariant", "general")
      (triples(TDA4j.computeFromDistanceMatrix(distances, lazyOpts).toArray()) must beEqualTo(
        triples(TDA4j.computeFromPoints(points, lazyOpts).toArray())
      )) and
        (triples(TDA4j.computeFromDistanceMatrix(distances, generalOpts).toArray()) must beEqualTo(
          triples(TDA4j.computeFromPoints(points, generalOpts).toArray())
        ))
    }

  // ---------------------------------------------------------------------------------------------------------
  // Qualitative smoke test only, explicitly NOT a pinned oracle: witness complexes of small, evenly-spaced
  // point clouds are known to be messy at nu=2's default ties (heavy fv=0 clustering, see
  // .claude/WORKLOG-witness-complex.md) -- this only checks that a circle's worth of points, witnessing
  // themselves via a well-spread maxmin landmark set, produces at least one substantially long H1 bar, not an
  // exact expected barcode.
  // ---------------------------------------------------------------------------------------------------------

  "qualitative smoke test: 20 points evenly spaced on a circle, self-witnessing, give at least one long H1 bar" >> {
    val n = 20
    val points = Array.tabulate(n) { i =>
      val theta = 2 * math.Pi * i / n
      Array(math.cos(theta), math.sin(theta))
    }
    val ms = EuclideanMetricSpace(points)
    val landmarks = LandmarkSelector.maxmin(ms, numLandmarks = 10).landmarks
    val stream = LazyWitnessSimplexStream(ms, landmarks, maxFiltrationValue = Some(Double.PositiveInfinity))
    val barcode = SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    val longestH1 = barcode.collect { case (1, b, d) if d.isFinite => d - b }.maxOption.getOrElse(0.0)
    longestH1 must be_>(0.3)
  }
