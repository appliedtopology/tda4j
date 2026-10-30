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

  def limit: Option[Int] = None

  def withThreads[A](n: Int)(body: => A): A =
    require(n >= 1, s"the number of threads must be at least 1, got $n")
    body

  def limited[A](limit: Option[Int])(body: => A): A = body

  def byDefault: Boolean = false

  def foreach[A](xs: IndexedSeq[A])(f: A => Unit): Unit = xs.foreach(f)

  def map[A, B](xs: IndexedSeq[A])(f: A => B): IndexedSeq[B] = xs.map(f)

  def both(left: => Unit, right: => Unit): Unit =
    left
    right

  def ranges(count: Int, grain: Int)(f: (Int, Int) => Unit): Unit = f(0, count)

  def tasks(count: Int)(f: Int => Unit): Unit = (0 until count).foreach(f)

  def sortLongs(xs: Array[Long]): Unit = java.util.Arrays.sort(xs)

  /** Sorts `xs(from until to)` ascending, in place. `java.util.Arrays.sort` on Scala.js is a merge sort through boxing
    * accessors that allocates a scratch array as long as the WHOLE of `xs` on every call, so a loop sorting many small
    * ranges of one big array is quadratic (`toTypedArray` copies the whole array too): an in-place quicksort on the
    * range instead, heap sort past a depth limit.
    */
  def sortInts(xs: Array[Int], from: Int, to: Int): Unit =
    require(0 <= from && from <= to && to <= xs.length, s"sortInts: range $from until $to of ${xs.length}")
    quickSort(xs, from, to - 1, 2 * (32 - Integer.numberOfLeadingZeros(to - from)))

  private def quickSort(a: Array[Int], low: Int, high: Int, depthLimit: Int): Unit =
    var lo = low
    var hi = high
    var depth = depthLimit
    while hi - lo >= 16 do
      if depth == 0 then
        heapSort(a, lo, hi)
        return
      depth -= 1
      val mid = lo + (hi - lo) / 2
      // The median of three is the pivot.
      val x = a(lo)
      val y = a(mid)
      val z = a(hi)
      val pivot =
        if x < y then if y < z then y else if x < z then z else x else if x < z then x else if y < z then z else y
      var i = lo
      var j = hi
      while i <= j do
        while a(i) < pivot do i += 1
        while a(j) > pivot do j -= 1
        if i <= j then
          val t = a(i)
          a(i) = a(j)
          a(j) = t
          i += 1
          j -= 1
      // Recurse into the shorter side, loop on the longer: the stack stays logarithmic.
      if j - lo < hi - i then
        quickSort(a, lo, j, depth)
        lo = i
      else
        quickSort(a, i, hi, depth)
        hi = j
    var k = lo + 1
    while k <= hi do
      val v = a(k)
      var m = k - 1
      while m >= lo && a(m) > v do
        a(m + 1) = a(m)
        m -= 1
      a(m + 1) = v
      k += 1

  private def heapSort(a: Array[Int], lo: Int, hi: Int): Unit =
    val n = hi - lo + 1
    def siftDown(start: Int, end: Int): Unit =
      var root = start
      var child = 2 * root + 1
      while child < end do
        if child + 1 < end && a(lo + child) < a(lo + child + 1) then child += 1
        if a(lo + root) < a(lo + child) then
          val t = a(lo + root)
          a(lo + root) = a(lo + child)
          a(lo + child) = t
          root = child
          child = 2 * root + 1
        else child = end
    var start = n / 2 - 1
    while start >= 0 do
      siftDown(start, n)
      start -= 1
    var end = n - 1
    while end > 0 do
      val t = a(lo)
      a(lo) = a(lo + end)
      a(lo + end) = t
      siftDown(0, end)
      end -= 1

  /** [[sortInts]] for `Long` keys (`ParallelPackedCohomology` sorts the keys of each block of a big array). */
  def sortLongs(xs: Array[Long], from: Int, to: Int): Unit =
    require(0 <= from && from <= to && to <= xs.length, s"sortLongs: range $from until $to of ${xs.length}")
    quickSortLongs(xs, from, to - 1, 2 * (32 - Integer.numberOfLeadingZeros(to - from)))

  private def quickSortLongs(a: Array[Long], low: Int, high: Int, depthLimit: Int): Unit =
    var lo = low
    var hi = high
    var depth = depthLimit
    while hi - lo >= 16 do
      if depth == 0 then
        heapSortLongs(a, lo, hi)
        return
      depth -= 1
      val mid = lo + (hi - lo) / 2
      // The median of three is the pivot.
      val x = a(lo)
      val y = a(mid)
      val z = a(hi)
      val pivot =
        if x < y then if y < z then y else if x < z then z else x else if x < z then x else if y < z then z else y
      var i = lo
      var j = hi
      while i <= j do
        while a(i) < pivot do i += 1
        while a(j) > pivot do j -= 1
        if i <= j then
          val t = a(i)
          a(i) = a(j)
          a(j) = t
          i += 1
          j -= 1
      // Recurse into the shorter side, loop on the longer: the stack stays logarithmic.
      if j - lo < hi - i then
        quickSortLongs(a, lo, j, depth)
        lo = i
      else
        quickSortLongs(a, i, hi, depth)
        hi = j
    var k = lo + 1
    while k <= hi do
      val v = a(k)
      var m = k - 1
      while m >= lo && a(m) > v do
        a(m + 1) = a(m)
        m -= 1
      a(m + 1) = v
      k += 1

  private def heapSortLongs(a: Array[Long], lo: Int, hi: Int): Unit =
    val n = hi - lo + 1
    def siftDown(start: Int, end: Int): Unit =
      var root = start
      var child = 2 * root + 1
      while child < end do
        if child + 1 < end && a(lo + child) < a(lo + child + 1) then child += 1
        if a(lo + root) < a(lo + child) then
          val t = a(lo + root)
          a(lo + root) = a(lo + child)
          a(lo + child) = t
          root = child
          child = 2 * root + 1
        else child = end
    var start = n / 2 - 1
    while start >= 0 do
      siftDown(start, n)
      start -= 1
    var end = n - 1
    while end > 0 do
      val t = a(lo)
      a(lo) = a(lo + end)
      a(lo + end) = t
      siftDown(0, end)
      end -= 1

  def sort[A <: AnyRef](xs: Array[A], order: java.util.Comparator[A]): Unit = java.util.Arrays.sort(xs, order)
