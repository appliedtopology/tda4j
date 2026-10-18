package org.appliedtopology.tda4j

import scala.collection.parallel.CollectionConverters.*

/** Runs independent work items on the common fork-join pool. The Scala.js build has the same object and runs them one
  * after the other (`src/main/scalajs`): JavaScript has no threads to share the work between.
  */
// Restricted: a public top-level `Parallel` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object Parallel:
  /** `f` on every element, in no particular order and possibly at the same time. */
  def foreach[A](xs: IndexedSeq[A])(f: A => Unit): Unit = xs.par.foreach(f)

  /** `f` on every element, possibly at the same time; the results are in the order of `xs`. */
  def map[A, B](xs: IndexedSeq[A])(f: A => B): IndexedSeq[B] = xs.par.map(f).toIndexedSeq
