package org.appliedtopology.tda4j

/** Runs independent work items. A JavaScript program has one thread, so this runs them one after the other, in order;
  * the JVM build (`src/main/scalajvm`) has the same object and shares the work between threads. A caller that asks for
  * parallelism (`parallelFiltrationValue`, `AlphaComplexDQP`'s `parallel`) gets the same answer, only not faster.
  */
// Restricted: a public top-level `Parallel` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object Parallel:
  def foreach[A](xs: IndexedSeq[A])(f: A => Unit): Unit = xs.foreach(f)

  def map[A, B](xs: IndexedSeq[A])(f: A => B): IndexedSeq[B] = xs.map(f)
