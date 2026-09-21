package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck
import org.scalacheck.*

/** `CechCofaceSimplexStream` correctness -- this is the first-ever exercise of the Cech complex anywhere in this
  * codebase, built on `RipserCofaceSimplexStream`'s generic coface-generation loop (genericized in this same session,
  * see `CechStream.scala`'s own doc for the downward-closure argument for why that reuse is valid) and Miniball's
  * minimum-enclosing-ball solver (previously imported but never actually invoked anywhere in this codebase -- the one
  * prior use, `MiniballDelaunay`, was ripped out for reporting the WRONG quantity as its filtration value, not for a
  * Miniball correctness problem -- see `.claude/WORKLOG-cech-complex.md`).
  *
  * Validation order follows this codebase's established convention for a new complex type (see `CubicalStreamSpec`'s
  * own header): hand-derived fixtures chosen to discriminate specific wrong-implementation shapes, a monotonicity
  * property test, the bars-account-for-cells structural invariant, an independent H0 cross-check, and (specific to this
  * stream's own enumeration algorithm, not needed for e.g. `CubicalGridStream`) a brute-force enumeration-completeness
  * check.
  */
class CechStreamSpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  // Shared generator for both property tests below -- 2D point clouds, small enough to keep Miniball calls
  // cheap across many trials (this is a correctness suite, not a benchmark).
  given Arbitrary[Array[Array[Double]]] =
    Arbitrary(matrixGen[Double](Gen.double, Gen.const(2), Gen.chooseNum(2, 8)))

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived single-simplex fixtures, each chosen to discriminate a SPECIFIC wrong-implementation shape --
  // not just "does it run." All distances/radii below are exact classical facts about minimum enclosing balls
  // of triangles (Cech radius is NOT the circumradius in general -- only for acute triangles; for right/obtuse
  // triangles the minimum enclosing ball is centered at the midpoint of the longest side, radius = half that
  // side, strictly smaller than the circumcircle).
  // ---------------------------------------------------------------------------------------------------------

  "A single vertex has Cech radius 0.0 (matches MaximumDistanceFiltrationValue's own dim<=0 convention)" >> {
    val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0)))
    CechFiltration(ms)(Simplex(0)) must beEqualTo(0.0)
  }

  "An edge's Cech radius is exactly half its length" >> {
    val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(4.0, 0.0)))
    CechFiltration(ms)(Simplex(0, 1)) must beCloseTo(2.0, 1e-9)
  }

  "An equilateral triangle's Cech radius is its circumradius s/sqrt(3), NOT s/2 -- catches a " +
    "'just used the VR diameter' regression (the actual historical MiniballDelaunay bug)" >> {
      val s = 1.0
      val ms = EuclideanMetricSpace(
        Array(Array(0.0, 0.0), Array(s, 0.0), Array(s / 2, s * math.sqrt(3) / 2))
      )
      CechFiltration(ms)(Simplex(0, 1, 2)) must beCloseTo(s / math.sqrt(3), 1e-9)
    }

  "A right triangle's Cech radius is exactly half the hypotenuse (Thales), matching its circumradius here " +
    "only because right is the acute/obtuse boundary case" >> {
      val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(3.0, 0.0), Array(0.0, 4.0)))
      CechFiltration(ms)(Simplex(0, 1, 2)) must beCloseTo(2.5, 1e-9)
    }

  "An obtuse triangle's Cech radius is half its LONGEST SIDE, strictly less than its circumradius -- catches a " +
    "naive closed-form circumradius implementation that doesn't fall back to the enclosing-ball case" >> {
      // Very obtuse: (0,0)-(10,0)-(5,0.5). Longest side is the base (length 10); the circumcircle through all
      // three points is much larger (the near-degenerate configuration pushes the circumcenter far away), but
      // the minimum ENCLOSING ball is just the ball with the base as diameter, since the third point sits well
      // inside it.
      val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(10.0, 0.0), Array(5.0, 0.5)))
      val radius = CechFiltration(ms)(Simplex(0, 1, 2))
      val naiveCircumradius =
        // Standard circumradius formula abc/(4K), computed independently for comparison only.
        val a = math.sqrt(math.pow(10.0 - 5.0, 2) + math.pow(0.0 - 0.5, 2))
        val b = math.sqrt(math.pow(5.0 - 0.0, 2) + math.pow(0.5 - 0.0, 2))
        val c = 10.0
        val s = (a + b + c) / 2
        val area = math.sqrt(s * (s - a) * (s - b) * (s - c))
        (a * b * c) / (4 * area)
      (radius must beCloseTo(5.0, 1e-6)) and (radius must be_<(naiveCircumradius))
    }

  "Three collinear points: Cech radius is half the maximum span, and Miniball doesn't choke on the degeneracy" >> {
    val ms = EuclideanMetricSpace(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(3.0, 0.0)))
    CechFiltration(ms)(Simplex(0, 1, 2)) must beCloseTo(1.5, 1e-9)
  }

  "Repeated calls on the same simplex (even from independently-constructed CechFiltration instances) agree " +
    "exactly -- the correctness property the per-simplex cache exists to guarantee regardless of whether " +
    "Miniball itself is deterministic across calls" >> {
      val ms = EuclideanMetricSpace(
        Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.3, 0.9), Array(-0.4, 0.2))
      )
      val a = CechFiltration(ms)
      val b = CechFiltration(ms)
      (0 until 4).combinations(3).forall { vs =>
        val spx = Simplex(vs*)
        a(spx) == a(spx) && a(spx) == b(spx)
      } must beTrue
    }

  // ---------------------------------------------------------------------------------------------------------
  // Monotonicity: fv(face) <= fv(coface), forced by the same processingOrder requirement CubicalStreamSpec's
  // own monotonicity test documents. Provably true for Cech (adding a vertex only adds a ball-intersection
  // constraint, which can never shrink the minimum enclosing ball) -- checked empirically anyway, with a small
  // tolerance, not exact `<=`. A tolerance is a deliberate choice here, not a loosened check: a concrete
  // counterexample found while writing this test had face.fv=0.3887884477377332 vs coface.fv=0.3887884477377331,
  // a genuine ULP-level floating-point rounding difference (the inserted vertex sat almost exactly on the
  // existing minimal ball's boundary, so the mathematically correct answer barely changes, and Miniball's
  // internal 2-point vs. 3-point computation paths round that last bit differently) -- not a real monotonicity
  // violation, and not something any floating-point minimum-enclosing-ball implementation can be expected to
  // avoid at this precision. `Field.DoubleApproximated`'s own 1e-9 tolerance is the established convention
  // elsewhere in this codebase for exactly this class of comparison.
  // ---------------------------------------------------------------------------------------------------------

  "fv(face) <= fv(coface) (within floating-point tolerance) on every boundary relationship, on small random point clouds" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
        // Every dimension up to the combinatorial maximum (points.length - 1, an n-point cloud's own top
        // simplex dimension), not an arbitrary fixed cap -- capping too low silently stops checking the higher
        // dimensions a larger random cloud actually reaches.
        (0 until points.length).forall { d =>
          stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).forall { spx =>
            spx.underlying.forall { v =>
              val face = (spx.underlying - v).asSimplex
              stream.filtrationValue(face) <= stream.filtrationValue(spx) + 1e-9
            }
          }
        }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Enumeration completeness: RipserCofaceSimplexStream's "extend accepted survivors only" coface loop is valid
  // for Cech because Cech is downward-closed (CechStream.scala's own doc has the argument) -- checked
  // empirically here, not just trusted from the proof, by comparing against a full brute-force
  // combinations(d+1).filter(cechValid) count on a small cloud.
  // ---------------------------------------------------------------------------------------------------------

  "the coface-loop enumeration finds exactly the same simplices as brute-force combinatorial search, at every dimension" >> {
    val ms = EuclideanMetricSpace(
      Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.3, 0.9), Array(-0.4, 0.2), Array(0.6, -0.5), Array(1.2, 0.4))
    )
    val threshold = 0.6
    val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(threshold))
    val fv = stream.filtrationValue
    (0 until 6)
      .map { d =>
        val fromStream = stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
        val bruteForce = ms.elements.toSeq
          .combinations(d + 1)
          .map(vs => Simplex(vs*))
          .filter(spx => fv.isDefinedAt(spx) && fv(spx) <= threshold)
          .toSet
        fromStream must beEqualTo(bruteForce)
      }
      .reduce(_ and _)
  }

  // ---------------------------------------------------------------------------------------------------------
  // Structural invariant + independent H0 oracle, mirroring CubicalStreamSpec's own pattern. An edge exists in
  // Cech_r iff d(x,y) <= 2r (two r-balls touch iff their centers are within 2r) -- a fact that needs no
  // Euclidean geometry beyond the triangle inequality, so union-find over that graph is a fully independent
  // cross-check of H0 at any threshold r. A small epsilon tolerance guards the `<= 2r` comparison itself
  // because `r` is drawn from an EDGE's own reported Cech radius below (see that val's own comment for why),
  // and `2*r` is not always bit-identical to the ORIGINAL distance it came from -- Miniball's own 2-point
  // computation can differ from a direct `d/2.0` by an ULP or two (confirmed directly while debugging this
  // test: one concrete case had d(1,2)/2.0 = 0.19216296992354814 but the stream's own reported edge radius was
  // 0.19216296992354817), so without slack the very edge whose OWN birth defines the threshold could be
  // excluded by this independent oracle at that exact threshold.
  // ---------------------------------------------------------------------------------------------------------

  def independentH0Count(points: Array[Array[Double]], r: Double): Int =
    val ms = EuclideanMetricSpace(points)
    val elems = ms.elements.toSeq
    val uf = new UnionFind[Int](elems)
    import uf.UFSet
    for
      i <- elems
      j <- elems
      if i != j && ms.distance(i, j) <= 2 * r + 1e-9
    do uf.union(UFSet(i), UFSet(j))
    elems.map(i => uf.find(UFSet(i))).toSet.size

  "the bars-account-for-cells structural invariant holds, and H0 matches an independent union-find count, on random small clouds" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
        val state = SimplicialHomologyContext[Int, Double, Double]().persistentHomology(stream)
        // Thresholds to sweep, drawn from each EDGE's own reported Cech radius (via stream.filtrationValue),
        // not independently recomputed as `distance/2.0` -- the latter isn't guaranteed bit-identical to what
        // the stream itself uses to decide inclusion (see this section's own header comment), which produced a
        // real, reproducible off-by-one-ULP false mismatch while writing this test. 0.0 is prepended, not
        // appended, since `advanceTo` only moves forward -- appending it after the real thresholds would make
        // it a no-op on an already-fully-advanced state instead of genuinely checking the empty-edge-set case.
        val radii = 0.0 +: (for
          i <- ms.elements
          j <- ms.elements
          if i < j
        yield stream.filtrationValue(Simplex(i, j))).toSeq.distinct.sorted
        val h0Ok = radii.forall { r =>
          state.advanceTo(r)
          val reported = state.positives.count { case (sigma, _) => sigma.dim == 0 }
          reported == independentH0Count(points, r)
        }
        // The structural invariant is checked LAST, via a final advanceAll-equivalent (diagramAt at
        // +Infinity) -- doing this BEFORE the incremental h0Ok sweep above would fully advance `state` first,
        // making every subsequent `advanceTo(r)` for a small r a no-op on an already-finished stream (the
        // exact ordering mistake caught while writing this test, not a hypothetical one).
        val barcode = state.diagramAt(Double.PositiveInfinity)
        // Every dimension up to points.length - 1 (an n-point cloud's own top simplex dimension) -- an earlier
        // version of this test hardcoded (0 to 3), which silently undercounted cellCount (and so broke the
        // structural invariant, which needs the TRUE total) on any generated cloud with more than 4 points,
        // since maxFiltrationValue = +Infinity here means every combinatorial subset is a real cell.
        val cellCount = (0 until points.length)
          .flatMap(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty))
          .size
        val structuralOk = HomologyFixtures.totalBarsAccountForAllCells(barcode, cellCount)
        structuralOk && h0Ok
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Full hand-derived barcode, cross-validated against the naive engine directly -- the exact same discriminator
  // shape as RipserCohomologySpec's 3-cycle/filled-triangle fixture, but at Cech's OWN numeric values, which
  // differ from VR's: for a unit equilateral triangle, VR gives every edge AND the triangle itself diameter 1.0
  // (a zero-length H1 bar), while Cech gives edges radius 0.5 and the triangle radius 1/sqrt(3) ~= 0.577 (a
  // genuine, non-zero-length H1 bar) -- a bug that silently fell back to VR values would show up here as a
  // zero-length bar instead of the expected one.
  //
  // Numeric checks below use a small tolerance, not exact equality: `s * math.sqrt(3) / 2`'s own floating-point
  // rounding means this triangle's three edges are not QUITE bit-identical in length (0.9999999999999999 vs
  // 1.0, confirmed by printing the raw distances while debugging this fixture), so the two finite H0 deaths
  // land at 0.49999999999999994, not exactly 0.5 -- a floating-point coordinate-construction artifact, not a
  // reduction bug (the exact SAME structure -- 2 finite H0 bars, 1 essential H0, 1 non-zero-length H1 bar --
  // holds regardless).
  // ---------------------------------------------------------------------------------------------------------

  "The unit equilateral triangle: three components merge via a tied 3-cycle at r=0.5, filled in at r=1/sqrt(3)" >> {
    val s = 1.0
    val ms = EuclideanMetricSpace(
      Array(Array(0.0, 0.0), Array(s, 0.0), Array(s / 2, s * math.sqrt(3) / 2))
    )
    val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
    val barcode = SimplicialHomologyContext[Int, Double, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    val triangleRadius = s / math.sqrt(3)
    val edgeRadius = 0.5
    val tol = 1e-9

    (HomologyFixtures.totalBarsAccountForAllCells(barcode, 7) must beTrue) and
      (barcode.count(_._1 == 0) must beEqualTo(3)) and
      (barcode.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (barcode.count {
        case (0, b, d) => math.abs(b - 0.0) < tol && math.abs(d - edgeRadius) < tol
        case _         => false
      } must beEqualTo(2)) and
      (barcode.count(_._1 == 1) must beEqualTo(1)) and
      (barcode.exists {
        case (1, b, d) => math.abs(b - edgeRadius) < tol && math.abs(d - triangleRadius) < tol
        case _         => false
      } must beTrue) and
      (barcode.count(_._1 >= 2) must beEqualTo(0))
  }

  // ---------------------------------------------------------------------------------------------------------
  // Chunks vs. naive cross-validation on Cech streams -- `CellularPersistenceInChunksContext` has never been
  // exercised against `CechCofaceSimplexStream` before (CLAUDE.md's Cech section only ever validated the naive
  // engine here). `CellularPersistenceInChunksContext[CellT: OrderedCell, ...]` has no Cech-specific code path
  // at all -- same as its already-validated use on `Cube`/`FiniteSimplicialSet` generators -- so there is no a
  // priori reason to expect it to fail here, but "no a priori reason to expect a bug" is exactly the standing
  // this codebase's own history warns against trusting without a real check (see e.g. the chunks pairing bug
  // found on tie-heavy VR cliques). `maxDim = points.length` is deliberately generous (the true combinatorial
  // top dimension for an n-point cloud), so nothing is truncated away that the naive engine would otherwise see.
  // ---------------------------------------------------------------------------------------------------------

  "CellularPersistenceInChunksContext agrees with the naive engine's own diagram, exactly, on random Cech streams" >>
    AsResult {
      prop { (points: Array[Array[Double]]) =>
        val ms = EuclideanMetricSpace(points)
        val stream = CechCofaceSimplexStream(ms, maxFiltrationValue = Some(Double.PositiveInfinity))
        val naive = SimplicialHomologyContext[Int, Double, Double]()
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
          .toSet
        val chunks = CellularPersistenceInChunksContext[Simplex[Int], Double](points.length)
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
          .toSet
        naive must beEqualTo(chunks)
      }
    }
