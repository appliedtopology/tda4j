package org.appliedtopology.tda4j
import org.specs2.mutable

class RingModuleSpec extends mutable.Specification {
  """This is a test module for developing the RingModule[M,R] interface
    |and make sure that it does what we need it to do.
    |""".stripMargin

  object rm extends RingModule[(Int,Int), Int] {
    val zero: (Int, Int) = (0, 0)

    def plus(ls: (Int, Int), rs: (Int, Int)): (Int, Int) = (ls._1 + rs._1, ls._2 + rs._2)

    override def negate(x: (Int, Int)): (Int, Int) = (-x._1, -x._2)

    def scale(x: Int, y: (Int, Int)): (Int, Int) = (x * y._1, x * y._2)
  }

  given RingModule[(Int,Int), Int] = rm

  "zero should exist" >> {
    val v : (Int,Int) = rm.zero
    v must be_==(rm.zero)
  }

  "addition should work" >> {
    ((2,3) + (4,5)) should be_== (6,8)
  }

  "scalar multiplication should work" >> {
    (2,3) <* 4 should be_== (8,12)
  }

  "scalar left-multiplication should work" >> {
    (4 *> (2,3)) should be_== (8,12)
  }
}
