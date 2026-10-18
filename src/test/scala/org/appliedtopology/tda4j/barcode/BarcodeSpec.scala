package org.appliedtopology.tda4j

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.collection.immutable.Seq

import org.appliedtopology.tda4j.given

class BarcodeAlgebraSpec extends Specification with ScalaCheck:
  "Comparing endpoints" >> {
    "Negative Infinity is less than everything else" >> {
      prop { (d: Double) =>
        NegativeInfinity[Double]() must beLessThanOrEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        NegativeInfinity[Double]() must beLessThanOrEqualTo(ClosedEndpoint(d))
      }
      NegativeInfinity[Double]() must beLessThanOrEqualTo(
        PositiveInfinity[Double]()
      )
    }

    "Positive Infinity is greater than everything else" >> {
      prop { (d: Double) =>
        PositiveInfinity[Double]() must beGreaterThanOrEqualTo(OpenEndpoint(d))
      }
      prop { (d: Double) =>
        PositiveInfinity[Double]() must beGreaterThanOrEqualTo(
          ClosedEndpoint(d)
        )
      }
      NegativeInfinity[Double]() must beLessThanOrEqualTo(
        PositiveInfinity[Double]()
      )
    }

    "Strictly smaller is smaller than strictly greater" >> {
      prop { (d: Double, e: Double) =>
        (d > e) ==>
          (ClosedEndpoint(d) must beGreaterThanOrEqualTo(ClosedEndpoint(e)))
      }
      prop { (d: Double, e: Double) =>
        (d > e) ==>
          (OpenEndpoint(d) must beGreaterThanOrEqualTo(ClosedEndpoint(e)))
      }
      prop { (d: Double, e: Double) =>
        (d > e) ==>
          (ClosedEndpoint(d) must beGreaterThanOrEqualTo(OpenEndpoint(e)))
      }
      prop { (d: Double, e: Double) =>
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
    given bc: BarcodeBuilder[Int]()
    import bc.*

    "One interval maps into another interval" ==> (
      Barcode.isMap(
        List(dim(0)(0.bc(5))),
        List(dim(0)(0.bc(5))),
        DenseMatrix(Array(Array(1.0)))
      ) must beTrue
    )

    "Target must exist at source birth" ==> {
      val isThisAMap = Barcode.isMap(
        List(dim(0)(0.bc(5))),
        List(dim(0)(1.bc(5))),
        DenseMatrix(Array(Array(1.0)))
      )
      isThisAMap must beFalse
    }

    "Target must exist at source death" ==> (
      Barcode.isMap(
        List(dim(0)(0.bc(5))),
        List(dim(0)(0.bc(6))),
        DenseMatrix(Array(Array(1.0)))
      ) must beFalse
    )

    "Properly contained target won't work" ==> (
      Barcode.isMap(
        List(dim(0)(0.bc(5))),
        List(dim(0)(1.bc(4))),
        DenseMatrix(Array(Array(1.0)))
      ) must beFalse
    )

    "Target must overlap source" ==> (
      Barcode.isMap(
        List(dim(0)(0.bc(5))),
        List(dim(0)(6.bc(10))),
        DenseMatrix(Array(Array(1.0)))
      ) must beFalse
    )

    "Target must overlap source" ==> (
      Barcode.isMap(
        List(dim(0)(10.bc(15))),
        List(dim(0)(6.bc(9))),
        DenseMatrix(Array(Array(1.0)))
      ) must beFalse
    )

    val eye5 = DenseMatrix.identity(5)
    // The j-th column of m as a matrix of one column.
    def column(m: DenseMatrix, j: Int): DenseMatrix = DenseMatrix.tabulate(m.rows, 1)((i, _) => m(i, j))
    "Target fully below source" ==> (Barcode.isMap(
      List(dim(0)(10.bc(15))),
      List(
        dim(0)(0.bc(5)),
        dim(0)(5.bc(12)),
        dim(0)(11.bc(14)),
        dim(0)(12.bc(16)),
        dim(0)(16.bc(20))
      ),
      column(eye5, 0)
    ) must beFalse)

    "Target intersects source from below" ==> (Barcode.isMap(
      List(dim(0)(10.bc(15))),
      List(
        dim(0)(0.bc(5)),
        dim(0)(5.bc(12)),
        dim(0)(11.bc(14)),
        dim(0)(12.bc(16)),
        dim(0)(16.bc(20))
      ),
      column(eye5, 1)
    ) must beTrue)

    "Target fully includes source" ==> (Barcode.isMap(
      List(dim(0)(10.bc(15))),
      List(
        dim(0)(0.bc(5)),
        dim(0)(5.bc(12)),
        dim(0)(11.bc(14)),
        dim(0)(12.bc(16)),
        dim(0)(16.bc(20))
      ),
      column(eye5, 2)
    ) must beFalse)

    "Target intersects source from above" ==> (Barcode.isMap(
      List(dim(0)(10.bc(15))),
      List(
        dim(0)(0.bc(5)),
        dim(0)(5.bc(12)),
        dim(0)(11.bc(14)),
        dim(0)(12.bc(16)),
        dim(0)(16.bc(20))
      ),
      column(eye5, 3)
    ) must beFalse)

    "Target fully above source" ==> (Barcode.isMap(
      List(dim(0)(10.bc(15))),
      List(
        dim(0)(0.bc(5)),
        dim(0)(5.bc(12)),
        dim(0)(11.bc(14)),
        dim(0)(12.bc(16)),
        dim(0)(16.bc(20))
      ),
      column(eye5, 4)
    ) must beFalse)
  }
  "Cokernel, kernel, image computation" >> {
    given bc: BarcodeBuilder[Double]()
    import bc.*

    val source = List(dim(0)(2.bc(5)), dim(0)(3.bc(7)))
    val target = List(dim(0)(1.bc(6)), dim(0)(0.bc(4)))
    val matrix =
      DenseMatrix(Array(Array(0.0, 1.0), Array(1.0, 1.0)))

    "cokernel" ==> (Barcode.cokernel(
      source,
      target,
      matrix
    ) must containAllOf(
      List(
        dim(0)(0.bc(2)),
        dim(0)(1.bc(3))
      )
    ))

    "kernel" ==> (Barcode.kernel(
      source,
      target,
      matrix
    ) must containAllOf(
      List(
        dim(0)(6.bc(7)),
        dim(0)(4.bc(5))
      )
    ))

    "image" ==> (Barcode.image(
      source,
      target,
      matrix
    ) must containAllOf(
      List(
        dim(0)(3.bc(6)),
        dim(0)(2.bc(4))
      )
    ))
  }
  "Reducing a matrix by the lowest nonzero entries of its columns" >> {
    val matrix = DenseMatrix(Array(Array(1.0, 1.0), Array(1.0, 2.0)))

    "subtracts the left column times the ratio of the pivot row's entries, and leaves its argument alone" ==> {
      Barcode.reduceMatrix(matrix) must beEqualTo(DenseMatrix(Array(Array(1.0, -1.0), Array(1.0, 0.0))))
      matrix must beEqualTo(DenseMatrix(Array(Array(1.0, 1.0), Array(1.0, 2.0))))
    }

    "reads each column's pivot after the columns to its left have been subtracted from it" ==> {
      // Column 1 has 4 where column 0 has 2 in the lowest row: minus twice column 0 it is (1, 0), pivot row 0.
      val m = DenseMatrix(Array(Array(1.0, 3.0, 1.0), Array(2.0, 4.0, 2.0)))
      val reduced = Barcode.reduceMatrix(m)
      reduced must beEqualTo(DenseMatrix(Array(Array(1.0, 1.0, 0.0), Array(2.0, 0.0, 0.0))))
      Barcode.pivotsOf(reduced) must beEqualTo(Seq((1, 0), (0, 1)))
    }

    "has pivots at the lowest nonzero entry of each nonzero column" ==> {
      val m = DenseMatrix(Array(Array(0.0, 5.0, 0.0), Array(0.0, 0.0, 0.0), Array(7.0, 1.0, 0.0)))
      Barcode.pivotsOf(m) must beEqualTo(Seq((2, 0), (2, 1)))
    }
  }
