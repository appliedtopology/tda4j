package org.appliedtopology.tda4j

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.TreeSet
import scala.collection.mutable
import scala.compiletime.asMatchable
import scala.util.hashing.Hashing

/** The memo table of a filtration value, safe to fill from several threads: a lock-free `TrieMap`, whose
  * `getOrElseUpdate` is atomic. The Scala.js build has the same object (`src/main/scalajs`) with a `HashMap`, since
  * `TrieMap` does not exist there and a JavaScript program has one thread.
  *
  * The keys are almost always [[Simplex]] values, which are `TreeSet`s: their own `hashCode` and `equals` walk the tree
  * through iterators that allocate, and that was most of the time a stream spent on a lookup. The table hashes and
  * compares a `TreeSet` by a plain traversal instead; any other key is hashed and compared as usual.
  */
// Restricted: a public top-level `FiltrationCache` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object FiltrationCache:
  def empty[K, V]: mutable.Map[K, V] =
    new TrieMap[K, V](SetHashing.asInstanceOf[Hashing[K]], SetEquiv.asInstanceOf[Equiv[K]])

  // The sum of a mixed hash of each element, so that it does not depend on the order of the traversal.
  private object SetHashing extends Hashing[Any]:
    def hash(key: Any): Int = key.asMatchable match
      case set: TreeSet[?] =>
        var sum = set.size
        set.foreach { element =>
          // MurmurHash3's finalizer: a linear mix would make every simplex with the same vertex sum collide.
          var x = element.##
          x ^= x >>> 16
          x *= 0x85ebca6b
          x ^= x >>> 13
          x *= 0xc2b2ae35
          sum += x ^ (x >>> 16)
        }
        sum
      case other => other.##

  private object SetEquiv extends Equiv[Any]:
    def equiv(x: Any, y: Any): Boolean = (x.asMatchable, y.asMatchable) match
      case (a: TreeSet[?], b: TreeSet[?]) =>
        (a eq b) || (a.size == b.size && {
          var all = true
          a.foreach(element => if all && !b.asInstanceOf[TreeSet[Any]].contains(element) then all = false)
          all
        })
      case _ => x == y
