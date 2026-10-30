package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck

import scala.util.Random
import scala.math.Fractional.Implicits.*

import org.scalacheck.*

//noinspection ScalaRedundantConversion
object FiniteFieldSpec extends mutable.Specification with ScalaCheck:
  """This is the specification for unit testing of our
    |implementation of arithmetic mod p as a Fractional[Int]
    |instance.
    |""".stripMargin

  val IntMod17 = new FiniteField(17)
  import IntMod17.Fp
  import IntMod17.given

  "Numbers mod p should" >> {
    "all have an inverse" >> {
      val calc =
        for j <- Range(1, 17)
        yield Fp(j) / Fp(j)
      calc must contain(be_==(Fp(1))).forall
    }

    "all signed operations stay within -p/2, p/2" >> {
      "*" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) * Fp(y)).toInt)
          }
        }
      "+" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) + Fp(y)).toInt)
          }
        }
      "-" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (-8 to 8).contains((Fp(x) - Fp(y)).toInt)
          }
        }
      "/" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (y != 0) ==>
              (-8 to 8).contains((Fp(x) * Fp(y)).toInt)
          }
        }
    }

    "all unsigned operations stay within 0, p-1" >> {
      "*" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) * Fp(y)).toUInt)
          }
        }
      "+" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) + Fp(y)).toUInt)
          }
        }
      "-" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (0 to 16).contains((Fp(x) - Fp(y)).toUInt)
          }
        }
      "/" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            (y != 0) ==>
              (0 to 16).contains((Fp(x) * Fp(y)).toUInt)
          }
        }
    }

    "commutativity" >> {
      "*" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            Fp(x) * Fp(y) === Fp(y) * Fp(x)
          }
        }
      "+" >>
        AsResult {
          prop { (x: Int, y: Int) =>
            Fp(x) + Fp(y) === Fp(y) + Fp(x)
          }
        }
    }
    "associativity" >> {
      "*" >>
        AsResult {
          prop { (x: Int, y: Int, z: Int) =>
            (Fp(x) * Fp(y)) * Fp(z) === Fp(y) * (Fp(x) * Fp(z))
          }
        }
      "+" >>
        AsResult {
          prop { (x: Int, y: Int, z: Int) =>
            (Fp(x) + Fp(y)) + Fp(z) === Fp(y) + (Fp(x) + Fp(z))
          }
        }
    }
    "distributivity" >>
      AsResult {
        prop { (x: Int, y: Int, z: Int) =>
          Fp(x) * (Fp(y) + Fp(z)) === Fp(x) * Fp(y) + Fp(x) * Fp(z)
        }
      }
    "units" >> {
      "x-x" >>
        AsResult {
          prop { (x: Int) =>
            // Through the field's equality: an `Fp` has several `Int` forms.
            (Fp(x) - Fp(x)) eql Fp(0)
          }
        }
      "x + (-x)" >>
        AsResult {
          prop { (x: Int) =>
            (Fp(x) + (-Fp(x))) eql Fp(0)
          }
        }
      "x * (1/x)" >>
        AsResult {
          prop { (y: Int) =>
            (y % 17 != 0) ==>
              (Fp(y) * (Fp(1) / Fp(y)) === Fp(1))
          }
        }
    }
  }

  "The hash of an element agrees with the field's equality" >> {
    val field = summon[Fp is Field]
    "it is the residue in 0 until p, for any Int" >>
      AsResult {
        prop((x: Int) => field.hash(Fp(x)) === Math.floorMod(x, 17))
      }
    "the two Int forms of a residue hash alike, and the residues hash apart" >> {
      ((0 until 17).forall(r => field.isEqual(Fp(r), Fp(r - 17)) && field.hash(Fp(r)) == field.hash(Fp(r - 17))) must
        beTrue) and ((0 until 17).map(r => field.hash(Fp(r))).distinct.size must beEqualTo(17))
    }
  }

  "FiniteField(p) refuses a p that is not a prime, saying so" >>
    Seq(-3, 0, 1, 4, 9, 15)
      .map(p => (new FiniteField(p): Any) must SpecThrows.throwWith[IllegalArgumentException]("needs a prime number"))
      .reduce(_ and _)

  "Testing unapply and pattern matching for value declarations" >> {
    val x: Fp = Fp(42)
    val Fp(y) = x
    eg(y == (42 % 17))
  }
