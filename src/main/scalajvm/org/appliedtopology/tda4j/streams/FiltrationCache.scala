package org.appliedtopology.tda4j

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** The memo table of a filtration value, safe to fill from several threads: a lock-free `TrieMap`, whose
  * `getOrElseUpdate` is atomic. The Scala.js build has the same object (`src/main/scalajs`) with a `HashMap`, since
  * `TrieMap` does not exist there and a JavaScript program has one thread.
  */
// Restricted: a public top-level `FiltrationCache` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object FiltrationCache:
  def empty[K, V]: mutable.Map[K, V] = TrieMap.empty[K, V]
