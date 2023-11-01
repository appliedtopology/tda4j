package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import scala.util.Random
import scala.math.Fractional.Implicits._

//noinspection ScalaRedundantConversion
class IntModpSpec extends mutable.Specification {
  """This is the specification for unit testing of our
    |implementation of arithmetic mod p as a Fractional[Int]
    |instance.
    |""".stripMargin

  val IntMod17 = new IntModp(17)
  import IntMod17.Intp
  import IntMod17.given
  import IntMod17.IntpIsFractional.mkNumericOps
  import IntMod17.IntpIsFractional.mkOrderingOps

  "Numbers mod p should" >> {
    "all have an inverse" >> {
      Range(1, 16).map(Intp.apply).map(j => j / j) must contain(be_==(Intp(1))).forall
    }
    val rand = Random
    val x = Intp(rand.between(-100, 100))
    var y = Intp(rand.between(-100, 100))
    val z = Intp(rand.between(-100, 100))
    if y == Intp(0) then y = Intp(rand.between(1, 100))
    "all operations stay within -p/2, p/2" >> {
      //noinspection ScalaRedundantConversion
      eg((x * y).toInt must beBetween(-8, 8))
      eg((x + y).toInt must beBetween(-8, 8))
      eg((x - y).toInt must beBetween(-8, 8))
      eg((x / y).toInt must beBetween(-8, 8))
      eg((-z).toInt must beBetween(-8, 8))
    }
    "commutativity" >> {
      eg(x * y === y * x)
      eg(x + y === y + x)
      eg(x - y === -(y - x))
    }
    "associativity" >> {
      eg { (x * y) * z === x * (y * z) }
      eg { (x + y) + z === x + (y + z) }
    }
    "distributivity" >> {
      eg { x * (y + z) === x * y + x * z }
    }
    "units" >> {
      eg { x - x === Intp(0) }
      eg { x + (-x) === Intp(0) }
      eg { x * (Intp(1)/x) === Intp(1) }
    }
  }
}
