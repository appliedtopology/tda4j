package org.appliedtopology.tda4j
import org.specs2.{mutable, ScalaCheck}
import org.specs2.execute.AsResult

class RingModuleSpec extends mutable.Specification with ScalaCheck {
  """This is a test module for developing the RingModule[M,R] interface
    |and make sure that it does what we need it to do.
    |""".stripMargin

  object rm extends RingModule[(Int, Int), Int] {
    val zero: (Int, Int) = (0, 0)

    def plus(ls: (Int, Int), rs: (Int, Int)): (Int, Int) =
      (ls._1 + rs._1, ls._2 + rs._2)

    override def negate(x: (Int, Int)): (Int, Int) = (-x._1, -x._2)

    def scale(x: Int, y: (Int, Int)): (Int, Int) = (x * y._1, x * y._2)
  }

  given RingModule[(Int, Int), Int] = rm

  "zero should exist" >> {
    val v: (Int, Int) = rm.zero
    v must be_==(rm.zero)
  }

  "addition should work" >> {
    AsResult {
      prop { (x: Int, y: Int, z: Int, w: Int) =>
        (x, y) + (z, w) should be_==(x + z, y + w)
      }
    }
    "zero is a left zero" >> {
      AsResult {
        prop { (x: Int, y: Int) =>
          rm.zero + (x, y) should be_==(x, y)
        }
      }
    }
    "zero is a right zero" >> {
      AsResult {
        prop { (x: Int, y: Int) =>
          (x, y) + rm.zero should be_==(x, y)
        }
      }
    }
  }

  "scalar multiplication should work" >> {
    AsResult {
      prop { (x: Int, y: Int, r: Int) =>
        (x, y) <* r should be_==(x * r, y * r)

      }
    }
  }

  "scalar left-multiplication should work" >> {
    AsResult {
      prop { (x: Int, y: Int, r: Int) =>
        r *> (x, y) should be_==(x * r, y * r)
      }
    }
  }
}
