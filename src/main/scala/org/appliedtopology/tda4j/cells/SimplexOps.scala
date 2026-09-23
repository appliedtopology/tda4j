package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag

/** Inherit a selection of the SortedSet methods and add other utility methods
  *
  * A `trait`, mixed into `object Simplex` (`Simplex.scala`), rather than a top-level `extension` clause: Scala 3 does
  * not allow same-named top-level extension methods for unrelated receiver types across different files in one package
  * (`.claude/WORKLOG-extension-companion-objects.md`). Routing through the opaque type's own companion object instead
  * scopes lookup by nominal receiver type, so a future opaque type's extensions can reuse a name like
  * `show`/`underlying` without colliding with this one.
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
    // ----- zipping: IndexedSeq, in vertex order -- NOT the underlying SortedSet's own zip*, which return an
    // unordered `Set` (hash-ordered from 5 elements on) and so lose the vertex order these exist to expose.
    def zip[B](that: IterableOnce[B]): IndexedSeq[(VertexT, B)] = spx.underlying.toIndexedSeq.zip(that)
    def zipAll[V >: VertexT, B](that: Iterable[B], thisElem: V, thatElem: B): IndexedSeq[(V, B)] =
      spx.underlying.toIndexedSeq.zipAll(that, thisElem, thatElem)
    def zipWithIndex: IndexedSeq[(VertexT, Int)] = spx.underlying.toIndexedSeq.zipWithIndex

/** `min`/`max` stay a top-level extension, not moved into `SimplexOps`/`object Simplex`'s companion scope like
  * everything else above: Scala 3 tries phase-1 extension candidates (lexical scope, including any wildcard
  * `import math.Ordering.Implicits.*` a call site has, which brings in `infixOrderingOps`'s binary `min(rhs)`/
  * `max(rhs)`) before phase-2 (the receiver's own companion). Moving these into the companion drops them to phase 2, so
  * a call site with that import silently rebinds `spx.max` to the stdlib's binary version instead -- a hard type error
  * (wrong arity), not a silent behavior change, but only caught at those call sites, not here. General lesson: an
  * opaque type's extension name that collides with a wildcard-importable stdlib extension (`min`/`max`/`<`/`compare`
  * from `Ordering.Implicits`, possibly others) needs to stay top-level.
  */
extension [VertexT](spx: Simplex[VertexT])
  def min[B >: VertexT: Ordering]: VertexT = spx.underlying.min
  def max[B >: VertexT: Ordering]: VertexT = spx.underlying.max
