package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck

import scala.util.Random
import scala.math.Fractional.Implicits.*

import org.scalacheck.*

//noinspection ScalaRedundantConversion
object FiniteFieldSpec extends mutable.Specification with ScalaCheck {
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
        j <- Range(1, 17)
      } yield Fp(j) / Fp(j)
      calc must contain(be_==(Fp(1))).forall
    }

    "all signed operations stay within -p/2, p/2" >> {
      "*" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) * Fp(y)).toInt)
          }
        }
      }
      "+" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) + Fp(y)).toInt)
          }
        }
      }
      "-" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) - Fp(y)).toInt)
          }
        }
      }
      "/" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (y != 0) ==>
              (-8 to 8).contains((Fp(x) * Fp(y)).toInt)
          }
        }
      }
    }

    "all unsigned operations stay within 0, p-1" >> {
      "*" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) * Fp(y)).toUInt)
          }
        }
      }
      "+" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) + Fp(y)).toUInt)
          }
        }
      }
      "-" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) - Fp(y)).toUInt)
          }
        }
      }
      "/" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            (y != 0) ==>
              (0 to 16).contains((Fp(x) * Fp(y)).toUInt)
          }
        }
      }
    }

    "commutativity" >> {
      "*" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            Fp(x) * Fp(y) === Fp(y) * Fp(x)
          }
        }
      }
      "+" >> {
        AsResult {
          prop { (x: Int, y: Int) =>
            Fp(x) + Fp(y) === Fp(y) + Fp(x)
          }
        }
      }
    }
    "associativity" >> {
      "*" >> {
        AsResult {
          prop { (x: Int, y: Int, z: Int) =>
            (Fp(x) * Fp(y)) * Fp(z) === Fp(y) * (Fp(x) * Fp(z))
          }
        }
      }
      "+" >> {
        AsResult {
          prop { (x: Int, y: Int, z: Int) =>
            (Fp(x) + Fp(y)) + Fp(z) === Fp(y) + (Fp(x) + Fp(z))
          }
        }
      }
    }
    "distributivity" >> {
      AsResult {
        prop { (x: Int, y: Int, z: Int) =>
          Fp(x) * (Fp(y) + Fp(z)) === Fp(x) * Fp(y) + Fp(x) * Fp(z)
        }
      }
    }
    "units" >> {
      "x-x" >> {
        AsResult {
          prop { (x: Int) =>
            x - x === Fp(0)
          }
        }
      }
      "x + (-x)" >> {
        AsResult {
          prop { (x: Int) =>
            x + (-x) === Fp(0)
          }
        }
      }
      "x * (1/x)" >> {
        AsResult {
          prop { (y: Int) =>
            (y % 17 != 0) ==>
              (Fp(y) * (Fp(1) / Fp(y)) === Fp(1))
          }
        }
      }
    }
  }

  "Testing unapply and pattern matching for value declarations" >> {
    val x: Fp = Fp(42)
    val Fp(y) = x
    eg(y == (42 % 17))
  }
}
