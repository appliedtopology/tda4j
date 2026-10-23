package org.appliedtopology.tda4j

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import org.specs2.{ScalaCheck, Specification}
import org.specs2.execute.Result
import org.specs2.scalacheck.Parameters

/** Weighted alpha complexes (`AlphaComplexDQP.weighted`, i.e. `PowerDistance.weight != 0`, Definition 6/10 in Carlsson
  * & Carlsson 2024) were never exercised end-to-end by any existing test before this session: `AlphaComplexSpec`'s
  * property suite always builds an unweighted `PowerDistance` (`AlphaShapes(...)` has no weighted entry point), and
  * `HelixDelaunay` -- the only cross-validation ground truth available -- computes a plain Euclidean Delaunay
  * triangulation with no notion of power weights at all, so it can't serve as ground truth here regardless.
  *
  * Without an independent ground truth, this checks the same *internal* structural invariants
  * `AlphaComplexSpec.alphaProperties` checks for the unweighted case (face closure, no duplicates, filtration
  * sortedness/monotonicity, dimension-bucket consistency) -- weaker than a full correctness proof, but real coverage:
  * it would have caught, for instance, `weight(i)` not being threaded through `ballRadius`/`gram`/ `dualLinear`
  * correctly, or the sort/monotonicity-clamp fixes from WORKLOG.md regressing specifically in the weighted path.
  */
