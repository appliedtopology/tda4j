package org.appliedtopology.tda4j

import java.util.concurrent.{ForkJoinPool, ForkJoinTask}
import java.util.stream.IntStream
import scala.collection.parallel.CollectionConverters.*

/** Runs independent work items on the common fork-join pool. The Scala.js build has the same object and runs them one
  * after the other (`src/main/scalajs`): JavaScript has no threads to share the work between.
  *
  * The pool is the JVM's common pool, so `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N` limits every
  * parallel step of the library at once (`1` runs them all on the calling thread). Every user of this object computes
  * the same result however the work is split: a step may be faster in parallel, never different.
  */
// Restricted: a public top-level `Parallel` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] object Parallel:
  // Set by `sequentially` on the calling thread, which is where every step decides whether to fork.
  private val forcedSequential = new ThreadLocal[java.lang.Boolean]

  /** The number of threads a step can use here: 1 inside `sequentially`, else the common pool's workers and the caller.
    */
  def threads: Int =
    if forcedSequential.get() eq java.lang.Boolean.TRUE then 1 else ForkJoinPool.commonPool().getParallelism + 1

  /** Runs `body` with every parallel step called from this thread run on this thread: a reference run to compare a
    * parallel one against.
    */
  def sequentially[A](body: => A): A =
    val before = forcedSequential.get()
    forcedSequential.set(java.lang.Boolean.TRUE)
    try body
    finally forcedSequential.set(before)

  /** The default of the `parallelFiltrationValue` flags: share the per-cell work when there are threads to share it. */
  def byDefault: Boolean = threads > 1

  /** `f` on every element, in no particular order and possibly at the same time. */
  def foreach[A](xs: IndexedSeq[A])(f: A => Unit): Unit =
    if threads == 1 then xs.foreach(f) else xs.par.foreach(f)

  /** `f` on every element, possibly at the same time; the results are in the order of `xs`. */
  def map[A, B](xs: IndexedSeq[A])(f: A => B): IndexedSeq[B] =
    if threads == 1 then xs.map(f) else xs.par.map(f).toIndexedSeq

  /** `left` and `right`, possibly at the same time (a divide and conquer step: call it on the halves while they are
    * big).
    */
  def both(left: => Unit, right: => Unit): Unit =
    if threads == 1 then
      left
      right
    else
      val task: Runnable = () => right
      val other = ForkJoinTask.adapt(task)
      other.fork()
      try left
      finally other.join()

  /** `f(from, until)` on consecutive ranges of at least `grain` indices that together cover `0 until count`, possibly
    * at the same time; one call when the work is too small to share.
    */
  def ranges(count: Int, grain: Int)(f: (Int, Int) => Unit): Unit =
    val parts = math.min(threads * 4, count / math.max(1, grain))
    if parts <= 1 then f(0, count)
    else
      val size = (count + parts - 1) / parts
      IntStream.range(0, parts).parallel().forEach(p => f(p * size, math.min(count, (p + 1) * size)))

  /** Sorts `xs` with `order`; equal elements may be in either order, so `order` must be total on what is sorted. */
  def sort[A <: AnyRef](xs: Array[A], order: java.util.Comparator[A]): Unit =
    if threads == 1 then java.util.Arrays.sort(xs, order) else java.util.Arrays.parallelSort(xs, order)
