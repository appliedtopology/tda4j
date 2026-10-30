package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class FlagComplexSpec extends Specification:

  private val square = Seq((0, 1, 1.0), (1, 2, 1.5), (2, 3, 1.0), (3, 0, 2.0))

  private def cellsAndValues(stream: StratifiedCellStream[Simplex[Int], Double]): Map[Simplex[Int], Double] =
    Iterator
      .from(0)
      .takeWhile(stream.iterateDimension.isDefinedAt)
      .flatMap(stream.iterateDimension(_))
      .map(cell => cell -> stream.filtrationValue(cell))
      .toMap

  /** The same graph as a dense distance matrix, `Infinity` where there is no edge. */
  private def dense(graph: WeightedGraph) =
    ExplicitMetricSpace(IndexedSeq.tabulate(graph.n, graph.n)(graph.distance(_, _)).map(_.toIndexedSeq))

  private def randomGraph(n: Int, seed: Long, density: Double) =
    val rng = new scala.util.Random(seed)
    WeightedGraph(
      n,
      for a <- 0 until n; b <- a + 1 until n if rng.nextDouble() < density yield (a, b, 0.5 + rng.nextDouble())
    )

  "WeightedGraph" should {
    "answer distances from the edges: the weight, 0 on the diagonal, Infinity elsewhere" >> {
      val graph = WeightedGraph(4, square)
      (graph.distance(0, 1) must beEqualTo(1.0)) and (graph.distance(1, 0) must beEqualTo(1.0)) and
        (graph.distance(2, 2) must beEqualTo(0.0)) and (graph.distance(0, 2) must beEqualTo(
          Double.PositiveInfinity
        )) and
        (graph.edgeCount must beEqualTo(4)) and (graph.minimumEnclosingRadius must beEqualTo(2.0))
    }

    "keep the lightest of a repeated edge, drop an infinite edge and ignore a loop" >> {
      val graph = WeightedGraph(3, Seq((0, 1, 3.0), (1, 0, 2.0), (1, 2, Double.PositiveInfinity), (2, 2, 1.0)))
      (graph.distance(0, 1) must beEqualTo(2.0)) and (graph.edgeCount must beEqualTo(1)) and
        (graph.distance(1, 2) must beEqualTo(Double.PositiveInfinity))
    }

    "list its edges once each, a < b, in order" >> {
      WeightedGraph(4, square).edges.toList must beEqualTo(List((0, 1, 1.0), (0, 3, 2.0), (1, 2, 1.5), (2, 3, 1.0)))
    }

    "reject an edge outside the graph, a negative weight and NaN" >> {
      (WeightedGraph(2, Seq((0, 2, 1.0))) must throwAn[IllegalArgumentException]) and
        (WeightedGraph(2, Seq((0, 1, -1.0))) must throwAn[IllegalArgumentException]) and
        (WeightedGraph(2, Seq((0, 1, Double.NaN))) must throwAn[IllegalArgumentException])
    }
  }

  "FlagComplex" should {
    "have the cells and the values of the Vietoris-Rips complex of the matrix, cell for cell" >> {
      val graph = randomGraph(14, seed = 11L, density = 0.4)
      val sparse = FlagComplex(graph, maxDimension = 2)
      val reference = VietorisRips(dense(graph), maxDimension = 2, maxFiltrationValue = graph.minimumEnclosingRadius)
      val (mine, theirs) = (cellsAndValues(sparse), cellsAndValues(reference))
      (mine.keySet must beEqualTo(theirs.keySet)) and (mine must beEqualTo(theirs)) and
        (mine.keys.map(_.size).max must beEqualTo(4))
    }

    "give the same barcode as the Ripser engine on the matrix, in every degree up to 2" >> {
      val graph = randomGraph(16, seed = 5L, density = 0.45)
      val bars = Persistence(graph, maxDimension = 2).triples.sorted
      val viaMatrix =
        Persistence(dense(graph), maxDimension = 2, maxFiltrationValue = graph.minimumEnclosingRadius).triples.sorted
      (bars must beEqualTo(viaMatrix)) and (bars.exists(_._1 == 1) must beTrue)
    }

    "see the square as a loop that is born with its last edge and never filled" >> {
      val diagram = Persistence(WeightedGraph(4, square), maxDimension = 1)
      diagram.dim(1).triples must beEqualTo(List((1, 2.0, Double.PositiveInfinity)))
    }

    "fill the loop when its diagonals enter, killing the bar" >> {
      val graph = WeightedGraph(4, square ++ Seq((0, 2, 3.0), (1, 3, 3.0)))
      Persistence(graph, maxDimension = 2).dim(1).triples must beEqualTo(List((1, 2.0, 3.0)))
    }

    "give a cycle representative of the loop: four edges with zero boundary" >> {
      val diagram = Persistence(WeightedGraph(4, square), maxDimension = 1, characteristic = 3)
      import diagram.given
      val loop = diagram.dim(1).bars.head
      (Chain.from(loop.representative.boundary).isZero() must beTrue) and
        (loop.representative.cells.map(_.toList).toSet must beEqualTo(
          Set(List(0, 1), List(1, 2), List(2, 3), List(0, 3))
        ))
    }

    "give the clique complex of an unweighted graph: a pentagon is a loop, K4 is a point" >> {
      val pentagon = WeightedGraph.unweighted(5, Seq((0, 1), (1, 2), (2, 3), (3, 4), (4, 0)))
      val k4 = WeightedGraph.unweighted(4, for a <- 0 until 4; b <- a + 1 until 4 yield (a, b))
      (Persistence(pentagon, maxDimension = 1).bettiNumbers must beEqualTo(Vector(1, 1))) and
        (Persistence(k4, maxDimension = 2).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
    }

    "leave out the cells entering after maxFiltrationValue" >> {
      val stream = FlagComplex(WeightedGraph(4, square), maxDimension = 1, maxFiltrationValue = 1.2)
      cellsAndValues(stream).keySet.map(_.toList) must beEqualTo(
        Set(List(0), List(1), List(2), List(3), List(0, 1), List(2, 3))
      )
    }

    "compute a path of 40,000 vertices, where the distance matrix alone would be 12.8 GB" >> {
      val n = 40000
      val path = WeightedGraph(n, (0 until n - 1).map(i => (i, i + 1, 1.0 + (i % 7))))
      val diagram = Persistence(path, maxDimension = 1)
      (diagram.bettiNumbers must beEqualTo(Vector(1, 0))) and (diagram.dim(0).essential.size must beEqualTo(1))
    }
  }

  "Ripser.parseSparseGraph" should {
    "hold the same distances as the matrix of the same text" >> {
      val text = "0 1 1.0\n1 2 2.5\n0 3 4\n2 3 0.5\n"
      val graph = Ripser.parseSparseGraph(text)
      val matrix = Ripser.parseSparseDistanceMatrix(text)
      val differences = for i <- 0 until 4; j <- 0 until 4 if graph.distance(i, j) != matrix(i)(j) yield (i, j)
      differences must beEmpty
    }

    "refuse a pair listed twice, in either direction" >> {
      Ripser.parseSparseGraph("0 1 1.0\n1 0 2.0\n") must throwAn[IllegalArgumentException]
    }
  }
