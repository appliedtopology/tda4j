package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `MergeForest.members(v, before)` is `v` and every root that died into it, transitively, at a position before
  * `before`: each vertex once. A star of many roots dying into one and a long chain exercise a component far larger
  * than the first buffer; a `java.util.ArrayDeque` used as the traversal stack repeats elements on Scala.js once it
  * grows, which only a component this size shows.
  */
class MergeForestSpec extends Specification:

  /** The merges `(dead, alive, position)` of `n` vertices, the oldest vertex of each pair surviving. */
  private def randomMerges(n: Int, seed: Long, shape: Int): Seq[(Int, Int, Int)] =
    val rng = new scala.util.Random(seed)
    val forest = MergeForest(n)
    val merges = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
    def join(a: Int, b: Int): Unit =
      val (ra, rb) = (forest.find(a), forest.find(b))
      if ra != rb then
        val (dead, alive) = if ra < rb then (rb, ra) else (ra, rb)
        forest.kill(dead, alive, merges.length)
        merges += ((dead, alive, merges.length))
    shape match
      case 0 => (1 until n).foreach(join(0, _)) // a star: every vertex dies into 0
      case 1 => (1 until n).foreach(v => join(v - 1, v)) // a chain
      case _ => (0 until 4 * n).foreach(_ => join(rng.nextInt(n), rng.nextInt(n)))
    merges.toSeq

  private def reference(merges: Seq[(Int, Int, Int)], v: Int, before: Int): Set[Int] =
    var members = Set(v)
    var grew = true
    while grew do
      val next = members ++ merges.collect {
        case (dead, alive, position) if position < before && members(alive) => dead
      }
      grew = next.size > members.size
      members = next
    members

  private def check(n: Int, seed: Long, shape: Int): Boolean =
    val merges = randomMerges(n, seed, shape)
    val forest = MergeForest(n)
    merges.foreach((dead, alive, position) => forest.kill(dead, alive, position))
    forest.seal()
    val rng = new scala.util.Random(seed + 1)
    (0 until n).forall { v =>
      val before = if rng.nextBoolean() then Int.MaxValue else rng.nextInt(merges.length + 1)
      val found = forest.members(v, before).toList
      found.distinct.length == found.length && found.toSet == reference(merges, v, before)
    }

  "MergeForest.members" should {
    "list each vertex of the component once, for a star, a chain and random merges, past the first buffer" in {
      val failures = for
        shape <- 0 until 3
        seed <- 0L until TestPlatform.perPlatform(20L, 5L)
        n <- Seq(5, 40, 150)
        if !check(n, seed, shape)
      yield (shape, seed, n)
      failures.take(3) must beEmpty
    }
  }
