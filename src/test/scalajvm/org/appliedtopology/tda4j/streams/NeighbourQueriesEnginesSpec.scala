package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.compiletime.asMatchable

import NeighbourQueriesFixtures.*

/** The engines that read the neighbour lists of a Euclidean cloud above the distance cache (`NeighbourQueriesSpec`
  * checks the lists): each is judged against itself run on a space that has no shortcut, so that the pass over all
  * pairs builds its lists.
  */
class NeighbourQueriesEnginesSpec extends Specification:
  sequential

  private def cell(c: Any): (Double, Long) = c.asMatchable match
    case x: PackedRipserCohomologyEngine[?]#DiameterIndex => (x.diameter, x.index)
    case _                                                => (Double.NaN, -1L)

  private def terms[C](chain: Chain[?, C])(using fr: C is Field): List[(Double, Long, String)] =
    chain.rawEntries
      .groupMapReduce(e => cell(e._1))(_._2)(fr.plus)
      .toList
      .collect { case ((d, i), k) if !fr.isEqual(k, fr.zero) => (d, i, k.toString) }
      .sortBy(_._2)

  "The packed Ripser engine reads them: the bars, cocycles and cycles of the pass's lists, and the longest edge" >> {
    val points = cloudWithRepeats(99, big)
    val fast = EuclideanMetricSpace(points, cacheDistances = false)
    val slow = noShortcut(points)
    val t = radiusFor(2.5, big) // small components, with cycles
    val f3 = FiniteField(3)
    import f3.given
    val a = PackedRipserCohomologyEngine[f3.Fp](fast, 1, maxFiltrationValue = Some(t))
    val b = PackedRipserCohomologyEngine[f3.Fp](slow, 1, maxFiltrationValue = Some(t))
    def flat[Cell](bars: List[PersistenceBar[Double, Chain[Cell, f3.Fp]]]) =
      // degree 0 by its ends alone: a component's chain has as many terms as the component has points
      bars.map(x => (x.dim, x.lower.toString, x.upper.toString, if x.dim == 0 then Nil else terms(x.representative)))
    val scanLongest = pass(slow, t).get.distances.max
    (a.usesNeighbourLists must beTrue) and (b.usesNeighbourLists must beTrue) and
      ((flat(a.persistentCohomology()) == flat(b.persistentCohomology())) must beTrue) and
      ((flat(a.persistentHomology(true)) == flat(b.persistentHomology(true))) must beTrue) and
      (flat(a.persistentHomology(true)).exists(_._1 == 1) must beTrue) and
      (a.longestListedEdge must beSome(scanLongest)) and (b.longestListedEdge must beSome(scanLongest))
  }

  "Without neighbour lists the engine has no longest edge of its own" >> {
    val points = cloudWithRepeats(100, big)
    val fast = EuclideanMetricSpace(points, cacheDistances = false)
    val f3 = FiniteField(3)
    import f3.given
    val dense =
      PackedRipserCohomologyEngine[f3.Fp](fast, 1, maxFiltrationValue = Some(0.0), neighbourLists = Some(false))
    dense.longestListedEdge must beNone
  }

  "ParallelRipser ranks the same edges and finds the same bars from the lists, and reads its longest edge off them" >> {
    val points = cloudWithRepeats(101, big)
    val fast = EuclideanMetricSpace(points, cacheDistances = false)
    val slow = noShortcut(points)
    val t = radiusFor(2.5, big)
    val scanLongest = pass(slow, t).get.distances.max
    val ties = TestPlatform.perPlatform(
      jvm = ParallelPackedCohomology.EdgeTies.values.toSeq,
      js = Seq(ParallelPackedCohomology.EdgeTies.Lexicographic, ParallelPackedCohomology.EdgeTies.Shuffled)
    )
    val rankings = ties.map { order =>
      val fromLists = ParallelPackedCohomology.EdgeRanking(
        big,
        fast.distance(_, _),
        t,
        order,
        edgeLists = fast.neighbourListsWithin(t, Long.MaxValue)
      )
      val fromPass = ParallelPackedCohomology.EdgeRanking(big, slow.distance(_, _), t, order)
      def flat(r: ParallelPackedCohomology.EdgeRanking) =
        (
          r.edgeA.toSeq,
          r.edgeB.toSeq,
          r.edgeLength.toSeq.map(_.toString),
          r.neighbours.map(_.toSeq).toSeq,
          r.neighbourRank.map(_.toSeq).toSeq
        )
      (order, flat(fromLists) == flat(fromPass), fromLists.edgeA.length)
    }
    val runs = Seq(fast, slow).map(ParallelRipser.run(_, 1, t, 17, withPairs = false))
    // `==` and a Boolean, not `beEqualTo` on the two listings: specs2 builds an edit-distance table of them, even when
    // they are equal, and 8,000 x 8,000 bars is 1.4 GB (`rules/testing.md`, "Compare big values")
    (rankings.filter(!_._2) must beEmpty) and (rankings.map(_._3).distinct.size must beEqualTo(1)) and
      ((runs.head.bars == runs.last.bars) must beTrue) and (runs.head.longestEdge must beEqualTo(scanLongest)) and
      (runs.last.longestEdge must beEqualTo(scanLongest))
  }

  "The diagram's last filtration value and bars do not depend on the shortcut, on both Ripser engines" >> {
    val points = cloudWithRepeats(102, big)
    val fast = EuclideanMetricSpace(points, cacheDistances = false)
    val slow = noShortcut(points)
    val t = radiusFor(2.5, big)
    val scanLongest = pass(slow, t).get.distances.max
    val engines = Seq(Persistence.Engine.Ripser, Persistence.Engine.ParallelRipser)
    val results = engines.map { engine =>
      def diagram(space: FiniteMetricSpace[Int]) =
        Persistence(space, maxDimension = 1, maxFiltrationValue = t, engine = engine)
      def bars(d: PersistenceDiagram[?]) = d.bars.map(x => (x.dim, x.lower.toString, x.upper.toString))
      (diagram(fast).lastFiltrationValue, diagram(slow).lastFiltrationValue, bars(diagram(fast)) == bars(diagram(slow)))
    }
    results must beEqualTo(Seq((scanLongest, scanLongest, true), (scanLongest, scanLongest, true)))
  }
