package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.homology.HomologyFixtures
import SimplicialSetStream.fromStream

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.{mutable as s2mutable, ScalaCheck}

/** `fromStream` exercises no degeneracy machinery at all (every face it produces is a bare generator) -- it's a
  * plumbing check that the `CellStream` adapter and `FiniteSimplicialSet` wiring agree with the existing, already-
  * validated `SimplicialHomologyContext` engine on the exact same input, not evidence that `faceOf`/`insertOuter` are
  * correct (that's `SSetElementSpec`/`SimplicialSetHomologySpec`, via the hand-built fixtures).
  */
class SimplicialSetStreamSpec extends s2mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  private def bettiFromOurs(diagram: List[(Int, Int, Int)]): Map[Int, Int] =
    diagram.collect { case (dim, _, Int.MaxValue) => dim }.groupBy(identity).view.mapValues(_.size).toMap

  private def bettiFromReference(diagram: List[(Int, Double, Double)]): Map[Int, Int] =
    diagram
      .collect { case (dim, _, death) if death.isPosInfinity => dim }
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap

  private def accountsForAllCells(diagram: List[(Int, Int, Int)], cells: Int): Boolean =
    val (finite, essential) = diagram.partition { case (_, _, death) => death != Int.MaxValue }
    finite.size * 2 + essential.size == cells

  private def ourBetti(stream: CellStream[Simplex[Int], ?]): (Map[Int, Int], Int) =
    val sset = fromStream(stream)
    given (Simplex[Int] is OrderedCell) = sset.cellInstance
    val diagram =
      CellularHomologyContext[Simplex[Int], Double, Int]().persistentHomology(SimplicialSetStream(sset)).diagramAt(0)
    (bettiFromOurs(diagram), diagram.size)

  "fromStream agrees with SimplicialHomologyContext on Betti numbers, for hand-built fixtures" >> {
    val cases = List(HomologyFixtures.triangleCells, HomologyFixtures.tetrahedronCells, HomologyFixtures.torusCells)
    forall(cases) { cells =>
      val stream = StreamFixtures.explicitStream(cells)
      val (betti, _) = ourBetti(stream)

      val reference = SimplicialHomologyContext[Int, Double, Double]()
      val theirs = reference.persistentHomology(StreamFixtures.explicitStream(cells)).diagramAt(Double.PositiveInfinity)

      betti === bettiFromReference(theirs)
    }
  }

  "fromStream's diagram accounts for exactly one bar-cell-slot per generator, hand-built fixtures" >> {
    val cases = List(HomologyFixtures.triangleCells, HomologyFixtures.tetrahedronCells, HomologyFixtures.torusCells)
    forall(cases) { cells =>
      val stream = StreamFixtures.explicitStream(cells)
      val sset = fromStream(stream)
      given (Simplex[Int] is OrderedCell) = sset.cellInstance
      val diagram =
        CellularHomologyContext[Simplex[Int], Double, Int]().persistentHomology(SimplicialSetStream(sset)).diagramAt(0)
      accountsForAllCells(diagram, cells.size) must beTrue
    }
  }

  // Past 4 vertices: an unordered-Set face list is hash-ordered from 5 elements on (see SimplexBoundarySpec).
  "fromStream lists each generator's faces as d_0..d_n (vertex i removed), and validates, up to 7 vertices" >>
    forall(1 to 7) { n =>
      val spx = Simplex((0 until n).map(v => 2 * v + 1)*)
      val sset = fromStream(StreamFixtures.explicitStream(Seq(0.0 -> spx)))
      val expected = if n == 1 then IndexedSeq.empty else spx.toIndexedSeq.map(v => SSetElement(Nil, spx - v))
      sset.faces(spx) must beEqualTo(expected)
    }

  "fromStream on a full 6-simplex (every face present) passes validate()" >> {
    val cells = (1 to 7).flatMap(k => (0 until 7).combinations(k).map(vs => 0.0 -> Simplex(vs*)))
    fromStream(StreamFixtures.explicitStream(cells)).validate() must beEmpty
  }

  "fromStream agrees with SimplicialHomologyContext on Betti numbers, random Vietoris-Rips clouds" >>
    forAll(matrixGen(Gen.choose(-1.0, 1.0), Gen.chooseNum(2, 4), Gen.chooseNum(6, 10))) { pts =>
      val metricSpace = EuclideanMetricSpace(pts)
      val vrStream = LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), 2)
      val (betti, _) = ourBetti(vrStream)

      val reference = SimplicialHomologyContext[Int, Double, Double]()
      val theirs = reference
        .persistentHomology(LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace), 2))
        .diagramAt(Double.PositiveInfinity)

      betti === bettiFromReference(theirs)
    }
