package org.appliedtopology.tda4j

import org.scalacheck.Gen.listOfN
import org.scalacheck.{Arbitrary, Gen}
import org.specs2.{ScalaCheck, Specification}
import org.specs2.execute.{AsResult, Result}
import org.scalacheck.Prop.forAll

class AlphaComplexSpec extends org.specs2.mutable.Specification with ScalaCheck {
  "Alpha complex should" >> {
    // matrixGen is defined in VietorisRipsSpec.scala
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 250))) { (points: Array[Array[Double]]) =>
      val alpha = AlphaShapes(points)
      val ref0 : Seq[Simplex[Int]] = alpha.metricSpace.elements.toSeq.map(Simplex(_))
      val it0 : Seq[Simplex[Int]] = alpha.iterateDimension(0).toSeq
      
      val ref1 : Iterator[Simplex[Int]] = alpha.iterateDimension(1)
      val ref2 : Iterator[Simplex[Int]] = alpha.iterateDimension(2)
      
      ref1.tapEach(_ => ())
      ref2.tapEach(_ => ())
      
      it0 must containTheSameElementsAs(ref0)
    }
  }
}

