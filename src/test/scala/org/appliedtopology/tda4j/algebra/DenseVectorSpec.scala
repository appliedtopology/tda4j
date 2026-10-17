package org.appliedtopology.tda4j

import org.specs2.mutable

/** `DenseVector` is a value: it ends up inside case classes (`Hyperplane`, `Hypersphere`) that are keys of hash sets,
  * so equality and `hashCode` must follow the entries, not the object.
  */
class DenseVectorSpec extends mutable.Specification:

  /** The shape in which the library holds vectors: a case class with a vector component, in a hash set. */
  private case class Sphere(center: DenseVector, radius: Double)

  "equality and hashing follow the entries" should {
    "equal vectors with equal entries, with equal hash codes" in {
      val a = DenseVector(1.0, 2.0, 3.0)
      val b = DenseVector.fromArray(Array(1.0, 2.0, 3.0))
      a must beEqualTo(b)
      a.hashCode must beEqualTo(b.hashCode)
    }
    "differ when one entry differs, or the length does" in {
      DenseVector(1.0, 2.0, 3.0) must not(beEqualTo(DenseVector(1.0, 2.0, 3.0000000000000004)))
      DenseVector(1.0, 2.0) must not(beEqualTo(DenseVector(1.0, 2.0, 0.0)))
      DenseVector(1.0, 2.0, 3.0) must not(beEqualTo(DenseVector(3.0, 2.0, 1.0)))
    }
    "treat 0.0 and -0.0 as the same entry, with the same hash code" in {
      DenseVector(0.0, 1.0) must beEqualTo(DenseVector(-0.0, 1.0))
      DenseVector(0.0, 1.0).hashCode must beEqualTo(DenseVector(-0.0, 1.0).hashCode)
    }
    "treat a vector with NaN entries as equal to itself and to an identical copy" in {
      val a = DenseVector(1.0, Double.NaN)
      a must beEqualTo(a)
      a must beEqualTo(DenseVector(1.0, Double.NaN))
      a.hashCode must beEqualTo(DenseVector(1.0, Double.NaN).hashCode)
    }
    "make a case class holding a vector a value too, so a Set of them deduplicates" in {
      val spheres = Set(
        Sphere(DenseVector(0.0, 1.0), 2.0),
        Sphere(DenseVector.fromArray(Array(0.0, 1.0)), 2.0),
        Sphere(DenseVector(0.0, 1.0), 2.5),
        Sphere(DenseVector(0.5, 1.0), 2.0)
      )
      spheres.size must beEqualTo(3)
      spheres must contain(Sphere(DenseVector(0.5, 1.0), 2.0))
    }
    "not equal anything that is not a vector" in {
      DenseVector(1.0).equals("1.0") must beFalse
      DenseVector(1.0).equals(null) must beFalse
    }
  }

  "a vector cannot be changed through the arrays that made it or that it hands out" should {
    "copy the array it is built from" in {
      val source = Array(1.0, 2.0)
      val v = DenseVector.fromArray(source)
      source(0) = 99.0
      v(0) must beEqualTo(1.0)
    }
    "hand out a copy of its entries" in {
      val v = DenseVector(1.0, 2.0)
      v.toArray(0) = 99.0
      v(0) must beEqualTo(1.0)
      v.toArray.toSeq must beEqualTo(Seq(1.0, 2.0))
    }
  }

  "arithmetic" should {
    val a = DenseVector(1.0, -2.0, 2.0)
    val b = DenseVector(4.0, 0.5, -1.0)
    "add, subtract, negate and scale entry by entry" in {
      (a + b) must beEqualTo(DenseVector(5.0, -1.5, 1.0))
      (a - b) must beEqualTo(DenseVector(-3.0, -2.5, 3.0))
      (-a) must beEqualTo(DenseVector(-1.0, 2.0, -2.0))
      (a * 2.0) must beEqualTo(DenseVector(2.0, -4.0, 4.0))
    }
    "take the dot product, the Euclidean norm and the Euclidean distance" in {
      (a.dot(b)) must beEqualTo(1.0 * 4.0 + -2.0 * 0.5 + 2.0 * -1.0)
      a.norm must beEqualTo(3.0)
      DenseVector(3.0, 4.0).norm must beEqualTo(5.0)
      DenseVector(1.0, 1.0).distance(DenseVector(4.0, 5.0)) must beEqualTo(5.0)
      a.distance(a) must beEqualTo(0.0)
    }
    "report its length and entries" in {
      a.size must beEqualTo(3)
      a(1) must beEqualTo(-2.0)
      DenseVector.zeros(4) must beEqualTo(DenseVector(0.0, 0.0, 0.0, 0.0))
      DenseVector.tabulate(3)(i => i * 2.0) must beEqualTo(DenseVector(0.0, 2.0, 4.0))
    }
    "refuse vectors of different lengths, and entries out of range" in {
      (a + DenseVector(1.0)) must throwAn[IllegalArgumentException]
      a.dot(DenseVector(1.0)) must throwAn[IllegalArgumentException]
      a.distance(DenseVector(1.0)) must throwAn[IllegalArgumentException]
      a(3) must throwAn[IndexOutOfBoundsException]
      a(-1) must throwAn[IndexOutOfBoundsException]
    }
    "have an empty vector with norm 0" in {
      DenseVector().size must beEqualTo(0)
      DenseVector().norm must beEqualTo(0.0)
    }
  }
