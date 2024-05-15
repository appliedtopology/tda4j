package org.appliedtopology.tda4j

import org.specs2.mutable

class DeferredSpec extends mutable.Specification {
  val fr = FractionalExpr
  val fractional : Fractional[FractionalExpr] = summon[Fractional[FractionalExpr]]
  import fractional.*
  import org.bitbucket.inkytonik.kiama.*
  import org.bitbucket.inkytonik.kiama.rewriting.*
  import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.*
  import org.bitbucket.inkytonik.kiama.util.Trampolines.*

  "For deferred evaluation" >> {
    "Create expressions" >> {
      val expr: FractionalExpr =
        fr.One + fr.One - fr.One - fr.One + fr.Zero/fr.One - fr.One/fr.One + (fr.One+fr.One)/(fr.One+fr.One)
      doubleHandler.eval(expr) must be_==(0.0)

      // ruleset from https://apps.dtic.mil/sti/pdfs/ADA087641.pdf ch. 8 Rings
      val rs : List[Strategy] = List(
        rule { case fr.Minus(x,y) => fr.Plus(x, fr.Negate(y)) },
        rule { case fr.Div(x,y) => fr.Times(x, fr.Inv(y)) },
        // ch 7. Abelian Groups, precompiled set
        rule { case fr.Plus(x, fr.Zero) => x },
        rule { case fr.Plus(x, fr.Negate(y)) if(x == y)  => fr.Zero },
        rule { case fr.Negate(fr.Zero) => fr.Zero },
        rule { case fr.Negate(fr.Negate(x)) => x },
        rule { case fr.Negate(fr.Plus(x, y)) => fr.Plus(fr.Negate(x), fr.Negate(y)) },
        // ch 8. Rings - associative + commutative
        rule { case fr.Times(x, fr.Plus(y,z)) => fr.Plus(fr.Times(x,y),fr.Times(x,z)) },
        rule { case fr.Times(fr.Plus(x,y), z) => fr.Plus(fr.Times(x,z),fr.Times(y,z)) },
        rule { case fr.Times(x, fr.Zero) => fr.Zero },
        rule { case fr.Times(x, fr.Negate(y)) => fr.Negate(fr.Times(x,y)) },
        rule { case fr.Times(fr.Zero, x) => fr.Zero },
        rule { case fr.Times(fr.Negate(x), y) => fr.Negate(fr.Times(x,y)) },
        // add handling of inverses
        rule { case fr.Inv(fr.Inv(x)) => x },
        rule { case fr.Inv(fr.Times(x,y)) => fr.Times(fr.Inv(y), fr.Inv(x)) },
        rule { case fr.Inv(fr.One) => fr.One },
        // add associativity with collections?
        //// construct sums
        rule { case fr.Plus(fr.Plus(x,y),z) => fr.Sum(List(x,y,z)) },
        rule { case fr.Plus(x,fr.Plus(y,z)) => fr.Sum(List(x,y,z)) },
        //// extend sums
        rule { case fr.Plus(fr.Sum(xs), y) => fr.Sum(y :: xs) },
        rule { case fr.Plus(x, fr.Sum(ys)) => fr.Sum(x :: ys) },
        rule { case fr.Sum(fr.Plus(x,y) :: rest) => fr.Sum(x :: y :: rest) },
        rule { case fr.Sum(fr.Sum(xs) :: rest) => fr.Sum(xs ++ rest) },
        //// construct products
        rule { case fr.Times(fr.Times(x,y),z) => fr.Prod(List(x,y,z)) },
        rule { case fr.Times(x,fr.Times(y,z)) => fr.Prod(List(x,y,z)) },
        //// extend products
        rule { case fr.Times(fr.Prod(xs), y) => fr.Prod(y :: xs) },
        rule { case fr.Times(x, fr.Prod(ys)) => fr.Prod(x :: ys) },
        rule { case fr.Prod(fr.Times(x,y) :: rest) => fr.Prod(x :: y :: rest) },
        rule { case fr.Prod(fr.Prod(xs) :: rest) => fr.Prod(xs ++ rest) },
      )

      val rsRules = rs.foldRight(id)(_ <+ _)

      // ruleset from Degano and Sirovich,
      // described in https://apps.dtic.mil/sti/pdfs/ADA087641.pdf ch. 18 Arithmetic Theories
      val ds : List[Strategy] = List(
        rule { case fr.One => fr.Succ(fr.Zero) },
        rule { case fr.Plus(x, fr.One) => fr.Succ(x) },
        rule { case fr.Plus(fr.Zero, x) => x },
        rule { case fr.Times(fr.Zero, x) => fr.Zero },
        rule { case fr.Plus(fr.Succ(x), y) => fr.Succ(fr.Plus(x,y)) },
        rule { case fr.Times(fr.Succ(x), y) => fr.Plus(fr.Times(x,y), y) },
        rule { case fr.Times(x, fr.Plus(y,z)) => fr.Plus(fr.Times(x,y), fr.Times(x,z)) },
      )
    }
  }
}
