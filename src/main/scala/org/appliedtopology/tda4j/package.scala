package org.appliedtopology.tda4j

import cats.Show
import org.appliedtopology.tda4j.algebra.{*, given}
import org.appliedtopology.tda4j.cells.{*, given}
import org.appliedtopology.tda4j.streams.{*, given}
import org.appliedtopology.tda4j.homology.{*, given}
import org.appliedtopology.tda4j.alpha.{*, given}

/** Pylab-style single entry point: `val tdalab = TDAlab(characteristic); import tdalab.{*, given}` brings coefficient
  * arithmetic (`Fp`, `⊠`, `+`, `-`), `∆`/`Simplex`/`Cube` literals, an implicit `Simplex -> Chain` widening and `Show`
  * syntax into scope. `characteristic = 0` selects `Double` coefficients (compared within `precision`); a prime `p`
  * selects `Z/p`. Convenience for interactive/notebook-style use only -- never consulted by an engine (see CLAUDE.md's
  * generic-`given`-capture note).
  *
  * Scala forbids `import tdalab(p = 3).{*, given}`, hence the `val` first.
  */
class TDAlab(characteristic: Int, precision: Double = 1e-9):

  import org.appliedtopology.tda4j.io
  import org.appliedtopology.tda4j.barcode
  import org.appliedtopology.tda4j.homology
  import org.appliedtopology.tda4j.streams
  import org.appliedtopology.tda4j.alpha
  import org.appliedtopology.tda4j.algebra.{*, given}
  import org.appliedtopology.tda4j.cells.{*, given}

  import cats.syntax.all.*

  trait FieldData:
    type CoefficientT

    given CoefficientT is Field = compiletime.deferred

    def coeff(x: Int): CoefficientT

    def Fp(x: Int): CoefficientT = coeff(x)

  object FieldData:
    def apply(): FieldData = characteristic match
      case 0 =>
        new FieldData:
          override type CoefficientT = Double

          override given CoefficientT is Field = Field.DoubleApproximated(precision)

          override def coeff(x: Int): CoefficientT = x.toDouble
      case p if BigInt(characteristic).isProbablePrime(certainty = 100) =>
        val ff = FiniteField(p)
        import ff.given
        new FieldData:
          override type CoefficientT = ff.Fp

          override given CoefficientT is Field = summon[ff.Fp is Field]

          override def coeff(x: Int): CoefficientT = ff.Fp(x)
      case _ =>
        throw IllegalArgumentException(s"TDAlab: characteristic must be 0 or a prime, got $characteristic")

  val fieldData = FieldData()
  export fieldData.{*, given}

  type VertexT = Int
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*

  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply

  export org.appliedtopology.tda4j.cells.∆, org.appliedtopology.tda4j.cells.Simplex,
    org.appliedtopology.tda4j.cells.asSimplex
  export org.appliedtopology.tda4j.cells.Cube, org.appliedtopology.tda4j.cells.asCube

  given Show[Simplex[VertexT]] = summon[Show[Simplex[VertexT]]]

  given Show[Chain[Simplex[VertexT], CoefficientT]] = summon[Show[Chain[Simplex[VertexT], CoefficientT]]]

  export cats.implicits.toShow
