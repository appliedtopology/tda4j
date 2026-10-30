package org.appliedtopology.tda4j

import scala.language.experimental.modularity

import org.specs2.mutable.Specification

class DirectedFlagStreamSpec extends Specification:

  private def allPairs(n: Int, weight: Double = 1.0) =
    for a <- 0 until n; b <- 0 until n if a != b yield (a, b, weight)

  private def cellCounts(stream: DirectedFlagStream): List[Int] =
    Iterator.from(0).takeWhile(stream.iterateDimension.isDefinedAt).map(stream.iterateDimension(_).size).toList

  "DirectedSimplex" should {
    val GF3 = new FiniteField(3)
    import GF3.{Fp, given}
    given Ordering[DirectedSimplex] = DirectedSimplex.intrinsicOrdering

    "have the boundary of an ordered simplex, with alternating signs, in the order of the vertices" >> {
      val boundary = DirectedSimplex(2, 0, 1).boundary[Fp]
      (boundary.map(_._1.vertices.toList) must beEqualTo(List(List(0, 1), List(2, 1), List(2, 0)))) and
        (boundary.map(_._2.toUInt) must beEqualTo(List(1, 2, 1)))
    }

    "have boundary of boundary zero over F3, up to 7 vertices" >>
      forall(1 to 7) { n =>
        val cell = DirectedSimplex(Vector.tabulate(n)(10 - _))
        Chain
          .from[DirectedSimplex, Fp](Chain.from[DirectedSimplex, Fp](cell.boundary[Fp]).boundary)
          .isZero() must beTrue
      }

    "reject a repeated vertex" >> {
      DirectedSimplex(1, 2, 1) must throwAn[IllegalArgumentException]
    }
  }

  "DirectedFlagStream" should {
    "count the ordered cliques: 3 vertices, 6 edges, 6 triangles in the complete directed graph" >> {
      cellCounts(DirectedFlagStream(3, allPairs(3), maxDimension = 2)) must beEqualTo(List(3, 6, 6))
    }

    "see a directed cycle as a loop and a transitive triangle as filled" >> {
      val cycle = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (2, 0, 1.0)), maxDimension = 1)
      val transitive = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 1.0), (0, 2, 1.0)), maxDimension = 2)
      (Persistence(cycle).bettiNumbers must beEqualTo(Vector(1, 1))) and
        (cellCounts(transitive) must beEqualTo(List(3, 3, 1))) and
        (Persistence(transitive).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
    }

    "give the complex of injective words: the complete directed graph on n vertices has D_n spheres of dimension n - 1" >> {
      // Farmer (1978), Bjorner and Wachs: the ordered tuples without repeats are a wedge of D_n spheres, D_n the number
      // of derangements: 1, 2, 9 for n = 2, 3, 4. A clique complex of the undirected graph would be a point.
      val two = Persistence(DirectedFlagStream(2, allPairs(2), maxDimension = 1)).bettiNumbers
      val three = Persistence(DirectedFlagStream(3, allPairs(3), maxDimension = 2)).bettiNumbers
      val four = Persistence(DirectedFlagStream(4, allPairs(4), maxDimension = 3)).bettiNumbers
      (two must beEqualTo(Vector(1, 1))) and (three must beEqualTo(Vector(1, 0, 2))) and
        (four must beEqualTo(Vector(1, 0, 0, 9)))
    }

    "see the same Betti numbers over Z/2 and Z/3" >> {
      def betti(p: Int) =
        Persistence(DirectedFlagStream(3, allPairs(3), maxDimension = 2), characteristic = p).bettiNumbers
      (betti(2) must beEqualTo(Vector(1, 0, 2))) and (betti(3) must beEqualTo(Vector(1, 0, 2)))
    }

    "give a transitive tournament the homology of a point, with one cell for every subset" >> {
      val tournament = for a <- 0 until 4; b <- a + 1 until 4 yield (a, b, 1.0)
      val stream = DirectedFlagStream(4, tournament, maxDimension = 3)
      (cellCounts(stream) must beEqualTo(List(4, 6, 4, 1))) and
        (Persistence(stream).bettiNumbers must beEqualTo(Vector(1, 0, 0, 0)))
    }

    "filter by the largest weight of a cell's edges" >> {
      val cycle = DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 2.0), (2, 0, 3.0)), maxDimension = 1)
      Persistence(cycle).triples.sorted must beEqualTo(
        List((0, 0.0, 1.0), (0, 0.0, 2.0), (0, 0.0, Double.PositiveInfinity), (1, 3.0, Double.PositiveInfinity))
      )
    }

    "leave out cells above the maximum filtration value" >> {
      val stream =
        DirectedFlagStream(3, Seq((0, 1, 1.0), (1, 2, 2.0), (2, 0, 3.0)), maxDimension = 1, maxFiltrationValue = 2.0)
      cellCounts(stream) must beEqualTo(List(3, 2))
    }

    "reject a loop, a repeated edge and an edge outside the graph" >> {
      (DirectedFlagStream(2, Seq((0, 0, 1.0)), maxDimension = 1) must throwAn[IllegalArgumentException]) and
        (DirectedFlagStream(2, Seq((0, 1, 1.0), (0, 1, 2.0)), maxDimension = 1) must throwAn[
          IllegalArgumentException
        ]) and
        (DirectedFlagStream(2, Seq((0, 2, 1.0)), maxDimension = 1) must throwAn[IllegalArgumentException])
    }
  }
