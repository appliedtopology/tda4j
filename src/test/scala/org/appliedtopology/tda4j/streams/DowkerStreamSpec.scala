package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

import org.appliedtopology.tda4j.barcode.*
import org.appliedtopology.tda4j.homology.HomologyFixtures

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
  * example, chosen specifically to exercise Dowker DUALITY -- the property that makes this construction worth having
  * at all), an independent brute-force reimplementation of the filtration-value formula cross-checked on random
  * relations, the duality cross-check itself on random relations, the bars-account-for-cells structural invariant, and
  * cross-engine agreement (`CellularCohomologyContext` against the naive engine -- `engine="cohomology"`'s own
  * validation story, since this construction is not a flag complex and so is never expected to agree with
  * `PackedRipserCohomologyContext`/`chunks`, the same status Cech and the general witness complex already have).
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
      SimplicialHomologyContext[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
    )

  /** Drops birth == death ("zero-persistence") bars -- an artifact of the total-order tie-break resolving several
    * cells sharing one real filtration value (e.g. a vertex born at `t` immediately merged by an edge also born at
    * `t`), not a genuine topological feature; CLAUDE.md/`WitnessStreamSpec` already document this as expected on a
    * tie-heavy complex. Load-bearing here specifically: the functorial Dowker duality theorem (Chowdhury & Mémoli,
    * 2018) guarantees the X-side and Y-side PERSISTENCE MODULES are naturally isomorphic, hence their interval
    * decompositions are literally identical multisets -- but only once these zero-length artifacts are stripped.
    * Confirmed necessary, not just theoretically tidy: a hand-worked 3x4 rectangular relation (`.claude/scratchpad`
    * during this construction's own development) has `numLeft != numWitnesses`, so the RAW barcodes differ in H0 bar
    * COUNT (exactly one birth event per vertex, unconditionally, so 3 vs 4 vertices cannot give equal raw multisets)
    * even though the underlying spaces are honestly homotopy equivalent at every threshold -- the extra births are
    * every one of them zero-persistence, and vanish under this filter.
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

  // ---------------------------------------------------------------------------------------------------------
  // Structural invariant (bars account for cells) -- the standard ordering-contract sanity net every stream in
  // this codebase gets (CLAUDE.md's "#1 historical bug source"), exercising `DowkerCofaceSimplexStream`'s own
  // `case 0` override (vertices are NOT all tied at filtration 0 here, unlike plain VR).
  // ---------------------------------------------------------------------------------------------------------

  "the bars-account-for-cells structural invariant holds for the Dowker complex, on random relations" >>
    AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val stream = DowkerCofaceSimplexStream(relation)
        val barcode = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
        val cellCount = (0 until stream.geometry.numLeft)
          .flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty))
          .size
        HomologyFixtures.totalBarsAccountForAllCells(barcode, cellCount)
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Cross-engine agreement: CellularCohomologyContext (engine="cohomology", the generic OrderedCell engine) vs
  // the naive engine -- the construction is not a flag complex (see DowkerStream.scala's own doc), so this is
  // the relevant cross-check, not PackedRipserCohomologyContext/chunks (same status Cech/general-witness have).
  // ---------------------------------------------------------------------------------------------------------

  private def cohomologyTriples[CellT](
    bars: List[PersistenceBar[Double, Chain[CellT, Double]]]
  ): List[(Int, Double, Double)] =
    sortedTriples(bars.map(bar => (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))))

  "CellularCohomologyContext agrees exactly (as a sorted list) with the naive engine on the Dowker complex, " +
    "on random relations" >> AsResult {
      prop { (relation: Array[Array[Double]]) =>
        val stream = DowkerCofaceSimplexStream(relation)
        val naive = naiveBarcode(stream)
        val cohomology =
          cohomologyTriples(CellularCohomologyContext[Simplex[Int], Double, Double]().persistentCohomology(stream))
        naive must beEqualTo(cohomology)
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
