package org.appliedtopology.tda4j

import scala.util.Random

/** What does `EuclideanMetricSpace` keep of a point cloud (#288)? The retained heap, after a full collection, of the
  * caller's array, of the space built over it (its snapshot of the coordinates), and of the space after its first
  * `neighbors` call (the vantage point tree, which copies the coordinates again). Run with a fixed heap:
  * {{{
  * java -Xmx8g -XX:+UseSerialGC -cp $CP org.appliedtopology.tda4j.SnapshotMemoryDriver <n> <dimension>
  * }}}
  */
object SnapshotMemoryDriver:
  private def used(): Long =
    for _ <- 0 until 4 do System.gc()
    val r = Runtime.getRuntime
    r.totalMemory - r.freeMemory

  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val d = args(1).toInt
    val base = used()
    val rng = Random(1)
    val points = Array.fill(n)(Array.fill(d)(rng.nextDouble()))
    val afterPoints = used()
    val space = EuclideanMetricSpace(points, cacheDistances = false)
    val afterSpace = used()
    val found = space.neighbors(points(0), 0.01).size
    val afterTree = used()
    def mb(x: Long) = x / 1048576.0
    println(f"n=$n d=$d (a neighbours call found $found points)")
    println(
      f"  caller's array          ${mb(afterPoints - base)}%9.1f MB  (${(afterPoints - base).toDouble / n}%.0f bytes a point)"
    )
    println(
      f"  + the space's snapshot  ${mb(afterSpace - afterPoints)}%9.1f MB  (${(afterSpace - afterPoints).toDouble / n}%.0f bytes a point)"
    )
    println(
      f"  + the vantage tree      ${mb(afterTree - afterSpace)}%9.1f MB  (${(afterTree - afterSpace).toDouble / n}%.0f bytes a point)"
    )
    println(
      f"  total held              ${mb(afterTree - base)}%9.1f MB (the array and the space are still referenced here: ${points.length + space.size})"
    )
