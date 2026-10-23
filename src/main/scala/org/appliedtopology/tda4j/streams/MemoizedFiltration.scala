package org.appliedtopology.tda4j

import scala.collection.mutable

/** A filtration function on simplices that remembers its values in `cache`.
  *
  * `applyOrElse` looks in `cache` first and checks `isDefined` only on a miss. The default `applyOrElse` of a
  * `PartialFunction` calls `isDefinedAt` and then `apply`, which for a simplex means walking its vertices to check them
  * before every lookup of a value that is already there.
  */
// Restricted: a public top-level `MemoizedFiltration` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object MemoizedFiltration:
  def apply(cache: mutable.Map[Simplex[Int], Double], isDefined: Simplex[Int] => Boolean)(
    compute: Simplex[Int] => Double
  ): PartialFunction[Simplex[Int], Double] =
    new PartialFunction[Simplex[Int], Double]:
      def isDefinedAt(spx: Simplex[Int]): Boolean = isDefined(spx)
      def apply(spx: Simplex[Int]): Double = cache.getOrElseUpdate(spx, compute(spx))
      override def applyOrElse[A1 <: Simplex[Int], B1 >: Double](spx: A1, default: A1 => B1): B1 =
        cache.get(spx) match
          case Some(value) => value
          case None        => if isDefined(spx) then apply(spx) else default(spx)
