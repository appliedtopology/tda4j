package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.appliedtopology.tda4j.barcode.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

/** `CellularCohomologyContext` correctness -- the generic (`CellT: OrderedCell`) cohomology engine, per
  * `.claude/DESIGN-generic-cohomology.md`. Validation plan, mirrored from that doc:
  *
  *   1. Cross-validate against `RipserCohomologyContext` on `Simplex[Int]` VR complexes -- bar VALUES (birth/death),
  *      not representative content: the two engines' tie-breaks were checked and found to genuinely differ (see the
  *      "term for term" test's own comment below for why that's expected, not a bug), so exact cross-engine
  *      representative agreement isn't a sound claim to make on VR input, where dimension 0 is always fully tied.
  *   2. `coboundaryOfChain(rep, cofacets).isZero()` for every ESSENTIAL bar (the only bars whose V-column is a genuine
  *      cocycle by construction -- see `Cohomology.scala`'s own doc), across every cell type this class newly supports.
  *   3. Barcode-value cross-validation against the already-trusted `CellularHomologyContext`, on Cube,
  *      `FiniteSimplicialSet`, Cech, and Alpha -- none of which had any cohomology cross-check before this class.
  *   4. The `totalBarsAccountForAllCells` structural invariant.
  */
class CohomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given Parameters = Parameters(minTestsOk = 100)

  private def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case NegativeInfinity() => Double.NegativeInfinity
    case PositiveInfinity() => Double.PositiveInfinity
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def toTuple[CellT](bar: PersistenceBar[Double, Chain[CellT, Double]]): (Int, Double, Double) =
    (bar.dim, endpointValue(bar.lower), endpointValue(bar.upper))

  private def cellsByDimOf[CellT: Cell, FiltrationT](stream: CellStream[CellT, FiltrationT]): Map[Int, Vector[CellT]] =
    stream.iterator.toVector.groupBy(_.dim)

  // ---------------------------------------------------------------------------------------------------------
  // 1. Cross-validation against RipserCohomologyContext, on Simplex[Int] Vietoris-Rips complexes.
  // ---------------------------------------------------------------------------------------------------------

  private val vrCtx = CellularCohomologyContext[Simplex[Int], Double, Double]()

  // No `maxDim` on this engine -- truncate at the STREAM level instead (LimitedCofaceSimplexStream, capping
  // real simplex dimension at maxDim + 1 so a maxDim-born class can be correctly resolved as finite or
  // essential), then drop the resulting dim == maxDim + 1 bars from the returned list, exactly the convention
  // `RipserCohomologySpec`'s own `naiveBars` helper and the MATLAB facade's `engine=naive` path already use.
  private def genericVrBars(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int
  ): List[PersistenceBar[Double, Chain[Simplex[Int], Double]]] =
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity)),
      maxDim + 1
    )
    vrCtx.persistentCohomology(stream).filter(_.dim <= maxDim)

  private def ripserBars(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int
  ): List[PersistenceBar[Double, Chain[Simplex[Int], Double]]] =
    RipserCohomologyContext[Double](metricSpace, maxDim, maxFiltrationValue = Some(Double.PositiveInfinity))
      .persistentCohomology()

  // Same calibration example RipserCohomologySpec itself uses: 3 colinear points at 0, 1, 3, all pairwise
  // distances distinct -- H_0's finite deaths are unambiguous, and maxDim=2 forces the triangle {0,2,1} to
  // exist, tied with edge {0,2} at 3.0 (a genuine zero-persistence H^1 bar).
  private val threePointLine = EuclideanMetricSpace(Array(Array(0.0), Array(1.0), Array(3.0)))

  "Generic cohomology agrees with RipserCohomologyContext on the calibration example" >> {
    val maxDim = 2
    genericVrBars(threePointLine, maxDim).map(toTuple) must containTheSameElementsAs(
      ripserBars(threePointLine, maxDim).map(toTuple)
    )
  }

  "Generic cohomology agrees with RipserCohomologyContext on random Vietoris-Rips point clouds" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2
      genericVrBars(metricSpace, maxDim).map(toTuple) must containTheSameElementsAs(
        ripserBars(metricSpace, maxDim).map(toTuple)
      )
    }

  // A first draft of this spec also asserted exact representative-content agreement against
  // `RipserCohomologyContext`, on the premise that both engines' tie-breaks resolve to the same total order
  // (colex) and hence, by the canonical-reduced-matrix argument this codebase already relies on elsewhere
  // (`unionFindDim01`/`vcolOf`), must compute term-for-term identical V-columns. Checked directly, not left as
  // an assumption -- and found FALSE, on the very first random point cloud tried, not some rare edge case:
  // dimension-0 Vietoris-Rips simplices (vertices) are ALWAYS tied at fv = 0.0, for every point cloud, and
  // `CellularCohomologyContext.cohomologyOrdering`'s tie-break (falling through to `stream.filtrationOrdering`)
  // resolves ties in the OPPOSITE direction from `RipserCohomologyContext.cohomologyOrdering`'s own hand-built
  // `compareFvThenIndex` ("larger combinatorial index sorts as older") -- confirmed two ways, not just reasoned
  // through: (1) by direct inspection (printing both engines' bars on the `threePointLine` fixture), the two
  // engines assign DIFFERENT specific vertices to the SAME (dim, birth, death) triple, e.g. generic pairs
  // vertex 1 with the bar dying at 1.0 where Ripser pairs vertex 0 with it; (2) by reconstructing and directly
  // printing each engine's OWN processing order over the three tied vertices (a throwaway debug spec calling
  // `cohomologyOrdering`/`compareFvThenIndex`'s exact construction directly, not inferred from source alone):
  // generic processes `[2,1,0]`, Ripser processes `[0,1,2]` -- genuinely, measurably opposite, matching (1)'s
  // observed pairings exactly. This is legitimate, not a bug -- when several cells tie at the same
  // birth time, which one is reported as dying at which death time is tie-order-dependent BY DESIGN (this
  // codebase's own `HomologyFixtures.tetrahedronBoundaryDegenerateExpected` fixture documents the identical
  // point for homology's elder rule), and the canonical-reduced-matrix argument only guarantees a UNIQUE
  // answer for a SINGLE fixed total order, never that two independently-chosen (each individually valid) total
  // orders must agree. The bar VALUES still agree exactly (the two tests just above already confirm this), and
  // each engine's own essential representatives are independently confirmed to be genuine cocycles (see below)
  // -- cross-engine representative-CONTENT agreement was never a sound claim to make, and is not attempted.

  // ---------------------------------------------------------------------------------------------------------
  // 2. coboundaryOfChain(rep, cofacets).isZero() for every ESSENTIAL bar. Only essential bars' V-columns are
  // genuine cocycles by construction (Algorithm 1's invariant is d(V_j) = R_j throughout, and R_j is zero
  // exactly when the bar is essential) -- a FINITE bar's V-column has coboundary equal to its own nonzero
  // reduced pivot chain, not zero, matching RipserCohomologySpec's own identically-scoped check
  // ("Essential representatives are genuine cocycles...") and its doc's explanation of why asserting isZero()
  // on a finite bar's representative would be a test bug, not a real property. Confirmed empirically here too,
  // not just inherited by assumption: a first draft of this test asserted it for every bar and failed on every
  // fixture with at least one finite bar, exactly as this reasoning predicts.
  //
  // NOTE: at the complex's own top dimension, this check is trivially true, not evidence of anything -- there
  // are no `(topDim + 1)`-cells at all, so `cofacets` is empty and `coboundaryOfChain(rep, Nil).isZero()` holds
  // vacuously (mathematically correct: with no cofacets, every top-dimension cochain IS a cocycle). Unlike
  // `RipserCohomologySpec`'s own equivalent test, which can genuinely claim "including at the top dimension"
  // (VR streams there are truncated at a caller-chosen maxDim with real cells one dimension higher still
  // available), every fixture below (Cube, the torus, Cech) is the complex's own natural top dimension, so the
  // substantive coverage here is at every dimension BELOW the top -- e.g. the torus's two essential H_1 bars.
  // ---------------------------------------------------------------------------------------------------------

  private def essentialRepsAreCocycles[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering](
    stream: CellStream[CellT, FiltrationT],
    ctx: CellularCohomologyContext[CellT, CoefficientT, FiltrationT]
  ): Boolean =
    val byDim = cellsByDimOf(stream)
    val bars = ctx.persistentCohomology(stream)
    bars.filter(_.upper == PositiveInfinity[FiltrationT]()).forall { bar =>
      bar.annotation.forall { rep =>
        val cofacets = byDim.getOrElse(bar.dim + 1, Vector.empty)
        ctx.coboundaryOfChain(rep, cofacets).isZero()
      }
    }

  "Every essential representative genuinely has zero coboundary: Vietoris-Rips (threePointLine, maxDim=2)" >> {
    val stream = LimitedCofaceSimplexStream(
      EnumeratingCofaceSimplexStream(threePointLine, maxFiltrationValue = Some(Double.PositiveInfinity)),
      3
    )
    essentialRepsAreCocycles(stream, vrCtx) must beTrue
  }

  "Every essential representative genuinely has zero coboundary: Cube (tie-heavy 2x2 grid)" >> {
    val stream = CubicalGridStream(IndexedSeq(2, 2), (_: IndexedSeq[Int]) => 5.0)
    essentialRepsAreCocycles(stream, CellularCohomologyContext[Cube, Double, Double]()) must beTrue
  }

  "Every essential representative genuinely has zero coboundary: FiniteSimplicialSet (torus, non-dimension-aligned filtration)" >> {
    import SimplicialSetFixtures.TorusGenerator
    import SimplicialSetFixtures.TorusGenerator.*
    given (TorusGenerator is OrderedCell) = SimplicialSetFixtures.torus.cellInstance
    val filtrationValue: PartialFunction[TorusGenerator, Double] =
      case Vertex => 0.0
      case A      => 1.0
      case B      => 2.0
      case C      => 3.0
      case U      => 4.0
      case L      => 5.0
    val stream = FilteredSimplicialSetStream(SimplicialSetFixtures.torus, filtrationValue)
    essentialRepsAreCocycles(stream, CellularCohomologyContext[TorusGenerator, Double, Double]()) must beTrue
  }

  "Every essential representative genuinely has zero coboundary: Cech (unit equilateral triangle)" >> {
    val s = 1.0
    val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(s, 0.0), Array(s / 2, s * math.sqrt(3) / 2)))
    val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
    essentialRepsAreCocycles(stream, vrCtx) must beTrue
  }

  // ---------------------------------------------------------------------------------------------------------
  // 3 & 4. Barcode-value cross-validation against CellularHomologyContext (already trusted, generic, and this
  // codebase's own established oracle for exactly this purpose), plus the structural invariant, for the four
  // cell types/constructions that have never had a cohomology cross-check before this class existed.
  // ---------------------------------------------------------------------------------------------------------

  "Cube: generic cohomology's barcode matches CellularHomologyContext's, and accounts for every cell" >> {
    val stream = CubicalGridStream(IndexedSeq(2, 2), (_: IndexedSeq[Int]) => 5.0)
    val totalCells = stream.iterator.size
    val cohomologyBars =
      CellularCohomologyContext[Cube, Double, Double]().persistentCohomology(stream).map(toTuple)
    val homologyBars =
      CellularHomologyContext[Cube, Double, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    (cohomologyBars must containTheSameElementsAs(homologyBars)) and
      (HomologyFixtures.totalBarsAccountForAllCells(cohomologyBars, totalCells) must beTrue)
  }

  "FiniteSimplicialSet (torus): generic cohomology's barcode matches CellularHomologyContext's, and accounts for every cell" >> {
    import SimplicialSetFixtures.TorusGenerator
    import SimplicialSetFixtures.TorusGenerator.*
    given (TorusGenerator is OrderedCell) = SimplicialSetFixtures.torus.cellInstance
    val filtrationValue: PartialFunction[TorusGenerator, Double] =
      case Vertex => 0.0
      case A      => 1.0
      case B      => 2.0
      case C      => 3.0
      case U      => 4.0
      case L      => 5.0
    val stream = FilteredSimplicialSetStream(SimplicialSetFixtures.torus, filtrationValue)
    val totalCells = stream.iterator.size
    val cohomologyBars =
      CellularCohomologyContext[TorusGenerator, Double, Double]().persistentCohomology(stream).map(toTuple)
    val homologyBars = CellularHomologyContext[TorusGenerator, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    (cohomologyBars must containTheSameElementsAs(homologyBars)) and
      (HomologyFixtures.totalBarsAccountForAllCells(cohomologyBars, totalCells) must beTrue)
  }

  "Cech: generic cohomology's barcode matches CellularHomologyContext's, and accounts for every cell" >> {
    val s = 1.0
    val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(s, 0.0), Array(s / 2, s * math.sqrt(3) / 2)))
    val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
    val totalCells = stream.iterator.size
    val cohomologyBars = vrCtx.persistentCohomology(stream).map(toTuple)
    val homologyBars =
      SimplicialHomologyContext[Int, Double, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    (cohomologyBars must containTheSameElementsAs(homologyBars)) and
      (HomologyFixtures.totalBarsAccountForAllCells(cohomologyBars, totalCells) must beTrue)
  }

  "Alpha: generic cohomology's barcode matches CellularHomologyContext's, and accounts for every cell" >> {
    val points: IndexedSeq[Array[Double]] =
      IndexedSeq(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.3, 0.9), Array(-0.4, 0.2), Array(0.6, -0.5))
    val stream = AlphaShapes(points, "helix")
    val totalCells = stream.iterator.size
    val cohomologyBars = vrCtx.persistentCohomology(stream).map(toTuple)
    val homologyBars =
      SimplicialHomologyContext[Int, Double, Double]().persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    (cohomologyBars must containTheSameElementsAs(homologyBars)) and
      (HomologyFixtures.totalBarsAccountForAllCells(cohomologyBars, totalCells) must beTrue)
  }

  // ---------------------------------------------------------------------------------------------------------
  // 5. A signed-field (F3) check, on the sign-discriminating RP^2 fixture -- every test above uses Double or
  // (via genericVrBars/ripserBars) an unsigned comparison that a sign error in `boundary` inherited through
  // this engine's coboundary-is-transpose-of-boundary construction could pass invisibly (bar VALUES over a
  // field are sign-flip-insensitive; F2 makes -1 == 1 outright). `SimplicialSetHomologySpec`'s own "RP2 is the
  // sign-discriminating fixture" test already established the expected F2-vs-F3 discrepancy for homology
  // (H_1=H_2=F2 over F2, both 0 over F3); this class had never been run over a signed field, or on a
  // torsion-sensitive fixture, at all before this test.
  // ---------------------------------------------------------------------------------------------------------

  private def endpointValueInt(e: BarcodeEndpoint[Int]): Int = e match
    case NegativeInfinity() => Int.MinValue
    case PositiveInfinity() => Int.MaxValue
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v

  private def toTupleInt[CellT, CoefficientT](bar: PersistenceBar[Int, Chain[CellT, CoefficientT]]): (Int, Int, Int) =
    (bar.dim, endpointValueInt(bar.lower), endpointValueInt(bar.upper))

  "RP^2 over F3: generic cohomology's barcode matches CellularHomologyContext's, accounts for every cell, and " +
    "matches the independently hand-derived F3 answer (no essential H_1/H_2, confirming signs are inherited " +
    "correctly through the coboundary-is-transpose-of-boundary construction, not just self-consistent)" >> {
      import SimplicialSetFixtures.ProjectiveGenerator
      val rp2 = SimplicialSetFixtures.realProjectiveSpace(2)
      given (ProjectiveGenerator is OrderedCell) = rp2.cellInstance
      val f3 = new FiniteField(3)
      import f3.given

      val stream = SimplicialSetStream(rp2)
      val cohomologyBars =
        CellularCohomologyContext[ProjectiveGenerator, f3.Fp, Int]().persistentCohomology(stream).map(toTupleInt)
      val homologyBars =
        CellularHomologyContext[ProjectiveGenerator, f3.Fp, Int]().persistentHomology(stream).diagramAt(0)

      // `HomologyFixtures.totalBarsAccountForAllCells` is `Double`-specific (`upper.isFinite`) -- not reused
      // here; matching the independently hand-derived expected list already implies cell-accounting
      // correctness for this one fixture.
      (cohomologyBars must containTheSameElementsAs(homologyBars)) and
        (cohomologyBars must containTheSameElementsAs(List((0, 0, Int.MaxValue), (1, 0, 0))))
    }

  "RP^2 over F3: every essential representative genuinely has zero coboundary" >> {
    import SimplicialSetFixtures.ProjectiveGenerator
    val rp2 = SimplicialSetFixtures.realProjectiveSpace(2)
    given (ProjectiveGenerator is OrderedCell) = rp2.cellInstance
    val f3 = new FiniteField(3)
    import f3.given

    val stream = SimplicialSetStream(rp2)
    essentialRepsAreCocycles(stream, CellularCohomologyContext[ProjectiveGenerator, f3.Fp, Int]()) must beTrue
  }
