package org.appliedtopology.tda4j
package homology

import org.specs2.mutable.Specification

/** Tests `LatticeReduction` (`.claude/WORKLOG-toroidal-coordinates.md`) in isolation from any of `CircularCoordinates`'
  * geometric machinery -- every property here is a fact about Gram matrices and unimodular integer matrices, checkable
  * on hand-built fixtures without any persistent (co)homology computation at all.
  */
class LatticeReductionSpec extends Specification:

  private def transpose(m: Array[Array[Int]]): Array[Array[Int]] =
    Array.tabulate(m(0).length, m.length)((i, j) => m(j)(i))

  private def matMulIntDouble(a: Array[Array[Int]], b: Array[Array[Double]]): Array[Array[Double]] =
    val n = a.length
    val m = b(0).length
    val k = b.length
    Array.tabulate(n, m)((i, j) => (0 until k).map(t => a(i)(t).toDouble * b(t)(j)).sum)

  private def matMulDoubleInt(a: Array[Array[Double]], b: Array[Array[Int]]): Array[Array[Double]] =
    val n = a.length
    val m = b(0).length
    val k = b.length
    Array.tabulate(n, m)((i, j) => (0 until k).map(t => a(i)(t) * b(t)(j).toDouble).sum)

  private def determinant2(m: Array[Array[Double]]): Double =
    val n = m.length
    if n == 1 then m(0)(0)
    else if n == 2 then m(0)(0) * m(1)(1) - m(0)(1) * m(1)(0)
    else
      var det = 0.0
      var sign = 1.0
      for col <- 0 until n do
        val minor = Array.tabulate(n - 1, n - 1)((i, j) => m(i + 1)(if j < col then j else j + 1))
        det += sign * m(0)(col) * determinant2(minor)
        sign = -sign
      det

  private def approxEqual(a: Array[Array[Double]], b: Array[Array[Double]], tol: Double = 1e-6): Boolean =
    a.indices.forall(i => a(i).indices.forall(j => math.abs(a(i)(j) - b(i)(j)) <= tol))

  /** `reducedGram` really is `U^T gram U` (the definition of `basisChange`) -- checked externally with independent
    * matrix-multiply code, not by trusting `LatticeReduction`'s own internal `congruence` helper.
    */
  private def congruenceHoldsExternally(gram: Array[Array[Double]], result: LatticeReduction.Result): Boolean =
    val u = result.basisChange
    val computed = matMulIntDouble(transpose(u), matMulDoubleInt(gram, u))
    approxEqual(computed, result.reducedGram)

  "LatticeReduction.reduce" should {
    "produce a unimodular basis change (|det U| = 1) on a variety of Gram matrices" >> {
      val fixtures: Seq[Array[Array[Double]]] = Seq(
        Array(Array(4.0, 0.0), Array(0.0, 9.0)),
        Array(Array(4.0, 12.0), Array(12.0, 45.0)),
        Array(Array(4.0, 0.0, 0.0), Array(0.0, 9.0, 0.0), Array(0.0, 0.0, 16.0)),
        Array(Array(2.0, 1.0, 0.0), Array(1.0, 2.0, 1.0), Array(0.0, 1.0, 2.0)),
        Array(
          Array(10.0, 3.0, 1.0, 0.0),
          Array(3.0, 10.0, 2.0, 1.0),
          Array(1.0, 2.0, 10.0, 3.0),
          Array(0.0, 1.0, 3.0, 10.0)
        )
      )
      forall(fixtures) { gram =>
        val result = LatticeReduction.reduce(gram)
        val n = gram.length
        val det = determinant2(result.basisChange.map(_.map(_.toDouble)))
        (math.abs(math.abs(det) - 1.0) must beLessThan(1e-6)) and
          (congruenceHoldsExternally(gram, result) must beTrue) and
          (LatticeReduction.isReduced(result.reducedGram) must beTrue) and
          // same lattice -> same covolume^2 = det(Gram), invariant under ANY unimodular change of basis
          (math.abs(determinant2(result.reducedGram) - determinant2(gram)) must beLessThan(
            1e-6 * (1.0 + math.abs(determinant2(gram)))
          ))
      }
    }

    "undo a known unimodular skew of an already-orthogonal basis -- the Edelsbrunner scenario: two generators " +
      "of norm 2 and 3 are equally well represented by a 'skewed' pair generating the same rank-2 lattice, and " +
      "reduction should recover (up to sign/relabeling) the short orthogonal pair, not leave the correlated one" >> {
        val g0 = Array(Array(4.0, 0.0), Array(0.0, 9.0)) // orthogonal generators of length 2 and 3
        val s = Array(Array(1, 3), Array(0, 1)) // unimodular (det = 1)
        // g_skewed = S^T g0 S: the Gram matrix of the SAME lattice under the skewed generating set b0, b0*3+b1
        val skewed = matMulIntDouble(transpose(s), matMulDoubleInt(g0, s))
        skewed must beEqualTo(Array(Array(4.0, 12.0), Array(12.0, 45.0)))

        val result = LatticeReduction.reduce(skewed)
        // off-diagonal correlation must be gone (or as close to gone as an orthogonal lattice permits), and the
        // two norms present must be a permutation of the original {4, 9} -- diagonal-dominance alone isn't
        // enough to rule out a "still somewhat correlated" answer, so check the actual recovered Gram matrix.
        val diag = Set(result.reducedGram(0)(0).round, result.reducedGram(1)(1).round)
        (math.abs(result.reducedGram(0)(1)) must beLessThan(1e-6)) and
          (diag must beEqualTo(Set(4L, 9L)))
      }

    "handle 3 simultaneous generators correctly -- exactly the k>=3 shape where DREiMac's own _gram_schmidt " +
      "bug (invisible at k=2, see .claude/BUGS-IN-REFERENCES.md) produces a non-orthogonal intermediate result; " +
      "this codebase's own from-scratch implementation must not do the same" >> {
        val g0 = Array(Array(4.0, 0.0, 0.0), Array(0.0, 9.0, 0.0), Array(0.0, 0.0, 16.0))
        val s = Array(Array(1, 2, 3), Array(0, 1, 4), Array(0, 0, 1)) // unimodular upper-triangular, det = 1
        val skewed = matMulIntDouble(transpose(s), matMulDoubleInt(g0, s))

        val result = LatticeReduction.reduce(skewed)
        LatticeReduction.isReduced(result.reducedGram) must beTrue
        val det = determinant2(result.basisChange.map(_.map(_.toDouble)))
        math.abs(math.abs(det) - 1.0) must beLessThan(1e-6)
      }

    "reproduce the textbook worked example (Wikipedia's own LLL article): a basis for Z^3 given by columns " +
      "(1,1,1), (-1,0,2), (3,5,6) reduces to a basis spanning the same lattice, already reduced" >> {
        // Rows here (this codebase's own vector-per-row convention) = Wikipedia's own columns.
        val basisVectors = Array(Array(1.0, 1.0, 1.0), Array(-1.0, 0.0, 2.0), Array(3.0, 5.0, 6.0))
        def dot(a: Array[Double], b: Array[Double]) = a.indices.map(i => a(i) * b(i)).sum
        val gram = Array.tabulate(3, 3)((i, j) => dot(basisVectors(i), basisVectors(j)))

        val result = LatticeReduction.reduce(gram)
        LatticeReduction.isReduced(result.reducedGram) must beTrue
        val det = determinant2(result.basisChange.map(_.map(_.toDouble)))
        math.abs(math.abs(det) - 1.0) must beLessThan(1e-6)
        // Applying the SAME basisChange to the concrete original vectors must give a basis whose own Gram matrix
        // matches reducedGram exactly -- ties the abstract Gram-matrix-only algorithm back to real vectors.
        val reducedVectors = Array.tabulate(3) { c =>
          Array.tabulate(3)(t => (0 until 3).map(r => result.basisChange(r)(c).toDouble * basisVectors(r)(t)).sum)
        }
        val checkGram = Array.tabulate(3, 3)((i, j) => dot(reducedVectors(i), reducedVectors(j)))
        approxEqual(checkGram, result.reducedGram) must beTrue
      }

    "reject a non-square gram matrix" >> {
      LatticeReduction.reduce(Array(Array(1.0, 0.0))) must throwA[IllegalArgumentException]
    }

    "reject a gram matrix that isn't symmetric beyond floating-point noise" >> {
      LatticeReduction.reduce(Array(Array(1.0, 0.0), Array(1.0, 1.0))) must throwA[IllegalArgumentException]
    }

    "reject a positive-semidefinite (linearly dependent generators) gram matrix with an actionable message" >> {
      // two "generators" that are secretly the same vector twice over -> rank-deficient Gram matrix
      LatticeReduction.reduce(Array(Array(4.0, 4.0), Array(4.0, 4.0))) must throwA[IllegalArgumentException]
    }

    "reject delta outside (1/4, 1]" >> {
      val gram = Array(Array(4.0, 0.0), Array(0.0, 9.0))
      (LatticeReduction.reduce(gram, delta = 0.25) must throwA[IllegalArgumentException]) and
        (LatticeReduction.reduce(gram, delta = 1.1) must throwA[IllegalArgumentException])
    }
  }
