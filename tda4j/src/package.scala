package org.appliedtopology.tda4j

import cats.Show
import org.appliedtopology.tda4j.algebra.{*, given}
import org.appliedtopology.tda4j.cells.{*, given}
import org.appliedtopology.tda4j.streams.{*, given}
import org.appliedtopology.tda4j.homology.{*, given}
import org.appliedtopology.tda4j.alpha.{*, given}

/** Thin user-facing facade over `SimplicialHomologyEngine`: brings `Chain`'s own `RingModule` arithmetic
  * (`+`/`-`/`⊠`/etc.) into scope on `Simplex` values directly, via `export` plus an implicit `Simplex -> Chain`
  * widening -- convenience for interactive/notebook-style use, never itself consulted by an engine (see CLAUDE.md's
  * generic-`given`-capture note).
  */
class TDAContext[VertexT: Ordering, CoefficientT: Field, FiltrationT: Ordering]
    extends SimplicialHomologyEngine[VertexT, CoefficientT, FiltrationT]():
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT } =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule { type R = CoefficientT }]
  export chainIsRingModule.*
  import scala.language.implicitConversions
  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply


/**
 * Here's an idea for how to build up these contexts?
 * Mix-in traits, each of which sets an internal fixed typename and imports some convenience functionality.
 * Combine to a context trait which you either extend or invoke to have access to all the corresponding types and functions.
 *
 * We **cannot** support (bc illegal in Scala) a syntax like `import tdacontext(p=3).{*,given}`.
 * We can support a syntax like `val tdac = tdacontext(p=3); import tdac.{*,given}`. This is likely to be the most
 * compact we can possibly hope for.
 *
 * What settings do we want to allow for? The generic setting just imports everything you need where you need it.
 *
 * - Choice of field.
 * - Choice of topological paradigm: simplex, cube, simplicial set.
 * - Choice of filtration paradigm: int, double
 */

enum FieldChoice:
  case DoubleApproximated(precision: Double)
  case FiniteField(p: Int)

enum FiltrationChoice:
  case IntFiltration
  case DoubleFiltration

enum TopologyChoice:
  case SimplexInt
  case Cube
  case SimplicialSet

trait TDAenvironment {
  type CoefficientT
  type CellT
  type FiltrationT

  import org.appliedtopology.tda4j.io
  import org.appliedtopology.tda4j.barcode

  given CoefficientT is Field = compiletime.deferred

  given CellT is Cell = compiletime.deferred
}

class TDAlab(characteristic: Int, precision: Double = 1e-9) {

  import org.appliedtopology.tda4j.io
  import org.appliedtopology.tda4j.barcode
  import org.appliedtopology.tda4j.homology
  import org.appliedtopology.tda4j.streams
  import org.appliedtopology.tda4j.alpha
  import org.appliedtopology.tda4j.algebra.{*, given}
  import org.appliedtopology.tda4j.cells.{*, given}

  import cats.syntax.all.*

  trait FieldData {
    type CoefficientT

    given CoefficientT is Field = compiletime.deferred

    def coeff(x: Int): CoefficientT

    def Fp(x: Int): CoefficientT = coeff(x)
  }

  object FieldData {
    def apply(): FieldData = characteristic match {
      case 0 => new FieldData {
        override type CoefficientT = Double

        override given CoefficientT is Field = Field.DoubleApproximated(precision)

        override def coeff(x: Int): CoefficientT = x.toDouble
      }
      case p if BigInt(characteristic).isProbablePrime(certainty = 100) => {
        val ff = FiniteField(p)
        import ff.given
        new FieldData {
          override type CoefficientT = ff.Fp

          override given CoefficientT is Field = summon[ff.Fp is Field]

          override def coeff(x: Int): CoefficientT = ff.Fp(x)
        }
      }
    }
  }

  val fieldData = FieldData()
  export fieldData.{*, given}

  type VertexT = Int
  val chainIsRingModule: Chain[Simplex[VertexT], CoefficientT] is RingModule {type R = CoefficientT} =
    summon[Chain[Simplex[VertexT], CoefficientT] is RingModule {type R = CoefficientT}]
  export chainIsRingModule.*

  given [T: Ordering] => Conversion[Simplex[T], Chain[Simplex[T], CoefficientT]] =
    Chain.apply

  export org.appliedtopology.tda4j.cells.∆, org.appliedtopology.tda4j.cells.Simplex, org.appliedtopology.tda4j.cells.asSimplex
  export org.appliedtopology.tda4j.cells.Cube, org.appliedtopology.tda4j.cells.asCube

  given Show[Simplex[VertexT]] = summon[Show[Simplex[VertexT]]]

  given Show[Chain[Simplex[VertexT], CoefficientT]] = summon[Show[Chain[Simplex[VertexT], CoefficientT]]]

  export cats.implicits.toShow
}
