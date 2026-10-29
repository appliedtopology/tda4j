package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

class ToMAToSpec extends Specification with ScalaCheck:

  // A path 0 - 1 - 2 - 3 - 4 - 5 with three peaks: vertex 3 (6.0), vertex 1 (5.0), vertex 5 (4.0). The valley between
  // the peaks at 3 and 1 is vertex 2 (2.0), 3 deep from the lower peak (5.0 - 2.0); between 3 and 5 it is vertex 4 (1.5),
  // 2.5 deep (4.0 - 1.5).
  private val density = IndexedSeq(1.0, 5.0, 2.0, 6.0, 1.5, 4.0)
  private val path: Int => Iterable[Int] = v => List(v - 1, v + 1).filter(u => u >= 0 && u < 6)

  private def finite(bars: List[PersistenceBar[Double, Nothing]]) =
    bars.filterNot(_.death.isPosInfinity).map(b => (-b.birth, -b.death)).sorted

  "diagram" should {
    "have a finite bar for each valley and an essential bar for the highest peak" >> {
      val bars = ToMATo.diagram(density, path)
      finite(bars) must beEqualTo(List((4.0, 1.5), (5.0, 2.0)))
      bars.filter(_.death.isPosInfinity).map(b => -b.birth) must beEqualTo(List(6.0))
    }

    "use the sublevel convention, persistence the depth of the valley" >> {
      val bars = ToMATo.diagram(density, path).filterNot(_.death.isPosInfinity)
      bars.map(_.persistence).sorted must beEqualTo(List(2.5, 3.0))
    }
  }

  "clusters" should {
    "merge a peak when its valley is shallower than tau, and only then" >> {
      val labelsAt = (tau: Double) => ToMATo.clusterGraph(density, path, tau).labels.toList
      // tau = 2.0: both valleys are deeper, three clusters, numbered by peak height: 3 is 0, 1 is 1, 5 is 2
      (labelsAt(2.0) must beEqualTo(List(1, 1, 0, 0, 0, 2))) and
        // 2.5 < tau < 3.0: the valley at 4 (2.5 deep) is shallower than tau, the one at 2 (3 deep) is not
        (labelsAt(2.7) must beEqualTo(List(1, 1, 0, 0, 0, 0))) and
        (labelsAt(3.5) must beEqualTo(List.fill(6)(0))) and
        // a valley exactly tau deep does not merge (the test is strict): 2.5 deep at tau = 2.5, 3 deep at tau = 3
        (labelsAt(2.5) must beEqualTo(labelsAt(2.0))) and
        (labelsAt(3.0) must beEqualTo(labelsAt(2.7))) and
        // tau = 0: only exact ties merge; here every local maximum stays a cluster
        (labelsAt(0.0) must beEqualTo(labelsAt(2.0)))
    }

    "report the peak vertex of each cluster, highest first" >> {
      ToMATo.clusterGraph(density, path, 2.0).peaks.toList must beEqualTo(List(3, 1, 5))
    }

    "treat an edge named from one end as an edge, and leave each component its own cluster" >> {
      val oneWay: Int => Iterable[Int] = v => if v == 0 then List(1) else Nil
      val result = ToMATo.clusterGraph(IndexedSeq(2.0, 1.0, 3.0), oneWay, Double.PositiveInfinity)
      (result.labels(0) must beEqualTo(result.labels(1))) and
        (result.labels(2) must not(beEqualTo(result.labels(0))))
    }

    "reject a negative tau and a neighbour outside the graph" >> {
      (ToMATo.clusterGraph(density, path, -1.0) must throwAn[IllegalArgumentException]) and
        (ToMATo.clusterGraph(IndexedSeq(1.0), _ => List(7), 1.0) must throwAn[IllegalArgumentException])
    }
  }

  "the diagram equals the degree-0 persistence of the lower-star filtration of minus the density" >> {
    // An independent oracle: the engines' barcode of the graph with vertex v entering at -f(v) and an edge at the later
    // of its ends. Random graphs, densities with few distinct values, so ties are exercised.
    val graphs = for
      n <- Gen.choose(2, 9)
      values <- Gen.listOfN(n, Gen.choose(0, 5).map(_.toDouble))
      edges <- Gen.listOf(Gen.zip(Gen.choose(0, n - 1), Gen.choose(0, n - 1)))
    yield (values.toIndexedSeq, edges.filter((a, b) => a != b))
    val property = forAll(graphs) { g =>
      val (values, edges) = g
      val adjacency = (v: Int) => edges.collect { case (a, b) if a == v => b; case (a, b) if b == v => a }
      val vertices = values.indices.map(v => (-values(v), Simplex.from(List(v))))
      val lines = edges.map((a, b) => (math.max(-values(a), -values(b)), Simplex.from(List(a, b))))
      val stream = ExplicitStreamBuilder.fromFilteredFacets(vertices ++ lines)
      val engine = Persistence(stream, maxDimension = 0, characteristic = 17).bars
      val expected = engine.map(b => (b.birth, b.death)).sorted
      val got = ToMATo.diagram(values, adjacency).map(b => (b.birth, b.death)).sorted
      got == expected
    }
    property
  }

  "cluster(points, k, tau)" should
    "find two well separated blobs and one cluster when tau is large" >> {
      val rng = new scala.util.Random(7)
      def blob(cx: Double, cy: Double) =
        Array.fill(40)(Array(cx + 0.3 * rng.nextGaussian(), cy + 0.3 * rng.nextGaussian()))
      val points = PointCloud(blob(0.0, 0.0) ++ blob(10.0, 0.0))
      val k = 8
      val bars = ToMATo.diagram(ToMATo.density(points, k), ToMATo.neighbourGraph(points, k))
      // 10 apart with 8 neighbours each, the blobs do not touch: two components, so two essential bars
      val merged = ToMATo.cluster(points, k, tau = 100.0)
      (bars.count(_.death.isPosInfinity) must beEqualTo(2)) and
        (merged.peaks.size must beEqualTo(2)) and
        (merged.labels.take(40).distinct.size must beEqualTo(1)) and
        (merged.labels.drop(40).distinct.size must beEqualTo(1)) and
        (merged.labels(0) must not(beEqualTo(merged.labels(79))))
    }
