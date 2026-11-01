package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.AsResult
import org.specs2.ScalaCheck
import org.scalacheck.*

/** `DowkerGeometry`/`DowkerFiltration`/`DowkerCofaceSimplexStream` correctness (`DowkerStream.scala`) -- the first
  * exercise of a Dowker complex anywhere in this codebase, built on `RipserCofaceSimplexStream`'s generic coface loop
  * the same way `CechCofaceSimplexStream`/`WitnessCofaceSimplexStream` are.
  *
  * Validation order follows this codebase's established convention for a new complex type (see `WitnessStreamSpec`'s
  * own header): a hand-derived fixture with known, nontrivial topology (the classical "5 arcs cover a circle" nerve
  * example, chosen specifically to exercise Dowker DUALITY -- the property that makes this construction worth having at
  * all), an independent brute-force reimplementation of the filtration-value formula cross-checked on random relations,
  * the duality cross-check itself on random relations, the bars-account-for-cells structural invariant, and
  * cross-engine agreement (`CellularCohomologyEngine` against the naive engine -- `engine="cohomology"`'s own
  * validation story, since this construction is not a flag complex and so is never expected to agree with
  * `PackedRipserCohomologyEngine`/`chunks`, the same status Cech and the general witness complex already have).
  */
class DowkerStreamSpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  given Arbitrary[Array[Array[Double]]] =
    Arbitrary(matrixGen[Double](Gen.chooseNum(0.0, 4.0), Gen.chooseNum(3, 5), Gen.chooseNum(3, 5)))

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def sortedTriples(bars: List[(Int, Double, Double)]): List[(Int, Double, Double)] =
    bars.sortBy(t => (t._1, t._2, t._3))

  private def naiveBarcode(stream: DowkerCofaceSimplexStream): List[(Int, Double, Double)] =
    sortedTriples(
      SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
    )

  /** Drops birth == death ("zero-persistence") bars -- an artifact of the total-order tie-break resolving several cells
    * sharing one real filtration value (e.g. a vertex born at `t` immediately merged by an edge also born at `t`), not
    * a genuine topological feature; CLAUDE.md/`WitnessStreamSpec` already document this as expected on a tie-heavy
    * complex. Load-bearing here specifically: the functorial Dowker duality theorem (Chowdhury & Mémoli, 2018)
    * guarantees the X-side and Y-side PERSISTENCE MODULES are naturally isomorphic, hence their interval decompositions
    * are literally identical multisets -- but only once these zero-length artifacts are stripped. Confirmed necessary,
    * not just theoretically tidy: a hand-worked 3x4 rectangular relation (`.claude/scratchpad` during this
    * construction's own development) has `numLeft != numWitnesses`, so the RAW barcodes differ in H0 bar COUNT (exactly
    * one birth event per vertex, unconditionally, so 3 vs 4 vertices cannot give equal raw multisets) even though the
    * underlying spaces are honestly homotopy equivalent at every threshold -- the extra births are every one of them
    * zero-persistence, and vanish under this filter.
    */
  private def dropZeroPersistence(bars: List[(Int, Double, Double)]): List[(Int, Double, Double)] =
    bars.filterNot(t => t._2 == t._3)

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived fixture: the classical nerve of 5 arcs covering a circle. X = 5 points, Y = 5 arcs; arc `y`
  // covers points `{y, y+1 mod 5}`. Every point is covered by exactly 2 arcs (`x` and `x-1 mod 5`), so two
  // DISTINCT points share a covering arc iff they are cyclically adjacent -- the X-side Dowker complex is
  // exactly the 5-cycle graph (no filled triangles: an arc covers only 2 points, so no witness can ever cover
  // 3 points at once), i.e. the boundary of a pentagon, homotopy equivalent to S^1. By the identical argument
  // (this relation happens to be shift-symmetric under swapping roles), the Y-side complex is ALSO a 5-cycle.
  // Chosen specifically because it has real, nontrivial topology (H1 != 0) -- unlike a chain/tree example,
  // which would validate duality only vacuously (both sides trivially contractible).
  // ---------------------------------------------------------------------------------------------------------

  private val pentagonRelation: Seq[Seq[Boolean]] =
    (0 until 5).map(x => (0 until 5).map(y => x == y || x == (y + 1) % 5))
  private val pentagonGeometry = DowkerGeometry.fromBoolean(pentagonRelation)

  "Hand-derived fixture: the 5-arcs-cover-a-circle Dowker complex is the pentagon boundary graph (5 vertices, " +
    "5 edges, no triangles) on BOTH sides" >> {
      val xStream = DowkerCofaceSimplexStream(pentagonGeometry)
      val yStream = xStream.dual
      def edgesAndVertices(s: DowkerCofaceSimplexStream): (Set[Simplex[Int]], Set[Simplex[Int]], Boolean) =
        val vertices = s.iterateDimension.applyOrElse(0, (_: Int) => Iterator.empty).toSet
        val edges = s.iterateDimension.applyOrElse(1, (_: Int) => Iterator.empty).toSet
        val noTriangles = s.iterateDimension.applyOrElse(2, (_: Int) => Iterator.empty).isEmpty
        (vertices, edges, noTriangles)
      val (xv, xe, xNoTri) = edgesAndVertices(xStream)
      val (yv, ye, yNoTri) = edgesAndVertices(yStream)
      (xv.size must beEqualTo(5)) and (xe.size must beEqualTo(5)) and (xNoTri must beTrue) and
        (yv.size must beEqualTo(5)) and (ye.size must beEqualTo(5)) and (yNoTri must beTrue)
    }

  "Hand-derived fixture: the pentagon Dowker complex has H0 = 1 essential class and H1 = 1 essential class, " +
    "identically on both dual sides, once the zero-persistence H0 pairs (every vertex/edge is born at the same " +
    "tied filtration value 0.0 here) are dropped" >> {
      val xBars = dropZeroPersistence(naiveBarcode(DowkerCofaceSimplexStream(pentagonGeometry)))
      val yBars = dropZeroPersistence(naiveBarcode(DowkerCofaceSimplexStream(pentagonGeometry).dual))
      val expected = List((0, 0.0, Double.PositiveInfinity), (1, 0.0, Double.PositiveInfinity))
      (xBars must beEqualTo(expected)) and (yBars must beEqualTo(expected))
    }

  // ---------------------------------------------------------------------------------------------------------
  // Independent brute-force oracle for `DowkerGeometry.filtrationValue`, and the monotonicity property the
  // class doc proves directly from the formula (CLAUDE.md's ordering-contract rule 3) -- checked empirically
  // here, not just trusted from the proof, matching every other stream's own validation story in this codebase.
  // ---------------------------------------------------------------------------------------------------------

  private def bruteFiltrationValue(R: Array[Array[Double]], sigma: Seq[Int]): Double =
    val numWitnesses = R(0).length
    (0 until numWitnesses).map(w => sigma.map(x => R(x)(w)).max).min

  "DowkerGeometry.filtrationValue matches an independent brute-force recomputation of the same formula, over " +
    "every non-empty subset of the left side, on random relations" >> AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val geometry = DowkerGeometry(relation)
        (1 to geometry.numLeft)
          .flatMap((0 until geometry.numLeft).combinations)
          .forall(sigma => math.abs(geometry.filtrationValue(sigma) - bruteFiltrationValue(relation, sigma)) < 1e-9)
      }
    }

  "DowkerGeometry.filtrationValue is monotone: every simplex's value is >= every one of its facets', on " +
    "random relations" >> AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val geometry = DowkerGeometry(relation)
        (2 to geometry.numLeft)
          .flatMap((0 until geometry.numLeft).combinations)
          .forall { sigma =>
            val own = geometry.filtrationValue(sigma)
            sigma.forall(v => geometry.filtrationValue(sigma.filterNot(_ == v)) <= own + 1e-9)
          }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Duality cross-check on random (not just the hand-derived pentagon) relations -- Dowker's theorem predicts
  // the two sides' barcodes agree EXACTLY, at every dimension, not just up to some coarser invariant like Euler
  // characteristic.
  // ---------------------------------------------------------------------------------------------------------

  "the X-side and Y-side (dual) Dowker complexes have IDENTICAL barcodes (as a sorted list, not just a set) " +
    "once zero-persistence bars are dropped, on random relations -- the functorial Dowker duality theorem" >>
    AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val xBars = dropZeroPersistence(naiveBarcode(DowkerCofaceSimplexStream(relation)))
        val yBars = dropZeroPersistence(naiveBarcode(DowkerCofaceSimplexStream(relation).dual))
        xBars must beEqualTo(yBars)
      }
    }

  // The stream ordering contract, including `DowkerCofaceSimplexStream`'s own `case 0` (vertices are not all
  // tied at 0 here, unlike plain VR).
  "the Dowker complex satisfies the stream ordering contract, on random relations" >>
    AsResult {
      prop((relation: Array[Array[Double]]) =>
        HomologyFixtures.respectsOrderingContract(DowkerCofaceSimplexStream(relation))
      )
    }

  // ---------------------------------------------------------------------------------------------------------
  // Cross-engine agreement: CellularCohomologyEngine (engine="cohomology", the generic OrderedCell engine) vs
  // the naive engine -- the construction is not a flag complex (see DowkerStream.scala's own doc), so this is
  // the relevant cross-check, not PackedRipserCohomologyEngine/chunks (same status Cech/general-witness have).
  // ---------------------------------------------------------------------------------------------------------

  private def cohomologyTriples[CellT](
    bars: List[PersistenceBar[Double, Chain[CellT, Double]]]
  ): List[(Int, Double, Double)] =
    sortedTriples(bars.map(bar => (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))))

  "CellularCohomologyEngine agrees exactly (as a sorted list) with the naive engine on the Dowker complex, " +
    "on random relations" >> AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val stream = DowkerCofaceSimplexStream(relation)
        val naive = naiveBarcode(stream)
        val cohomology =
          cohomologyTriples(CellularCohomologyEngine[Simplex[Int], Double, Double]().persistentCohomology(stream))
        naive must beEqualTo(cohomology)
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // The array-level value the flat layers use: a new row on top of a parent, with the parent's maxima kept.
  // ---------------------------------------------------------------------------------------------------------

  "the flat value of a candidate (a new smallest vertex on a parent) is the formula's value bit for bit, in the order " +
    "the layers ask for it and in any other" >> {
      def bits(x: Double) = java.lang.Double.doubleToLongBits(x)
      def subsets(n: Int): Seq[Array[Int]] =
        (1 until (1 << n)).map(mask => (0 until n).filter(i => (mask & (1 << i)) != 0).toArray)
      val results = for
        seed <- 0L until 4L
        infinite <- Seq(false, true)
      yield
        val rng = new scala.util.Random(seed)
        val relation = Array.fill(7, 5)(
          if infinite then if rng.nextInt(3) == 0 then 0.0 else Double.PositiveInfinity else rng.nextInt(4).toDouble
        )
        val geometry = DowkerGeometry(relation)
        class Exposed extends DowkerCofaceSimplexStream(geometry):
          def exposed: RipserCofaceSimplexStream.FlatFiltration = flatFiltration.get
        val flat = new Exposed
        // Every simplex of two or more vertices, as the candidate it is: its smallest vertex on top of the rest.
        val candidates = subsets(7).filter(_.length >= 2)
        def agrees(order: Seq[Array[Int]]): Boolean =
          val values = flat.exposed.perRun
          order.forall(c =>
            bits(values.value(c, 0, c.length, Double.NaN)) == bits(geometry.filtrationValue(c, 0, c.length))
          )
        // The layers' order: by width, and each parent's candidates together.
        val grouped = candidates.sortBy(c => (c.length, c.tail.toSeq.map(_.toLong).foldLeft(0L)(_ * 8 + _)))
        val shuffled = new scala.util.Random(seed + 100).shuffle(candidates)
        agrees(grouped) && agrees(shuffled) && agrees(grouped.reverse)
      results must contain(true).forall
    }

  // ---------------------------------------------------------------------------------------------------------
  // Landmark-to-witness distances (`WitnessGeometry.D`): the Dowker complex is the Čech complex of the landmarks
  // tested at the witnesses (Chazal, de Silva and Oudot's Čech(L, W)). It is inside every witness complex and equal
  // to none: the lazy one with nu = 0 is its flag complex. Fixture: the vertices of an equilateral triangle of side 1
  // as landmarks, its edge midpoints as the only other witnesses. Every edge has a witness at 1/2 (its midpoint), the
  // triangle only at sqrt(3)/2 (a midpoint is that far from the opposite vertex), so the Dowker complex has the loop
  // [1/2, sqrt(3)/2); the lazy complex fills the triangle with its edges, at 1/2, and has no loop.
  // `WORKLOG-dual-witness-complex.md`.
  // ---------------------------------------------------------------------------------------------------------

  private def barcodeOf(stream: CellStream[Simplex[Int], Double]): List[(Int, Double, Double)] =
    sortedTriples(
      SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    )

  private val h = math.sqrt(3.0) / 2
  private val triangleWithMidpoints = EuclideanMetricSpace(
    Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, h), Array(0.5, 0.0), Array(0.75, h / 2), Array(0.25, h / 2))
  )

  "on landmark-to-witness distances the Dowker complex has a loop the lazy nu = 0 witness complex fills at once: " +
    "the triangle with only its edge midpoints as witnesses" >> {
      val geometry = WitnessGeometry(triangleWithMidpoints, IndexedSeq(0, 1, 2))
      val dowkerLoops =
        dropZeroPersistence(barcodeOf(DowkerCofaceSimplexStream(DowkerGeometry(geometry.D)))).filter(_._1 == 1)
      val lazyLoops =
        dropZeroPersistence(barcodeOf(LazyWitnessSimplexStream(triangleWithMidpoints, IndexedSeq(0, 1, 2), 0)))
          .filter(_._1 == 1)
      (dowkerLoops.map(_._2) must beEqualTo(List(0.5))) and
        (math.abs(dowkerLoops.head._3 - h) must beLessThan(1e-12)) and
        (lazyLoops must beEmpty)
    }

  "on landmark-to-witness distances the Dowker value of every simplex is at least its value in every witness " +
    "complex, and the lazy nu = 0 value is the largest Dowker value of its edges, on random point clouds" >>
    AsResult {
      val clouds = matrixGen[Double](Gen.chooseNum(-1.0, 1.0), Gen.const(2), Gen.chooseNum(5, 9))
      Prop.forAll(clouds, Gen.chooseNum(3, 4)) { (points: Array[Array[Double]], numLandmarks: Int) =>
        val metricSpace = EuclideanMetricSpace(points)
        val geometry = WitnessGeometry(metricSpace, (0 until numLandmarks).toIndexedSeq)
        val dowker = DowkerGeometry(geometry.D)
        val general = WitnessCofaceSimplexStream.recursiveFiltrationValue(geometry)
        val lazySpaces = (0 to 2).map(nu => WitnessMetricSpace(geometry, nu))
        def edgeMax(sigma: IndexedSeq[Int], value: (Int, Int) => Double): Double =
          sigma.combinations(2).map(e => value(e(0), e(1))).max
        val simplices: Seq[IndexedSeq[Int]] = (2 to 3).flatMap((0 until numLandmarks).combinations)
        simplices.forall { sigma =>
          val own = dowker.filtrationValue(sigma)
          val lazyBelow = lazySpaces.forall(s => edgeMax(sigma, s.distance) <= own + 1e-12)
          val generalBelow = general(Simplex(sigma*)) <= own + 1e-12
          val flag = math.abs(
            edgeMax(sigma, lazySpaces(0).distance) - edgeMax(sigma, (i, j) => dowker.filtrationValue(IndexedSeq(i, j)))
          ) < 1e-12
          lazyBelow && generalBelow && flag
        }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Constructor validation.
  // ---------------------------------------------------------------------------------------------------------

  "DowkerGeometry rejects a ragged, empty, or negative-valued relation" >> {
    (DowkerGeometry(Array(Array(0.0, 1.0), Array(0.0))) must throwAn[IllegalArgumentException]) and
      (DowkerGeometry(Array.empty[Array[Double]]) must throwAn[IllegalArgumentException]) and
      (DowkerGeometry(Array(Array(-1.0, 0.0))) must throwAn[IllegalArgumentException])
  }
