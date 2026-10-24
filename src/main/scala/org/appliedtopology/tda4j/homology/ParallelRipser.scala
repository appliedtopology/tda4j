package org.appliedtopology.tda4j

import scala.collection.mutable

/** Vietoris-Rips persistent cohomology of a metric space by [[ParallelPackedCohomology]], on every processor (one
  * thread on JavaScript, the same answer). The run keeps each reduced column's log, so a bar's cocycle is built when it
  * is asked for ([[ParallelRipserRun]]).
  */
// Restricted: a public top-level `ParallelRipser` would join every user's `import org.appliedtopology.tda4j.*`; the
// engine itself is public.
private[tda4j] object ParallelRipser:
  // Several units per thread so a unit that finishes early does not leave its thread idle (`PaperBenchmarkDriver`).
  private val UnitsPerThread = 8

  /** Whether `run` can take a cloud of `points` points to degree `maxDimension` over Z/`characteristic`: a prime the
    * engine's Int arithmetic holds, an `n x n` table of edge ranks that fits an array, simplex indices that fit a
    * `Long`, and, for cycles, keys that are one word (the cycles are found on keys; two-word keys give cocycles only).
    */
  def supports(points: Int, maxDimension: Int, characteristic: Int, cycles: Boolean): Boolean =
    characteristic >= 2 && characteristic <= ParallelPackedCohomology.MaxPrime &&
      points.toLong * points <= Int.MaxValue && {
        def choose(q: Int): BigInt = (1 to q).foldLeft(BigInt(1))((acc, i) => acc * (points - q + i) / i)
        val bits = (1 to math.min(maxDimension + 2, points)).map(q => choose(q).bitLength).max
        val edges = points.toLong * (points - 1) / 2
        val blockBits = 64 - java.lang.Long.numberOfLeadingZeros(edges + 1)
        bits <= 63 && (!cycles || bits + blockBits <= 63)
      }

  /** Runs the engine; `withPairs` keeps the pairing, which cycles are found from. */
  def run(
    metricSpace: FiniteMetricSpace[Int],
    maxDimension: Int,
    threshold: Double,
    characteristic: Int,
    withPairs: Boolean
  ): ParallelRipserRun =
    val n = metricSpace.size
    require(
      n.toLong * n <= Int.MaxValue,
      s"Persistence: engine = ParallelRipser keeps an n x n table, so $n points are too many; use Engine.Ripser"
    )
    val space = ParallelPackedCohomology.Space(n, metricSpace.distance(_, _), threshold, maxDimension, characteristic)
    val threads = Parallel.threads
    val result =
      if threads == 1 then ParallelPackedCohomology.persistence(space, 1, 1, true, withPairs)
      else ParallelPackedCohomology.persistence(space, threads, UnitsPerThread, true, withPairs)
    Run(result, withPairs)

  private final class Run(result: ParallelPackedCohomology.Result, withPairs: Boolean) extends ParallelRipserRun:
    private val space = result.space

    // (degree, birth, death, column) of every bar, ordered as the triples are.
    private val found = result.degrees
      .flatMap(d => d.bars.indices.map(i => (d.bars(i), d.degree, d.barColumns(i))))
      .sortBy(_._1)
    val bars: IndexedSeq[(Int, Double, Double)] = found.map(_._1)

    def cocycle(i: Int): IndexedSeq[(Array[Int], Int)] =
      val (_, degree, column) = found(i)
      val p = space.p
      ParallelPackedCohomology
        .cocycle(result.degrees(degree), p, column)
        .toIndexedSeq
        .map((key, c) => (vertices(key, degree), ((c % p) + p) % p))
        .filter(_._2 != 0)

    lazy val pairs: IndexedSeq[(Int, Long, Long)] =
      require(withPairs, "ParallelRipser: the run was made without its pairs")
      result.degrees.flatMap(d => d.pairs.map((column, row) => (d.degree, column, row)))

    // The index in `pairs` of each degree's pair, by its birth column.
    private lazy val pairIndex: Array[mutable.LongMap[Int]] =
      val maps = Array.fill(result.degrees.length)(mutable.LongMap.empty[Int])
      for ((degree, column, _), at) <- pairs.zipWithIndex do maps(degree)(column) = at
      maps

    def pairOfBar(i: Int): Int =
      val (_, degree, column) = found(i)
      pairIndex(degree)(column)

    def vertices(key: Long, dim: Int): Array[Int] =
      val vs = new Array[Int](dim + 1)
      space.decode(space.index(key), dim + 1, vs)
      vs

    def faces(key: Long, dim: Int): Array[Long] =
      val vs = vertices(key, dim)
      val face = new Array[Int](dim)
      Array.tabulate(dim + 1) { omitted =>
        var at = 0
        var j = 0
        while j <= dim do
          if j != omitted then
            face(at) = vs(j)
            at += 1
          j += 1
        space.key(ParallelPackedCohomology.blockOf(space, face, dim), space.indexOf(face, dim))
      }
