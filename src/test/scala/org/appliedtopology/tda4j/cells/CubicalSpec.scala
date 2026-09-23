package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck
import org.scalacheck.*

import math.Fractional.Implicits.infixFractionalOps

/** Core `Cube`/`OrderedCell` correctness, independent of any stream or homology engine: the boundary formula's own
  * algebraic properties. Deliberately checked over a SIGNED field that isn't F2 (F3 here) in addition to Double -- F2
  * can't distinguish a correctly-alternating sign from a constant one (-1 = 1 mod 2), so an F2-only dd=0 check would
  * pass even with the "raw coordinate position" sign bug the advisor flagged before any of this was written (see
  * `cubeIsOrderedCell`'s own doc and `.claude/WORKLOG-cubical.md`).
  */
class CubicalSpec extends mutable.Specification with ScalaCheck:
  "The `Cube` type should" >> {
    "correctly report dimension" >> {
      (Cube.vertex(1, 2, 3).dim must beEqualTo(0)) and
        (Cube.unitCube(1, 2, 3).dim must beEqualTo(3)) and
        (Cube(Seq(0, 0, 0), Set(0, 2)).dim must beEqualTo(2))
    }
    "round-trip lattice coordinates through vertex/unitCube" >> {
      (Cube.vertex(3, -2, 5).latticeCoordinates must beEqualTo(Seq(3, -2, 5))) and
        (Cube.unitCube(3, -2, 5).latticeCoordinates must beEqualTo(Seq(3, -2, 5)))
    }
    "be a genuine total order, consistent with sorting, on a small generated set" >> {
      val cubes = for
        a <- 0 to 2
        b <- 0 to 2
        nondeg <- Seq(Set.empty[Int], Set(0), Set(1), Set(0, 1))
      yield Cube(Seq(a, b), nondeg)
      val sorted = cubes.sorted(using cubeOrdering)
      val sortedOk = sorted.sliding(2).forall {
        case Seq(x, y) => cubeOrdering.compare(x, y) <= 0
        case _         => true
      }
      val reflexiveOk = cubes.forall(c => cubeOrdering.compare(c, c) == 0)
      (sortedOk must beTrue) and (reflexiveOk must beTrue)
    }
  }

  given Double is Field = Field.DoubleApproximated(1e-9)
  val GF3 = new FiniteField(3)
  import GF3.Fp
  import GF3.given

  /** Every cube reachable within `ambientDim` axes and coordinates in `0 to maxCoord` (both directions, i.e.
    * doubled-encoding entries `0 to 2*maxCoord`), exhaustively -- small enough to be a real exhaustive check, not just
    * a spot sample, for the dimensions cubical complexes actually get used at (2D images, 3D voxel grids).
    */
  def allCubes(ambientDim: Int, maxCoord: Int): Seq[Cube] =
    def go(n: Int): Seq[Seq[Int]] =
      if n == 0 then Seq(Seq.empty)
      else go(n - 1).flatMap(rest => (0 to 2 * maxCoord).map(v => rest :+ v))
    go(ambientDim).map(coords => Cube.fromEncoded(coords))

  def boundaryOfBoundaryIsZero[CoefficientT: Field](cube: Cube): Boolean =
    val ddc = Chain.from[Cube, CoefficientT](cube.boundary[CoefficientT]).boundary
    Chain.from[Cube, CoefficientT](ddc).isZero()

  "The boundary operator should satisfy d(d(x)) = 0" >> {
    "exhaustively in ambient dimension 2 (coords 0..4), over Double" >> {
      allCubes(2, 2).forall(c => boundaryOfBoundaryIsZero[Double](c)) must beTrue
    }
    "exhaustively in ambient dimension 3 (coords 0..2), over Double" >> {
      allCubes(3, 1).forall(c => boundaryOfBoundaryIsZero[Double](c)) must beTrue
    }
    "exhaustively in ambient dimension 2 (coords 0..4), over F3 -- a signed, non-F2 field" >> {
      allCubes(2, 2).forall(c => boundaryOfBoundaryIsZero[Fp](c)) must beTrue
    }
    "exhaustively in ambient dimension 4 (coords 0..2), over F3" >> {
      allCubes(4, 1).forall(c => boundaryOfBoundaryIsZero[Fp](c)) must beTrue
    }
  }

  "The boundary of a top-dimensional cube should have exactly 2*dim terms, all one dimension lower" >> {
    val cube = Cube.unitCube(0, 0, 0)
    val bd = cube.boundary[Double]
    (bd.length must beEqualTo(6)) and
      (bd.forall((face, _) => face.dim == cube.dim - 1) must beTrue)
  }

  "A vertex (0-dimensional cube) should have an empty boundary" >> {
    Cube.vertex(1, 2).boundary[Double] must beEqualTo(Seq.empty)
  }
