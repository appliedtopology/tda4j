package org.appliedtopology.tda4j

import org.scalacheck.Gen.listOfN
import org.scalacheck.{Arbitrary, Gen}
import org.specs2.{ScalaCheck, Specification}
import org.specs2.execute.{AsResult, Result}
import org.scalacheck.Prop.{classify, collect, forAll}
import org.specs2.scalacheck.Parameters

class CofacetsSpec extends org.specs2.mutable.Specification with ScalaCheck:
  "The Cofacets Iterator should" >> {
    // matrixGen is defined in VietorisRipsSpec.scala
    "create and compute" >> forAll(matrixGen[Double](Gen.double, Gen.const(5), Gen.chooseNum(25, 250))) {
      (points: Array[Array[Double]]) =>
        val sms = SparseMetricSpace(EuclideanMetricSpace(points), Gen.double.sample.getOrElse(50.0))
        val spxGen =
          for vtx <- Gen.atLeastOne(sms.elements)
          yield Simplex(vtx.toSeq*)
        val spx = spxGen.sample.getOrElse(Simplex(5, 10, 15, 20))

        val cofacets = CofacetIterator(spx, sms)
        val allCofacets = cofacets.toSeq
        allCofacets.size >= 0
    }
  }
