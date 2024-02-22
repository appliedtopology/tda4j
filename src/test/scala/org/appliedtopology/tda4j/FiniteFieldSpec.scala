package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import scala.util.Random
import scala.math.Fractional.Implicits._

//noinspection ScalaRedundantConversion
class FiniteFieldSpec extends mutable.Specification {
  """This is the specification for unit testing of our
    |implementation of arithmetic mod p as a Fractional[Int]
    |instance.
    |""".stripMargin

  val IntMod17 = new FiniteField(17)
  import IntMod17.Fp
  import IntMod17.given

  import IntMod17.FpIsFractional.mkNumericOps
  import IntMod17.FpIsFractional.mkOrderingOps

  "Numbers mod p should" >> {
    "all have an inverse" >> {
      val calc = for {
        j <- Range(1,17)
      } yield Fp(j)/Fp(j)
      calc must contain(be_==(Fp(1))).forall
    }

    val rand = Random
    val x = Fp(rand.between(-100, 100))
    var y = Fp(rand.between(-100, 100))
    val z = Fp(rand.between(-100, 100))
    if y == Fp(0) then y = Fp(rand.between(1, 100))
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
      eg { x - x === Fp(0) }
      eg { x + (-x) === Fp(0) }
      eg { y * (Fp(1)/y) === Fp(1) }
    }
  }
}
