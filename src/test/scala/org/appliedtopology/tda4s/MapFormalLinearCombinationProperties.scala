package org.appliedtopology.tda4s

import org.scalacheck.{Arbitrary, Properties, Gen}
import org.scalacheck.Prop.forAll

given Arbitrary[Simplex] = Arbitrary {
  for {
    integers <- Gen.nonEmptyContainerOf[Set, Int](Gen.choose(0, 100)) // Generate a non-empty set of integers
  } yield new Simplex(integers) // Assuming Simplex has a constructor taking a Set[Int]
}

given Arbitrary[Map[Simplex, Double]] = Arbitrary {
  for {
    size <- Gen.choose(0, 10) // Limit the size of the map
    keys <- Gen.listOfN(size, Arbitrary.arbitrary[Simplex]) // Generate a list of Simplexes
    values <- Gen.listOfN(size, Arbitrary.arbitrary[Double]) // Generate a corresponding list of Doubles
  } yield keys.zip(values).toMap // Combine them into a Map
}


import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers

class MapFormalLinearCombinationProperties extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers:
  import Field.given

  property("Additive identity") {
    forAll { (terms: Map[Simplex, Double]) =>
      val f = MapFormalLinearCombination(terms)
      val zero = MapFormalLinearCombination[Simplex, Double](Map.empty)

      f + zero == f && zero + f == f
    }
  }

  property("Additive associativity") {
    forAll {
      (terms1: Map[Simplex, Double], terms2: Map[Simplex, Double], terms3: Map[Simplex, Double]) =>
        val f1 = MapFormalLinearCombination(terms1)
        val f2 = MapFormalLinearCombination(terms2)
        val f3 = MapFormalLinearCombination(terms3)

        ((f1 add f2) add f3) == (f1 add (f2 add f3))
    }
  }

  property("Scaling identity") {
    forAll { (terms: Map[Simplex, Double]) =>
      val f = MapFormalLinearCombination(terms)
      ((f scale 2.0) scale 0.5) == f
    }
  }

  property("Distributivity") {
    forAll {
      (terms1: Map[Simplex, Double], terms2: Map[Simplex, Double], scalar: Double) =>
        val f1 = MapFormalLinearCombination(terms1)
        val f2 = MapFormalLinearCombination(terms2)

        ((f1 add f2) scale scalar) == ((f1 scale scalar) add (f2 scale scalar))
    }
  }
