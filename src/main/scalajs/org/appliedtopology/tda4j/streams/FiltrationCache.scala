package org.appliedtopology.tda4j

import scala.collection.mutable

/** The memo table of a filtration value. On Scala.js a plain `HashMap`: there is one thread, and `TrieMap` is not
  * available. The JVM build has the same object (`src/main/scalajvm`) with a `TrieMap` that several threads can fill.
  */
// Restricted: a public top-level `FiltrationCache` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object FiltrationCache:
  def empty[K, V]: mutable.Map[K, V] = mutable.HashMap.empty[K, V]
