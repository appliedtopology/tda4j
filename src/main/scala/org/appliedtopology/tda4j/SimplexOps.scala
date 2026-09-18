package org.appliedtopology.tda4j

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag

/** Inherit a selection of the SortedSet methods and add other utility methods
  *
  * A `trait`, mixed into `object Simplex` (`Simplex.scala`), rather than a top-level `extension` clause: Scala 3 does
  * not allow same-named top-level extension methods for unrelated receiver types across different files in one package
  * (confirmed empirically, not merely suspected -- see `.claude/WORKLOG-cubical.md`'s "naming collision" section and
  * `.claude/WORKLOG-extension-companion-objects.md`). Routing through the opaque type's own companion object instead
  * scopes lookup by nominal receiver type, so a future opaque type's extensions can reuse a name like
  * `show`/`underlying` without colliding with this one -- confirmed by a standalone `scala-cli` repro mirroring this
  * exact shape (generic opaque type, non-generic companion, extension body split across two files) before this file was
  * changed, not merely reasoned through.
  */
trait SimplexOps:
  extension [VertexT](spx: Simplex[VertexT])
    // ----- rendering & dimension
    def show: String = spx.underlying.mkString(s"∆(", ",", ")")
    def dim: Int = spx.underlying.size - 1
    // ----- size and membership
    def contains(elem: VertexT): Boolean = spx.underlying.contains(elem)
    def nonEmpty: Boolean = spx.underlying.nonEmpty
    def isEmpty: Boolean = spx.underlying.isEmpty
    def size: Int = spx.underlying.size
    def forall(p: VertexT => Boolean): Boolean = spx.underlying.forall(p)
    def count(p: VertexT => Boolean): Int = spx.underlying.count(p)
    def exists(p: VertexT => Boolean): Boolean = spx.underlying.exists(p)
    // ----- iteration
    def foreach(f: VertexT => Unit): Unit = spx.underlying.foreach(f)
    def iterator: Iterator[VertexT] = spx.underlying.iterator
    // ----- ends
    def head: VertexT = spx.underlying.head
    def headOption: Option[VertexT] = spx.underlying.headOption
    def first: VertexT = spx.underlying.head
    def last: VertexT = spx.underlying.last
    def firstKey: VertexT = spx.underlying.firstKey
    def lastKey: VertexT = spx.underlying.lastKey
    def firstOption: Option[VertexT] = spx.underlying.headOption
    def lastOption: Option[VertexT] = spx.underlying.lastOption
    def tail: Simplex[VertexT] = spx.underlying.tail.asSimplex
    def tails: Iterator[Simplex[VertexT]] = spx.underlying.tails.map(_.asSimplex)
    // ----- conversions
    def toArray[B >: VertexT: ClassTag]: Array[B] = spx.underlying.toArray
    def toIndexedSeq: IndexedSeq[VertexT] = spx.underlying.toIndexedSeq
    def toList: List[VertexT] = spx.underlying.toList
    def toSeq: Seq[VertexT] = spx.underlying.toSeq
    def toSet: Set[VertexT] = spx.underlying.toSet
    def toSortedSet: SortedSet[VertexT] = spx.underlying
    // ---- order queries
    // `min`/`max` themselves are NOT here -- see the top-level extension clause below this trait.
    def minAfter(key: VertexT): Option[VertexT] = spx.underlying.minAfter(key)
    def minBy[B: Ordering](f: VertexT => B): VertexT = spx.underlying.minBy(f)
    def minByOption[B: Ordering](f: VertexT => B): Option[VertexT] = spx.underlying.minByOption(f)
    def minOption[B >: VertexT](using ordering: Ordering[B]): Option[B] = spx.underlying.minOption
    def maxBefore(key: VertexT): Option[VertexT] = spx.underlying.maxBefore(key)
    def maxBy[B: Ordering](f: VertexT => B): VertexT = spx.underlying.maxBy(f)
    def maxByOption[B: Ordering](f: VertexT => B): Option[VertexT] = spx.underlying.maxByOption(f)
    def maxOption[B >: VertexT](using ordering: Ordering[B]): Option[B] = spx.underlying.maxOption
    // ----- set algebra
    def union(that: Simplex[VertexT]): Simplex[VertexT] = spx.underlying.union(that.underlying).asSimplex
    def |(that: Simplex[VertexT]): Simplex[VertexT] = spx.underlying.union(that.underlying).asSimplex
    def incl(v: VertexT): Simplex[VertexT] = spx.underlying.incl(v).asSimplex
    def +(v: VertexT): Simplex[VertexT] = spx.underlying.incl(v).asSimplex
    def excl(v: VertexT): Simplex[VertexT] = spx.underlying.excl(v).asSimplex
    def -(v: VertexT): Simplex[VertexT] = spx.underlying.excl(v).asSimplex
    def concat(that: IterableOnce[VertexT]): Simplex[VertexT] = spx.underlying.concat(that).asSimplex
    def ++(that: IterableOnce[VertexT]): Simplex[VertexT] = spx.underlying.concat(that).asSimplex
    // ----- transformation
    def map[B: Ordering](f: VertexT => B): Simplex[B] = spx.underlying.map(f).asSimplex
    def flatMap[B: Ordering](f: VertexT => IterableOnce[B]): Simplex[B] = spx.underlying.flatMap(f).asSimplex
    def filter(pred: VertexT => Boolean): Simplex[VertexT] = spx.underlying.filter(pred).asSimplex
    def filterNot(pred: VertexT => Boolean): Simplex[VertexT] = spx.underlying.filterNot(pred).asSimplex
    // ----- slicing
    def drop(n: Int): Simplex[VertexT] = spx.underlying.drop(n).asSimplex
    def dropRight(n: Int): Simplex[VertexT] = spx.underlying.dropRight(n).asSimplex
    def dropWhile(p: VertexT => Boolean): Simplex[VertexT] = spx.underlying.dropWhile(p).asSimplex
    def dropIndex(n: Int): Simplex[VertexT] = (spx.underlying -- spx.underlying.slice(n, n + 1)).asSimplex
    def take(n: Int): Simplex[VertexT] = spx.underlying.take(n).asSimplex
    def takeRight(n: Int): Simplex[VertexT] = spx.underlying.takeRight(n).asSimplex
    // ----- zipping
    def zip[B](that: IterableOnce[B]): Set[(VertexT, B)] = spx.underlying.toSet.zip(that)
    def zipAll[V >: VertexT, B](that: Iterable[B], thisElem: V, thatElem: B): Set[(V, B)] =
      spx.underlying.zipAll(that, thisElem, thatElem)
    def zipWithIndex: Set[(VertexT, Int)] = spx.underlying.zipWithIndex

