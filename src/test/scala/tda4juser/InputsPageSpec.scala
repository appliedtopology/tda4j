package tda4juser

import org.specs2.mutable.Specification

/** The snippets of the user guide page "Time series, graphs and functions", from a user's side of the package boundary:
  * one import, and the answers the page states.
  */
class InputsPageSpec extends Specification:
  import org.appliedtopology.tda4j.*

  "the time series snippet" should {
    "pick a delay of a quarter of the period and show the period as one long loop" in {
      val signal = Seq.tabulate(200)(t => math.sin(2 * math.Pi * t / 40))
      val delay = TakensEmbedding.delayByAutocorrelation(signal, maxLag = 30)
      val windows = TakensEmbedding(signal, dimension = 2, delay = delay)
      (delay must beBetween(9, 11)) and (Persistence(windows, maxDimension = 1)
        .dim(1)
        .longest
        .get
        .persistence must beGreaterThan(0.5))
    }
  }

  "the graph snippets" should {
    "measure along the circle, and see the loop of the neighbour graph" in {
      val circle =
        PointCloud(Array.tabulate(100)(i => Array(math.cos(2 * math.Pi * i / 100), math.sin(2 * math.Pi * i / 100))))
      val along = GraphMetric.nearestNeighbours(circle, k = 2)
      (along.distance(0, 50) must beCloseTo(math.Pi, 1e-3)) and
        (Persistence(along, maxDimension = 1).dim(1).longest.isDefined must beTrue)
    }

    "see the weighted square as a loop from 2.0 on, and the unweighted pentagon as a loop" in {
      val network = WeightedGraph(4, Seq((0, 1, 1.0), (1, 2, 1.5), (2, 3, 1.0), (3, 0, 2.0)))
      val pentagon = WeightedGraph.unweighted(5, Seq((0, 1), (1, 2), (2, 3), (3, 4), (4, 0)))
      (Persistence(network).dim(1).triples must beEqualTo(List((1, 2.0, Double.PositiveInfinity)))) and
        (Persistence(pentagon).bettiNumbers must beEqualTo(Vector(1, 1, 0)))
    }
  }

  "the lower-star snippet" should {
    "give the three minima of the path, and the two bars of the superlevel sets in negated units" in {
      val heights = Vector(0.0, 3.0, 1.0, 4.0, 2.0)
      val edges = Seq((0, 1), (1, 2), (2, 3), (3, 4))
      val sublevel = Persistence(LowerStar.ofGraph(5, edges, heights), maxDimension = 0).triples.sorted
      val superlevel =
        Persistence(LowerStar.ofGraph(5, edges, heights, superlevel = true), maxDimension = 0).triples.sorted
      (sublevel must beEqualTo(List((0, 0.0, Double.PositiveInfinity), (0, 1.0, 3.0), (0, 2.0, 4.0)))) and
        (superlevel must beEqualTo(List((0, -4.0, Double.PositiveInfinity), (0, -3.0, -1.0))))
    }
  }

  "the directed snippet" should {
    "see the directed 3-cycle as a loop and the transitive triangle as filled" in {
      val cycle = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (2, 0, 1.0)), maxDimension = 1)
      val transitive = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (0, 2, 1.0)), maxDimension = 2)
      (Persistence(cycle).bettiNumbers must beEqualTo(Vector(1, 1))) and
        (Persistence(transitive).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
    }

    "count 4, 12, 24 and 24 cells in the complete digraph on 4 vertices, with nine 3-spheres" in {
      val all = for a <- 0 until 4; b <- 0 until 4 if a != b yield (a, b, 1.0)
      val stream = DirectedFlagStream(4, all, maxDimension = 3)
      val counts =
        Iterator.from(0).takeWhile(stream.iterateDimension.isDefinedAt).map(stream.iterateDimension(_).size).toList
      (counts must beEqualTo(List(4, 12, 24, 24))) and (Persistence(stream).bettiNumbers must beEqualTo(
        Vector(1, 0, 0, 9)
      ))
    }
  }
