package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** Each public complex object must reproduce, cell for cell AND value for value, the implementation class wrapped at
  * `k + 1` by hand -- Betti numbers alone would not catch a wrong `+1`.
  */
class ComplexesSpec extends Specification:
  private val points = Array(
    Array(0.0, 0.0),
    Array(1.0, 0.1),
    Array(0.5, 0.9),
    Array(1.6, 1.1),
    Array(-0.4, 1.3),
    Array(0.9, 1.7),
    Array(2.0, 0.2)
  )
  private val euclidean = EuclideanMetricSpace(points)

  private def listing(s: LevelwiseSimplexStream[Int, Double]): List[(Simplex[Int], Double)] =
    s.iterator.toList.map(c => (c, s.filtrationValue(c)))

  private def sameAs(actual: LevelwiseSimplexStream[Int, Double], expected: LevelwiseSimplexStream[Int, Double]) =
    (listing(actual).nonEmpty must beTrue).and(listing(actual) must beEqualTo(listing(expected)))

  "Truncated" should {
    "equal the hand-wrapped LimitedCofaceSimplexStream (public apply, not just ofCofaces), cell for cell and value for value" in
      // fresh stream instances on each side: coface streams carry cache state
      sameAs(
        Truncated(EnumeratingCofaceSimplexStream(euclidean), 1),
        LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(euclidean), 2)
      )
    "keep simplices up to dimension maxDimension + 1" in {
      val s = Truncated(EnumeratingCofaceSimplexStream(euclidean), 1)
      (s.iterator.map(_.dim).max must beEqualTo(2))
        .and(Truncated(EnumeratingCofaceSimplexStream(euclidean), 1) must not(beNull))
    }
    "reject a negative degree" in {
      Truncated(EnumeratingCofaceSimplexStream(euclidean), -1) must throwAn[IllegalArgumentException]
    }
  }

  "the complex objects" should {
    "Cech matches CechCofaceSimplexStream wrapped by hand" in
      sameAs(Cech(euclidean, 1), LimitedCofaceSimplexStream(CechCofaceSimplexStream(euclidean), 2))
    "Witness (lazy) matches LazyWitnessSimplexStream wrapped by hand" in {
      val landmarks = IndexedSeq(0, 2, 4, 6)
      sameAs(
        Witness(euclidean, landmarks, 1),
        LimitedCofaceSimplexStream(LazyWitnessSimplexStream(euclidean, landmarks, 2), 2)
      )
    }
    "Witness (general) matches WitnessCofaceSimplexStream wrapped by hand" in {
      val landmarks = IndexedSeq(0, 2, 4, 6)
      sameAs(
        Witness(euclidean, landmarks, 1, Witness.Variant.General),
        LimitedCofaceSimplexStream(WitnessCofaceSimplexStream(euclidean, landmarks), 2)
      )
    }
    "Dowker matches DowkerCofaceSimplexStream wrapped by hand, and dual differs in vertex count" in {
      val relation = Array(Array(0.0, 1.0, 2.0), Array(1.0, 0.0, 1.0), Array(2.0, 1.0, 0.0), Array(0.5, 0.5, 3.0))
      val primal = Dowker(relation, 1)
      val dual = Dowker(relation, 1, dual = true)
      sameAs(primal, LimitedCofaceSimplexStream(DowkerCofaceSimplexStream(relation), 2))
        .and(primal.iterator.count(_.dim == 0) must beEqualTo(4))
        .and(dual.iterator.count(_.dim == 0) must beEqualTo(3))
    }
    "DtmRips matches DtmRipsSimplexStream wrapped by hand, from weights and from neighbours" in {
      val f = DistanceToMeasure(euclidean, 2, 2.0)
      sameAs(DtmRips(euclidean, f, 1), LimitedCofaceSimplexStream(DtmRipsSimplexStream(euclidean, f), 2))
        .and(sameAs(DtmRips.fromNeighbours(euclidean, 2, 1), DtmRips(euclidean, f, 1)))
    }
    "SparseRips matches SheehyRipsSimplexStream wrapped by hand" in
      sameAs(SparseRips(euclidean, 0.5, 1), LimitedCofaceSimplexStream(SheehyRipsSimplexStream(euclidean, 0.5), 2))
  }
