package org.appliedtopology.tda4j
package matlab

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

/** Tests `PersistenceResult`'s boundary-matrix export (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item
  * 1). The real oracle here is [[reduceZ2]]: an independent, textbook Z/2 persistence-algorithm reduction of the
  * EXPORTED matrix, run entirely outside any engine in this codebase. If that reduction's own (dim, birth, death)
  * triples don't match `result.toArray()`, the export is wrong (or inconsistent with whichever engine actually
  * computed the bars) -- this is a strictly stronger check than eyeballing a few hand-picked entries, since it
  * exercises every row/column/value this class reports at once.
  */
class BoundaryMatrixSpec extends Specification with ScalaCheck:

  /** Standard Z/2 persistence-algorithm reduction (Edelsbrunner-Letscher-Zomorodian): columns are reduced
    * left-to-right against already-recorded pivots via symmetric difference; a column that reduces to empty
    * opens a class, one that reduces to a nonzero pivot row closes the class that row opened. Relies on exactly
    * one structural fact about the exported matrix -- a face's row index is always strictly less than its
    * coface's column index (guaranteed by `TDA4j.buildBoundaryMatrix` building columns in filtration order,
    * dimension-bucketed, so a face is always in an earlier bucket than any of its cofaces) -- not on any global
    * monotonicity of `columnFiltrationValue` itself (which is NOT guaranteed globally, only within a dimension:
    * e.g. a DTM-weighted vertex can be born later than some higher-dimensional cell's own filtration value).
    */
  private def reduceZ2(result: PersistenceResult): Set[(Int, Double, Double)] =
    val n = result.numCells()
    val columns = Array.fill(n)(scala.collection.immutable.TreeSet.empty[Int])
    val rows = result.boundaryRows()
    val cols = result.boundaryCols()
    for k <- rows.indices do columns(cols(k)) = columns(cols(k)) + rows(k)
    def symDiff(a: scala.collection.immutable.TreeSet[Int], b: scala.collection.immutable.TreeSet[Int]) =
      (a -- b) ++ (b -- a)
    val pivotOf = scala.collection.mutable.Map.empty[Int, Int]
    val paired = Array.fill(n)(false)
    val bars = scala.collection.mutable.Set.empty[(Int, Double, Double)]
    for j <- 0 until n do
      while columns(j).nonEmpty && pivotOf.contains(columns(j).max) do
        columns(j) = symDiff(columns(j), columns(pivotOf(columns(j).max)))
      if columns(j).nonEmpty then
        val low = columns(j).max
        pivotOf(low) = j
        paired(low) = true
        paired(j) = true
        bars += ((result.columnDimension(low), result.columnFiltrationValue(low), result.columnFiltrationValue(j)))
    for j <- 0 until n do
      if !paired(j) then bars += ((result.columnDimension(j), result.columnFiltrationValue(j), Double.PositiveInfinity))
    bars.toSet

  private def barsAsTriples(result: PersistenceResult): Set[(Int, Double, Double)] =
    (0 until result.size()).map(i => (result.dimension(i), result.birth(i), result.death(i))).toSet

  "boundary matrix reduction reproduces the exact barcode" >> {
    "on a hand-sized triangle (3 points, maxDimension=2 -- vertices, edges, AND the filled triangle)" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0))
      // maxFiltrationValue=Infinity: this triangle's own enclosing radius (~0.707, at the right-angle vertex)
      // is LESS than its longest edge (the hypotenuse, sqrt(2)) -- the default truncation would otherwise drop
      // both the hypotenuse and the triangle itself, which is correct default behavior but not what this
      // particular test wants to exercise (the full, untruncated 7-cell complex).
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "2", "maxFiltrationValue", "Infinity"))
      result.numCells() must beEqualTo(7) // 3 vertices + 3 edges + 1 triangle
      reduceZ2(result) must beEqualTo(barsAsTriples(result))
    }

    "on random small point clouds, across every engine (all must agree with each other AND the reduction)" >> {
      val pointGen = Gen.listOfN(6, Gen.listOfN(2, Gen.choose(0.0, 3.0))).map(_.map(_.toArray).toArray)
      forAll(pointGen) { points =>
        val engines = Seq("ripser", "naive", "chunks", "cohomology")
        val results = engines.map(e => TDA4j.computeFromPoints(points, Array("engine", e, "maxDimension", "1")))
        val barSets = results.map(barsAsTriples)
        // The exported matrix intentionally includes the SAME "one cell-dimension higher" scaffolding
        // TDA4j.computeGeneric itself builds internally (H_k needs (k+1)-chains) and that fromBars drops from
        // the reported bars (CLAUDE.md's "drop dim == k + 1 bars" rule) -- reducing the WHOLE exported matrix
        // therefore surfaces that same scaffolding dimension as its own (not generally essential-vs-finite
        // meaningful) bars, so this oracle check has to apply the identical filter before comparing.
        val reductionSets = results.map(r => reduceZ2(r).filter(_._1 <= 1))
        (barSets.forall(_ == barSets.head) must beTrue) and // every engine agrees on the barcode itself
          (reductionSets.forall(_ == reductionSets.head) must beTrue) and // ...and on the exported boundary matrix
          (reductionSets.head must beEqualTo(barSets.head)) // ...which reduces to that same barcode independently
      }
    }

    "on a cubical complex" >> {
      val values = Array(Array(0.0, 1.0, 0.0), Array(1.0, 2.0, 1.0), Array(0.0, 1.0, 0.0))
      val result = TDA4j.computeFromImage(values, Array("maxDimension", "1"))
      reduceZ2(result) must beEqualTo(barsAsTriples(result))
    }

    "on an alpha complex (no maxDimension truncation, exercises the Int.MaxValue keepDimensionsUpTo path)" >> {
      // A generic (non-symmetric, non-cospherical) point set deliberately -- unrelated pre-existing bug found
      // while writing this spec: a highly symmetric configuration (a unit square plus its own center) makes
      // TDA4j.computeFromPoints(complex=alpha) throw IllegalStateException("reduction pivot ... was not a
      // recorded open class") from CellularHomologyContext.advanceOne, reproducing with NO boundary-matrix code
      // involved at all -- confirmed by calling that exact one-line combination directly. Out of scope for this
      // arc (a real but separate ordering/tie-handling bug, the same failure signature CLAUDE.md's "Streams:
      // the ordering contract" section already documents five prior instances of); flagged in this session's
      // own worklog rather than chased down here.
      val points = Array(Array(0.0, 0.0), Array(1.1, 0.2), Array(0.3, 1.4), Array(2.1, 0.9), Array(1.5, 1.8))
      val result = TDA4j.computeFromPoints(points, Array("complex", "alpha"))
      reduceZ2(result) must beEqualTo(barsAsTriples(result))
    }
  }

  "boundary matrix structure" >> {
    "every column's own dimension is one more than every row it references, i.e. boundary strictly lowers dimension by exactly 1" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9), Array(2.0, 0.3))
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "2"))
      val rows = result.boundaryRows()
      val cols = result.boundaryCols()
      forall(rows.indices) { k =>
        result.columnDimension(rows(k)) must beEqualTo(result.columnDimension(cols(k)) - 1)
      }
    }

    "every entry is a face: its row's own column index is strictly less than its column index" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9), Array(2.0, 0.3))
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "2"))
      val rows = result.boundaryRows()
      val cols = result.boundaryCols()
      forall(rows.indices)(k => rows(k) must beLessThan(cols(k)))
    }

    "with the default field (Z, prime 2), every reported value has absolute value 1.0" >> {
      // NOT literally always 1.0: FiniteField.Fp.toInt is the raw (possibly-negative) Scala `%` residue, not
      // canonicalized to [0, p) the way the separate toUInt is (see FiniteField.scala's own comment) -- -1 and 1
      // are the same element of F2, and this codebase's own boundary computation can legitimately report either
      // representative. Confirmed directly (not assumed): every entry on this exact fixture is consistently
      // -1.0, never a mix, and cycleCoefficients elsewhere in this facade has the identical property already.
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "2"))
      result.boundaryValues().forall(v => math.abs(v) == 1.0) must beTrue
    }

    "columnVertices/columnDimension are self-consistent: a dimension-k column's vertex array has k+1 entries" >> {
      val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9))
      val result = TDA4j.computeFromPoints(points, Array("maxDimension", "2"))
      forall(0 until result.numCells()) { j =>
        result.columnVertices(j).length must beEqualTo(result.columnDimension(j) + 1)
      }
    }

    "is computed lazily, and only once: the provider thunk runs iff/when a boundary-matrix accessor is first " +
      "called, then never again" >> {
      var calls = 0
      def provider(): BoundaryMatrixData =
        calls += 1
        BoundaryMatrixData(Array(0), Array(1), Array(1.0), Array(0, 1), Array(Array(0), Array(1)), Array(0.0, 1.0))
      val result = new PersistenceResult(
        Array(0),
        Array(0.0),
        Array(1.0),
        _ => (Array(Array(0)), Array(1.0)),
        provider
      )
      calls must beEqualTo(0) // constructing the result alone must not force it
      result.toArray() // ordinary barcode access must not force it either
      calls must beEqualTo(0)
      result.numCells() must beEqualTo(2) // first boundary-matrix accessor: forces it once
      calls must beEqualTo(1)
      result.boundaryRows() // a second, different accessor: must reuse the memoized value, not recompute
      calls must beEqualTo(1)
    }
  }