/** `min`/`max`, unlike everything else in `SimplexOps` above, are kept as a plain TOP-LEVEL extension clause rather
  * than moved into the trait (hence not routed through `object Simplex`'s companion scope) -- a second, different kind
  * of exception from `asSimplex`/`asCube`'s (see `Simplex.scala`/`Cubical.scala`), found the hard way: several call
  * sites elsewhere in this codebase (`FiniteMetricSpace.scala`, `SimplexStream.scala`) do
  * `import math.Ordering.Implicits.*`, which brings `scala.math.Ordering.Implicits.infixOrderingOps`'s OWN
  * `min(rhs: T)`/`max(rhs: T)` (binary, generic over any `T: Ordering`) into LEXICAL scope there -- a phase-1 extension
  * candidate. Extension-method resolution tries phase 1 (lexical scope: imports, local defs, and every top-level
  * `def`/`extension` visible package-wide) before ever considering phase 2 (the receiver type's own implicit/companion
  * scope) -- so once `min`/`max` moved into `object Simplex`'s companion (phase 2 only), calls like `spx.max` at those
  * sites stopped finding `SimplexOps.max` (this file's zero-arg "largest element") and committed instead to the
  * stdlib's phase-1 `infixOrderingOps.max(rhs)`, eta-expanded to a function value for lack of an argument -- a hard
  * type error (`Found: Simplex[Double] => Simplex[Double], Required: Double`), not a silent behavior change, confirmed
  * empirically by moving `min`/`max` into the trait and rerunning `sbt compile`. Keeping `min`/`max` at the TOP level
  * (their original position, and the same phase-1 slot `infixOrderingOps` competes for) restores the original, correct
  * overload resolution: two phase-1 candidates, one of which (`SimplexOps.min`/`.max`, a genuine zero-arg method)
  * actually type-checks as called and the other (`infixOrderingOps`) doesn't without an explicit argument, so ordinary
  * overload resolution -- not phase priority -- picks the right one. This is the general lesson for any future opaque
  * type in this codebase: a method name that collides with a common, wildcard-importable stdlib extension
  * (`min`/`max`/`<`/`compare`/etc. from `Ordering.Implicits`, but potentially others) is safer left as a top-level
  * extension, not moved to the companion object -- the companion-object fix solves collisions between two of THIS
  * codebase's own opaque types (see `SimplexOps`'s own doc above), not collisions with the standard library's own
  * generic extensions, which predate and are orthogonal to that fix. See
  * `.claude/WORKLOG-extension-companion-objects.md`.
  */
extension [VertexT](spx: Simplex[VertexT])
  def min[B >: VertexT: Ordering]: VertexT = spx.underlying.min
  def max[B >: VertexT: Ordering]: VertexT = spx.underlying.max
