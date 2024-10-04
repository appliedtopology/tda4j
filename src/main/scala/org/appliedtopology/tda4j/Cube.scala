package org.appliedtopology.tda4j

import Ordering.Implicits.seqOrdering
import scala.annotation.tailrec

sealed trait ElementaryInterval {
  def n: Int
}

given elementaryIntervalOrdering: Ordering[ElementaryInterval] = Ordering.by(i => i.n)

case class DegenerateInterval(n: Int) extends ElementaryInterval {
  override def toString: String = s"[$n,$n]"
}
case class FullInterval(n: Int) extends ElementaryInterval {
  override def toString: String = s"[$n,${n + 1}]"
}
object ElementaryInterval:
  given (ElementaryInterval is OrderedCell) with {
    extension (t: ElementaryInterval)
      def boundary[CoefficientT: Fractional]: Chain[ElementaryInterval, CoefficientT] = t match {
        case td @ DegenerateInterval(n) => Chain.empty[ElementaryInterval, CoefficientT]
        case tf @ FullInterval(n) =>
          Chain[ElementaryInterval, CoefficientT](DegenerateInterval(t.n + 1)) - Chain[
            ElementaryInterval,
            CoefficientT
          ](DegenerateInterval(t.n))
      }
      def dim: Int = t match
        case td @ DegenerateInterval(n) => 0
        case tf @ FullInterval(n)       => 1
  }
case class ElementaryCube(val intervals: List[ElementaryInterval]) {
  def boundaryImpl[CoefficientT: Fractional]: Chain[ElementaryCube, CoefficientT] =
    if (this.dim == 0) Chain()
    else {
      val chainOps = summon[Chain[ElementaryInterval, CoefficientT] is RingModule]
      import chainOps.{*, given}
      val fr = summon[Fractional[CoefficientT]]
      import math.Fractional.Implicits.infixFractionalOps

      // For a cube that decomposes as Q = I*P where I is a single interval, we define
      // ∂Q = ∂I * P + (-1)^{dim I} I * ∂P

      // Given stuff-already-processed and an I*P decomposition, figure out whether this I changes the accumulated
      // sign, and produce the ∂I * P + sign pair
      def process(
        left: List[ElementaryInterval],
        current: ElementaryInterval,
        right: List[ElementaryInterval]
      ): (CoefficientT, Chain[ElementaryCube, CoefficientT]) = current match {
        case DegenerateInterval(n) => (fr.one, Chain())
        case FullInterval(n) =>
          (
            fr.negate(fr.one),
            Chain(
              ElementaryCube(left ++ (DegenerateInterval(n + 1) :: right)) -> fr.one,
              ElementaryCube(left ++ (DegenerateInterval(n) :: right)) -> fr.negate(fr.one)
            )
          )
      }

      @tailrec
      def boundaryOf(
        left: List[ElementaryInterval],
        current: ElementaryInterval,
        right: List[ElementaryInterval],
        sign: CoefficientT,
        acc: Chain[ElementaryCube, CoefficientT]
      ): Chain[ElementaryCube, CoefficientT] = {
        val cubeOps = summon[Chain[ElementaryCube, CoefficientT] is RingModule]
        import cubeOps.*
        val (sgn, newchain) = process(left, current, right)
        right match {
          case Nil =>
            // process current, then return the resulting acc
            acc + ((sign * sgn) ⊠ newchain)
          case c :: cs =>
            // process current, then call with c as new current
            boundaryOf(left.appended(current), c, cs, sign * sgn, acc + ((sign * sgn) ⊠ newchain))
        }
      }
      intervals match {
        case Nil       => Chain()
        case (i :: is) => boundaryOf(List.empty, i, is, fr.one, Chain())
      }
    }

  def emb: Int = intervals.size

  infix def cubeProduct(left: ElementaryCube, right: ElementaryCube): ElementaryCube =
    ElementaryCube(left.intervals ++ right.intervals)

  override def toString: String =
    s"Cubical[${intervals.map(_.toString).mkString("x")}]"
}

given elementaryCubeOrdering: Ordering[ElementaryCube] = Ordering.by(c => c.intervals)

given (ElementaryCube is OrderedCell) with {
  extension (t: ElementaryCube)
    override def boundary[CoefficientT: Fractional]: Chain[ElementaryCube, CoefficientT] =
      t.boundaryImpl
  extension (t: ElementaryCube) override def dim: Int = t.intervals.map(_.dim).sum

  def compare(x: ElementaryCube, y: ElementaryCube): Int = elementaryCubeOrdering.compare(x, y)
}

trait CubeStream[FiltrationT: Ordering] extends CellStream[ElementaryCube, FiltrationT]

object Cubical {
  def from[T: Filterable: Ordering](grid: Array[Array[T]]): CubeStream[T] = new CubeStream[T] {
    val largest = summon[Filterable[T]].largest
    val smallest = summon[Filterable[T]].smallest

    override def filtrationOrdering: Ordering[ElementaryCube] =
      Ordering.by(cubes).orElseBy(_.dim).orElseBy(_.intervals.map(int => int.n))

    val cubes: Map[ElementaryCube, T] = Map.from(
      (
        for
          (row, i) <- grid.zipWithIndex
          (pixel, j) <- row.zipWithIndex
          firstInterval <- Seq(FullInterval(i), DegenerateInterval(i))
          secondInterval <- Seq(FullInterval(j), DegenerateInterval(j))
        yield ElementaryCube(List(firstInterval, secondInterval)) -> pixel
      ).toList.sorted.reverse
    ) // reversed so that the last element saved for any one cube is the one with lowest Self-value

    override def filtrationValue: PartialFunction[ElementaryCube, T] =
      cubes

    override def iterator: Iterator[ElementaryCube] =
      cubes.keys.toList
        .sorted(using Ordering.by(cubes.apply).orElseBy(_.dim)(using ord = Ordering.Int.reverse))
        .iterator
  }
}
