package org.appliedtopology.tda4j
package barcode

import org.specs2.mutable.Specification

/** Direct, hand-verified checks on the two combinatorial primitives [[BarcodeDistance]] builds on, independent of any
  * barcode/diagram framing. Both objects are `private[barcode]`, reachable here because this spec shares that package.
  */
class BipartiteMatchingSpec extends Specification:
  "HopcroftKarp.maximumMatching" >> {
    "finds a perfect matching when one exists" >> {
      // 0 -> {0,1}, 1 -> {1}, 2 -> {1,2}: a perfect matching (e.g. 0-0, 1-1, 2-2) exists.
      val adjacency: Int => Seq[Int] = Map(0 -> Seq(0, 1), 1 -> Seq(1), 2 -> Seq(1, 2))
      val matchLeft = HopcroftKarp.maximumMatching(3, 3, adjacency)
      matchLeft.forall(_ != -1) must beTrue
      matchLeft.toSet must haveSize(3)
      // every reported pair must actually be an edge of the input graph
      (0 until 3).forall(u => adjacency(u).contains(matchLeft(u))) must beTrue
    }

    "reports the true maximum when no perfect matching exists" >> {
      // both left vertices only reach right vertex 0: max matching size is 1, not 2.
      val adjacency: Int => Seq[Int] = Map(0 -> Seq(0), 1 -> Seq(0))
      val matchLeft = HopcroftKarp.maximumMatching(2, 1, adjacency)
      matchLeft.count(_ != -1) must beEqualTo(1)
    }

    "handles empty graphs" >> {
      HopcroftKarp.maximumMatching(0, 0, _ => Seq.empty) must beEqualTo(Array.empty[Int])
    }

    "handles a left vertex with no edges" >> {
      val adjacency: Int => Seq[Int] = Map(0 -> Seq(0), 1 -> Seq.empty)
      val matchLeft = HopcroftKarp.maximumMatching(2, 1, adjacency)
      matchLeft(1) must beEqualTo(-1)
      matchLeft.count(_ != -1) must beEqualTo(1)
    }
  }

  "Hungarian.minCostPerfectMatching" >> {
    "matches the brute-force-verified optimum on a textbook 3x3 example" >> {
      // Every one of the 6 permutations checked by hand: minimum total cost is 5, via
      // row0->col1 (1) + row1->col0 (2) + row2->col2 (2).
      val cost = Array(
        Array(4.0, 1.0, 3.0),
        Array(2.0, 0.0, 5.0),
        Array(3.0, 2.0, 2.0)
      )
      val (assignment, total) = Hungarian.minCostPerfectMatching(cost)
      total must beCloseTo(5.0, 1e-9)
      assignment.toSeq must beEqualTo(Seq(1, 0, 2)) // assignment(col) = row
    }

    "agrees with brute-force permutation search on random small matrices" >> {
      val rng = new scala.util.Random(42)
      val trials = for n <- 1 to 6 yield
        val cost = Array.fill(n, n)(rng.nextDouble() * 10.0)
        val (_, total) = Hungarian.minCostPerfectMatching(cost)
        val bruteForce = (0 until n).permutations
          .map(perm => perm.zipWithIndex.map((row, col) => cost(row)(col)).sum)
          .min
        (total, bruteForce)
      trials.forall((total, bruteForce) => math.abs(total - bruteForce) < 1e-9) must beTrue
    }

    "handles the empty matrix" >> {
      val (assignment, total) = Hungarian.minCostPerfectMatching(Array.empty)
      assignment.toSeq must beEqualTo(Seq.empty[Int]) // Array equality is by reference, so compare via .toSeq
      total must beEqualTo(0.0)
    }

    "handles a single entry" >> {
      val (assignment, total) = Hungarian.minCostPerfectMatching(Array(Array(7.0)))
      assignment.toSeq must beEqualTo(Seq(0))
      total must beCloseTo(7.0, 1e-9)
    }
  }
