package org.appliedtopology.tda4s

import org.scalacheck.Properties
import org.scalacheck.Prop.{forAll, propBoolean}

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.matchers.should.Matchers

class FieldPropertiesValidation extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers:
  import Field.given

  property("additive identity") {
    forAll { (x: Double) =>
      val field = summon[Double is Field]
      field.add(x, field.zero) == x
    }
  }
  
  property("additive commutativity") {
    forAll { (x: Double, y: Double) =>
      val field = summon[Double is Field]
      field.add(x, y) == field.add(y, x)
    }
  }

  property("multiplicative identity") {
    forAll { (x: Double) =>
      val field = summon[Double is Field]
      field.mul(x, field.one) == x
    }
  }

  property("multiplicative commutativity") {
    forAll { (x: Double, y: Double) =>
      val field = summon[Double is Field]
      field.mul(x, y) == field.mul(y, x)
    }
  }

  property("distributivity") {
    forAll { (x: Double, y: Double, z: Double) =>
      val field = summon[Double is Field]
      field.mul(x, field.add(y, z)) == field.add(field.mul(x, y), field.mul(x, z))
    }
  }

  property("division reversal") {
    forAll { (x: Double, y: Double) =>
      val field = summon[Double is Field]
      (y != 0) ==> {
        field.mul(field.div(x, y), y) == x
      }
    }
  }