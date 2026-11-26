package org.appliedtopology.tda4j

/** A stable merge sort of the indices `0 until n` by a strict "comes before" relation on indices, without boxing: the
  * fast engines order millions of cells by values and keys held in primitive arrays.
  */
object SortIndices:
  def sort(n: Int, before: (Int, Int) => Boolean): Array[Int] =
    var src = Array.range(0, n)
    var dst = new Array[Int](n)
    var width = 1
    while width < n do
      var lo = 0
      while lo < n do
        val mid = math.min(lo + width, n)
        val hi = math.min(lo + 2 * width, n)
        var i = lo
        var j = mid
        var k = lo
        while i < mid && j < hi do
          if before(src(j), src(i)) then
            dst(k) = src(j)
            j += 1
          else
            dst(k) = src(i)
            i += 1
          k += 1
        while i < mid do
          dst(k) = src(i)
          i += 1
          k += 1
        while j < hi do
          dst(k) = src(j)
          j += 1
          k += 1
        lo += 2 * width
      val tmp = src
      src = dst
      dst = tmp
      width *= 2
    src

  /** The elements of `arrangement` (indices into `keys`) stably sorted by `keys`, smallest first, as
    * `java.lang.Double.compare` orders them: equal keys stay in the order of `arrangement`. The keys travel with the
    * indices through the merges, so every pass reads memory in sequence and no comparison goes through a function: this
    * is the sort of the edges of a complex by length, whose ties `arrangement` has already put in the order wanted.
    */
  def stableByKey(keys: Array[Double], arrangement: Array[Int]): Array[Int] =
    val n = arrangement.length
    var ka = new Array[Double](n)
    var ia = arrangement.clone()
    var i = 0
    while i < n do
      ka(i) = keys(arrangement(i))
      i += 1
    var kb = new Array[Double](n)
    var ib = new Array[Int](n)
    // Insertion sort of blocks of `Block`, then bottom-up merges.
    val Block = 16
    var lo = 0
    while lo < n do
      val hi = math.min(lo + Block, n)
      var j = lo + 1
      while j < hi do
        val key = ka(j)
        val id = ia(j)
        var m = j
        while m > lo && java.lang.Double.compare(key, ka(m - 1)) < 0 do
          ka(m) = ka(m - 1)
          ia(m) = ia(m - 1)
          m -= 1
        ka(m) = key
        ia(m) = id
        j += 1
      lo = hi
    var width = Block
    while width < n do
      lo = 0
      while lo < n do
        val mid = math.min(lo + width, n)
        val hi = math.min(lo + 2 * width, n)
        var left = lo
        var right = mid
        var out = lo
        // Two runs already in order are copied as they are (a sorted or nearly sorted input).
        if mid < hi && java.lang.Double.compare(ka(mid), ka(mid - 1)) < 0 then
          while left < mid && right < hi do
            if java.lang.Double.compare(ka(right), ka(left)) < 0 then
              kb(out) = ka(right)
              ib(out) = ia(right)
              right += 1
            else
              kb(out) = ka(left)
              ib(out) = ia(left)
              left += 1
            out += 1
        while left < mid do
          kb(out) = ka(left)
          ib(out) = ia(left)
          left += 1
          out += 1
        while right < hi do
          kb(out) = ka(right)
          ib(out) = ia(right)
          right += 1
          out += 1
        lo += 2 * width
      val tk = ka
      ka = kb
      kb = tk
      val ti = ia
      ia = ib
      ib = ti
      width *= 2
    ia
