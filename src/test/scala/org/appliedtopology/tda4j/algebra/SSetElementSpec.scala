package org.appliedtopology.tda4j
package algebra

import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures.ProjectiveGenerator
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures.SphereGenerator

import org.specs2.mutable

/** Direct, hand-verified unit tests for `insertOuter`/`faceOf` themselves -- independent of any fixture's
  * topological meaning, and independent of `FiniteSimplicialSet.validate()`'s own (integration-level) use of the
  * same functions. See `.claude/WORKLOG-simplicial-sets.md` for the full derivation of each example.
  */
class SSetElementSpec extends mutable.Specification:

  "insertOuter" >> {
    "s_0 s_0 (v) normalizes to the unique word [1, 0], not [0, 1]" >> {
      insertOuter(0, Nil) === List(0)
      insertOuter(0, List(0)) === List(1, 0)
    }

    "s_0 s_1 s_0 (y) = s_2 s_1 s_0 (y), cross-checked against the identities directly" >> {
      // s_0 s_1 s_0 = (s_0 s_1) s_0 = s_2 s_0 s_0 = s_2 (s_0 s_0) = s_2 s_1 s_0
      insertOuter(0, List(1, 0)) === List(2, 1, 0)
    }

    "already-sorted insertion is a no-op prepend" >> {
      insertOuter(1, List(0)) === List(1, 0)
      insertOuter(2, List(1, 0)) === List(2, 1, 0)
    }
  }

  "faceOf" >> {
    val rp3 = SimplicialSetFixtures.realProjectiveSpace(3)
    import ProjectiveGenerator.*

    def d(i: Int, elt: SSetElement[ProjectiveGenerator]): SSetElement[ProjectiveGenerator] =
      faceOf(i, elt.word, elt.generator, rp3.faces)
    def bare(g: ProjectiveGenerator): SSetElement[ProjectiveGenerator] = SSetElement(Nil, g)

    "all six d_i d_j = d_{j-1} d_i instances on RP3's top generator agree" >> {
      val pairs = for i <- 0 to 3; j <- (i + 1) to 3 yield (i, j)
      forall(pairs) { case (i, j) =>
        d(i, d(j, bare(E(3)))) === d(j - 1, d(i, bare(E(3))))
      }
    }

    "row (1,3) is the one that reaches faceOf's i > w1+1 branch, and it's correct" >> {
      // d_2(s_0(e_1)): word=[0], w1=0, i=2 > w1+1=1 -- the branch no sphere/RP2 fixture reaches.
      faceOf(2, List(0), E(1), rp3.faces) === SSetElement(List(0), E(0))
      // d_1 d_3 = d_2 d_1 (i=1, j=3): both sides route through that same intermediate step.
      d(1, d(3, bare(E(3)))) === d(2, d(1, bare(E(3))))
    }

    "minimal S^n's maximally-degenerate word is the decreasing staircase [n-2,...,0]" >> {
      SimplicialSetFixtures.minimalSphere(2).faces(SphereGenerator.Top).head.word === List(0)
      SimplicialSetFixtures.minimalSphere(3).faces(SphereGenerator.Top).head.word === List(1, 0)
    }

    "d_0 of S^3's degenerate 2-simplex over v hits the i < w1 branch" >> {
      // faceOf(0, [1,0], v): the S^3 trace the branch-coverage table cites.
      val s3 = SimplicialSetFixtures.minimalSphere(3)
      faceOf(0, List(1, 0), SphereGenerator.Vertex, s3.faces) === SSetElement(List(0), SphereGenerator.Vertex)
    }

    "the i < w1 rewrap can itself force insertOuter's cascading branch" >> {
      // y: dim 2, faces(y)(0) = s_0(z) (z: dim 0); the other two faces are unused by this trace.
      val w = "w" // dim-1 placeholder, never queried below
      val faces: String => IndexedSeq[SSetElement[String]] = {
        case "y" => IndexedSeq(SSetElement(List(0), "z"), SSetElement(Nil, w), SSetElement(Nil, w))
        case _   => IndexedSeq.empty
      }
      // faceOf(0, [1], y): i=0 < w1=1, inner = faceOf(0, [], y) = faces(y)(0) = ([0], z) -- nonempty.
      // Rewrap: insertOuter(w1-1=0, [0]) -- m=0 is NOT > head=0, so this is insertOuter's cascade
      // branch, not its prepend one: (0+1) :: insertOuter(0, []) = [1, 0].
      faceOf(0, List(1), "y", faces) === SSetElement(List(1, 0), "z")
    }

    "a deeper i < w1 rewrap, landing on the prepend case instead" >> {
      // y: dim 2, faces(y)(1) = s_0(z) (z: dim 0); faceOf(2, [3,0], y) hand-derived in the plan.
      val w = "w"
      val faces: String => IndexedSeq[SSetElement[String]] = {
        case "y" => IndexedSeq(SSetElement(Nil, w), SSetElement(List(0), "z"), SSetElement(Nil, w))
        case _   => IndexedSeq.empty
      }
      faceOf(2, List(3, 0), "y", faces) === SSetElement(List(2, 1, 0), "z")
    }
  }