class AlphaComplexDQPWeightedSpec extends org.specs2.mutable.Specification with ScalaCheck:
  // Both properties fail on the first case or two when the weights are mishandled (every mutation of the weight terms
  // tried failed within a handful of cases); 400 per property on the JVM, a tenth of that on Scala.js (`TestPlatform`).
  given Parameters = Parameters(minTestsOk = TestPlatform.propertyCases(400))

  // Weights scaled to roughly match the [0,1)^d point cloud, so they meaningfully shift
  // power distances without dwarfing them entirely. (maxPower = Infinity below means no
  // point's ball ever gets killed outright by ballRadius(i, maxPower) = sqrt(maxPower +
  // weight(i)) regardless of weight sign -- that only matters for a finite maxPower,
  // orthogonal to what this spec is checking.)
  private val pointsAndWeightsGen = for
    dim <- Gen.chooseNum(2, 5)
    n <- Gen.chooseNum(6, 12)
    points <- Gen.listOfN(n * dim, Gen.double).map(_.toArray.grouped(dim).toArray)
    weights <- Gen.listOfN(n, Gen.chooseNum(-0.3, 0.3)).map(_.toArray)
  yield (points, weights)

  // Mirrors AlphaComplexSpec.alphaProperties's exact structure deliberately: every
  // .forall result is wrapped immediately in `must beTrue` and chained with `and`,
  // rather than combined with a raw &&. With both org.scalacheck.Prop.forAll and
  // specs2's ScalaCheck/ValueCheck implicits in scope, a raw && chain over .forall
  // results on a Seq[Simplex[Int]] resolves .forall to a specs2 ValueCheck-based
  // overload instead of plain Boolean.forall (confirmed directly from the compiler
  // error when this was first tried) -- immediately forcing each .forall's expected
  // type to Boolean via `must beTrue` sidesteps that ambiguity, the same way the
  // existing, working alphaProperties already does.
  private def hasCleanStructure(ac: AlphaComplexDQP, n: Int): Prop =
    val layerByDimension = (0 to ac.maxDimension).map(d => ac.cellsOfDimension(d).toSeq)
    val allSimplices: IndexedSeq[Simplex[Int]] = layerByDimension.flatten
    val simplicesByDimension = layerByDimension.map(_.toSet)

    (layerByDimension.forall(layer => layer.distinct.size == layer.size) must beTrue) and
      (allSimplices.forall { simplex =>
        simplex.toSeq.forall(v => v >= 0 && v < n) &&
        (simplex.dim == 0 || simplex.toSeq.forall(v => simplicesByDimension(simplex.dim - 1).contains(simplex - v)))
      } must beTrue) and
      (layerByDimension.forall(layer =>
        layer.map(ac.filtrationValue).toSeq == layer.map(ac.filtrationValue).toSeq.sorted
      ) must beTrue) and
      (allSimplices.forall { simplex =>
        simplex.dim == 0 || simplex.toSeq.forall { v =>
          ac.filtrationValue(simplex - v) <= ac.filtrationValue(simplex)
        }
      } must beTrue) and
      (allSimplices.forall(simplex => simplicesByDimension(simplex.dim).contains(simplex)) must beTrue)

  // The power distance is pi_i(y) = |y - x_i|^2 - p_i and a simplex enters at the least power at which its weighted
  // balls meet (Definition 10). The two properties below hold for ANY weights, so they cannot tell a weight with the wrong
  // sign from the right one (mutating the sign of a weight term changed none of their verdicts); these values are derived
  // by hand.
  "weighted alpha complexes should give the values derived by hand" >> {
    def expect(points: Array[Array[Double]], weights: Array[Double], dim: Int)(cells: (Simplex[Int], Double)*) =
      val ac = AlphaComplexDQP.weighted(points, weights, Double.PositiveInfinity, dim)
      Result.foreach(cells) { (cell, value) =>
        (ac.contains(cell) must beTrue).and(ac.filtrationValue(cell) must beCloseTo(value, 1e-9))
      }
    val line = Array(Array(0.0), Array(1.0))
    val (a, b, ab) = (Simplex(0), Simplex(1), Simplex(0, 1))
    // The balls meet at y = (1 + p0 - p1) / 2, where the power is y^2 - p0.
    expect(line, Array(0.3, -0.3), 1)(a -> -0.3, b -> 0.3, ab -> 0.34)
      .and(
        expect(line, Array(0.0, 0.5), 1)(a -> 0.0, b -> -0.5, ab -> 0.0625)
      )
      .and(
        // p1 - p0 = 1.5 exceeds |x0 - x1|^2 = 1: x0 lies outside its own power cell, so the vertex enters with the edge.
        expect(line, Array(0.0, 1.5), 1)(a -> 0.0625, b -> -1.5, ab -> 0.0625)
      )
      .and(
        // An acute triangle: the weighted circumcentre (0.55, 0.3) has power 0.3425 to each vertex.
        expect(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.9)), Array(0.05, -0.05, 0.02), 2)(
          Simplex(0, 1, 2) -> 0.3425
        )
      )
  }

  "weighted alpha complexes below a power limit keep exactly the simplices whose value is within it" >> {
    // Values -0.3, 0.3 and 0.34 for the two vertices and the edge (previous example): at 0.2 only the first vertex is in.
    val ac = AlphaComplexDQP.weighted(Array(Array(0.0), Array(1.0)), Array(0.3, -0.3), 0.2, 1)
    (ac.contains(Simplex(0)) must beTrue)
      .and(ac.contains(Simplex(1)) must beFalse)
      .and(ac.contains(Simplex(0, 1)) must beFalse)
  }

  "weighted alpha complexes should" >> {
    "compute without exception and satisfy the same structural invariants as unweighted ones" >>
      forAll(pointsAndWeightsGen) { case (points, weights) =>
        val ac = AlphaComplexDQP.weighted(points, weights, Double.PositiveInfinity, points.head.length)
        hasCleanStructure(ac, points.length)
      }

    "reduce to the unweighted complex when every weight is exactly zero" >> {
      // Cross-check against the (separately, extensively verified) unweighted path itself,
      // as the closest thing to a ground truth available without Helix.
      val pointsGen = matrixGen[Double](Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(6, 12))
      forAll(pointsGen) { points =>
        val zeroWeighted =
          AlphaComplexDQP.weighted(points, Array.fill(points.length)(0.0), Double.PositiveInfinity, points.head.length)
        val unweighted = AlphaComplexDQP.euclidean(points, Double.PositiveInfinity, points.head.length)
        (0 to points.head.length).forall { d =>
          zeroWeighted.cellsOfDimension(d).toSet == unweighted.cellsOfDimension(d).toSet
        } must beTrue
      }
    }
  }
