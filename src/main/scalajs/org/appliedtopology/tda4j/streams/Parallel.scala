package org.appliedtopology.tda4j

/** Runs independent work items. A JavaScript program has one thread, so this runs them one after the other, in order;
  * the JVM build (`src/main/scalajvm`) has the same object and shares the work between threads. A caller that asks for
  * parallelism (`parallelFiltrationValue`, `AlphaComplexDQP`'s `parallel`, the alpha triangulation) gets the same
  * answer, only not faster.
  */
// Restricted: a public top-level `Parallel` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object Parallel:
  def threads: Int = 1

  def sequentially[A](body: => A): A = body

  def foreach[A](xs: IndexedSeq[A])(f: A => Unit): Unit = xs.foreach(f)

  def map[A, B](xs: IndexedSeq[A])(f: A => B): IndexedSeq[B] = xs.map(f)

  def ranges(count: Int, grain: Int)(f: (Int, Int) => Unit): Unit = f(0, count)

  def sort[A <: AnyRef](xs: Array[A], order: java.util.Comparator[A]): Unit = java.util.Arrays.sort(xs, order)
