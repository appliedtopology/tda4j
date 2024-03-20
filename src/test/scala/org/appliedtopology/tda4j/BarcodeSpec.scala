package org.appliedtopology.tda4j

import org.apache.commons.math3.linear.*
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.collection.immutable.Seq

import org.appliedtopology.tda4j.given
import org.appliedtopology.tda4j.barcode.{*,given}

class BarcodeAlgebraSpec extends Specification with ScalaCheck {
  "Comparing endpoints" >> {
    "Negative Infinity is less than everything else" >> {
      prop { (d:Double) =>
        NegativeInfinity[Double]() must beLessThanOrEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        NegativeInfinity[Double]() must beLessThanOrEqualTo(ClosedEndpoint(d))
      }
      NegativeInfinity[Double]() must beLessThanOrEqualTo(PositiveInfinity[Double]())
    }

    "Positive Infinity is greater than everything else" >> {
      prop { (d: Double) =>
        PositiveInfinity[Double]() must beGreaterThanOrEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        PositiveInfinity[Double]() must beGreaterThanOrEqualTo(ClosedEndpoint(d))
      }
      NegativeInfinity[Double]() must beLessThanOrEqualTo(PositiveInfinity[Double]())
    }

    "Strictly smaller is smaller than strictly greater" >> {
      prop { (d:Double,e:Double) =>
        (d > e) ==>
          (ClosedEndpoint(d) must beGreaterThanOrEqualTo(ClosedEndpoint(e)))
      }
      prop { (d: Double, e: Double) =>
        (d > e) ==>
          (OpenEndpoint(d) must beGreaterThanOrEqualTo(ClosedEndpoint(e)))
      }
      prop { (d:Double,e:Double) =>
        (d > e) ==>
          (ClosedEndpoint(d) must beGreaterThanOrEqualTo(OpenEndpoint(e)))
      }
      prop { (d:Double,e:Double) =>
        (d > e) ==>
          (OpenEndpoint(d) must beGreaterThanOrEqualTo(OpenEndpoint(e)))
      }
    }

    "Open is not equal to Closed" >> {
      prop { (d: Double) =>
          ClosedEndpoint(d) must beEqualTo(ClosedEndpoint(d))
      }
      prop { (d: Double) =>
        OpenEndpoint(d) must beEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        ClosedEndpoint(d) must beLessThanOrEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        OpenEndpoint(d) must beGreaterThanOrEqualTo(ClosedEndpoint(d))
      }
    }
  }
  "valid and non-valid maps of barcodes" >> {
    given bc: BarcodeContext[Int]()
    import bc.*

    val barcode = Barcode[Int,Nothing]()

    "One interval maps into another interval" ==> (
      barcode.isMap(List(dim(0)(0 bc 5)), List(dim(0)(0 bc 5)), 
        MatrixUtils.createRealMatrix(Array(Array(1.0)))) must beTrue
      )

    "Target must exist at source birth" ==> ({
      val isThisAMap = barcode.isMap(List(dim(0)(0 bc 5)), List(dim(0)(1 bc 5)),
        MatrixUtils.createRealMatrix(Array(Array(1.0))))
      isThisAMap must beFalse
    })

    "Target must exist at source death" ==> (
      barcode.isMap(List(dim(0)(0 bc 5)), List(dim(0)(0 bc 6)),
        MatrixUtils.createRealMatrix(Array(Array(1.0)))) must beFalse
      )

    "Properly contained target won't work" ==> (
      barcode.isMap(List(dim(0)(0 bc 5)), List(dim(0)(1 bc 4)),
        MatrixUtils.createRealMatrix(Array(Array(1.0)))) must beFalse
      )

    "Target must overlap source" ==> (
      barcode.isMap(List(dim(0)(0 bc 5)), List(dim(0)(6 bc 10)),
        MatrixUtils.createRealMatrix(Array(Array(1.0)))) must beFalse
      )

    "Target must overlap source" ==> (
      barcode.isMap(List(dim(0)(10 bc 15)), List(dim(0)(6 bc 9)),
        MatrixUtils.createRealMatrix(Array(Array(1.0)))) must beFalse
      )


    val eye5 = MatrixUtils.createRealIdentityMatrix(5)
    "Target fully below source" ==> (
    barcode.isMap(
      List(dim(0)(10 bc 15)),
      List(dim(0)(0 bc 5), dim(0)(5 bc 12), dim(0)(11 bc 14), dim(0)(12 bc 16), dim(0)(16 bc 20)),
      eye5.getColumnMatrix(0)
    ) must beFalse)

    "Target intersects source from below" ==> (
      barcode.isMap(
        List(dim(0)(10 bc 15)),
        List(dim(0)(0 bc 5), dim(0)(5 bc 12), dim(0)(11 bc 14), dim(0)(12 bc 16), dim(0)(16 bc 20)),
        eye5.getColumnMatrix(1)
      ) must beTrue)

    "Target fully includes source" ==> (
      barcode.isMap(
        List(dim(0)(10 bc 15)),
        List(dim(0)(0 bc 5), dim(0)(5 bc 12), dim(0)(11 bc 14), dim(0)(12 bc 16), dim(0)(16 bc 20)),
        eye5.getColumnMatrix(2)
      ) must beFalse)

    "Target intersects source from above" ==> (
      barcode.isMap(
        List(dim(0)(10 bc 15)),
        List(dim(0)(0 bc 5), dim(0)(5 bc 12), dim(0)(11 bc 14), dim(0)(12 bc 16), dim(0)(16 bc 20)),
        eye5.getColumnMatrix(3)
      ) must beFalse)

    "Target fully above source" ==> (
      barcode.isMap(
        List(dim(0)(10 bc 15)),
        List(dim(0)(0 bc 5), dim(0)(5 bc 12), dim(0)(11 bc 14), dim(0)(12 bc 16), dim(0)(16 bc 20)),
        eye5.getColumnMatrix(4)
      ) must beFalse)
  }
  "Cokernel, kernel, image computation" >> {
    given bc: BarcodeContext[Double]()
    import bc.*
    
    val source = List(dim(0)(2 bc 5), dim(0)(3 bc 7))
    val target = List(dim(0)(1 bc 6), dim(0)(0 bc 4))
    val matrix = MatrixUtils.createRealMatrix(Array(Array(0.0, 1.0), Array(1.0, 1.0)))
    
    "cokernel" ==> (Barcode[Double,Nothing]().cokernel(source,target,matrix) must containAllOf(List(
      dim(0)(0 bc 2), dim(0)(1 bc 3)
    )))
    
    "kernel" ==> (Barcode[Double,Nothing]().kernel(source,target,matrix) must containAllOf(List(
      dim(0)(6 bc 7), dim(0)(4 bc 5)
    )))

    "image" ==> (Barcode[Double,Nothing]().image(source,target,matrix) must containAllOf(List(
      dim(0)(3 bc 6), dim(0)(2 bc 4)
    )))
  }
}

class BarcodeSpec extends Specification {
  "0-persistence output" >> {
    given sc: SimplexContext[Int]()
    import sc.*
    val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(
      Seq(Seq(0.0, 1.0, 2.0), Seq(1.0, 0.0, 3.0), Seq(2.0, 3.0, 0.0))
    )
    val rs = RipserStream(ms, ms.minimumEnclosingRadius, 5)
  }
}
