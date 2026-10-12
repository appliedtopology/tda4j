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
