package org.appliedtopology.tda4j

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

/** Cross-checks `AlphaComplexDQPBuilder.cechNeighbours()`'s VP-tree-based implementation against a direct
  * reimplementation of the original O(N^2) all-pairs scan it replaced (see WORKLOG.md for the full rationale: this only
  * changes performance, not the candidate set, since every VP-tree hit still goes through the same exact pairwise check
  * as before). Exercises both unweighted (constant radius, where the query bound is exact) and weighted (varying
  * radius, where the query bound is a deliberately conservative superset) point clouds, since those take different code
  * paths.
  *
  * This needs a genuinely finite maxRadius to exercise the spatial-index branch at all -- AlphaShapeDQP's default
  * (maxRadius = Infinity, to match HelixDelaunay) takes the separate "everyone is everyone's neighbour" branch instead,
  * so none of the existing Alpha* specs touch this code path.
  */
class AlphaComplexDQPSpatialIndexSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Parameters = Parameters(minTestsOk = 500)

  private def bruteForceCechNeighbours(space: PowerDistance, maxPower: Double): IndexedSeq[IndexedSeq[Int]] =
    val n = space.size
    val radius = Array.tabulate(n)(space.ballRadius(_, maxPower))
    val alive = radius.map(_ >= 0.0)
    val adj = IndexedSeq.fill(n)(collection.mutable.SortedSet[Int]())
    for
      i <- 0 until n
      if alive(i)
      j <- i + 1 until n
      if alive(j)
    do
      val sum = radius(i) + radius(j)
      val bound = sum * sum
      if space.squaredDistance(i, j) <= bound * (1.0 + 1e-12) then
        adj(i) += j
        adj(j) += i
    adj.map(_.toIndexedSeq)

  private val pointsGen = matrixGen[Double](Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(6, 40))
  // Generous enough that a meaningful (not near-empty, not near-complete) Cech graph
  // results for points in [0,1)^d -- exercises real spatial-index pruning either way.
  private val radiusGen = Gen.chooseNum(0.1, 0.9)

  "cechNeighbours (VP-tree) should match the brute-force O(N^2) scan" >> {
    "for unweighted point clouds" >>
      forAll(pointsGen, radiusGen) { (points, maxRadius) =>
        val space = PowerDistance.euclidean(points)
        val builder = new AlphaComplexDQPBuilder(space, maxRadius * maxRadius, 1)
        val fast = builder.cechNeighbours().map(_.toSet)
        val slow = bruteForceCechNeighbours(space, maxRadius * maxRadius).map(_.toSet)
        fast must be_==(slow)
      }

    "for weighted point clouds (conservative superset bound, still exact after filtering)" >> {
      val weightedGen = for
        points <- pointsGen
        weights <- Gen.listOfN(points.length, Gen.chooseNum(-0.05, 0.05)).map(_.toArray)
        maxRadius <- radiusGen
      yield (points, weights, maxRadius)

      forAll(weightedGen) { case (points, weights, maxRadius) =>
        val space = PowerDistance.euclidean(points, weights)
        val builder = new AlphaComplexDQPBuilder(space, maxRadius * maxRadius, 1)
        val fast = builder.cechNeighbours().map(_.toSet)
        val slow = bruteForceCechNeighbours(space, maxRadius * maxRadius).map(_.toSet)
        fast must be_==(slow)
      }
    }
  }
