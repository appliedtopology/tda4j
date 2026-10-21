package org.appliedtopology.tda4j

import LayeredInorderSimplexStream.{sortIndices, Generator, IntBuffer, Layer}

import java.util.concurrent.{Callable, ExecutorService, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Test-scope experiment (JVM): persistent cohomology of a Vietoris-Rips complex over F_p, degree by degree with
  * clearing as Ripser does (degree 0 by union-find), on explicit coboundary matrices, reduced either sequentially or by
  * edge blocks on a thread pool. Both reductions run the same column arithmetic on the same matrices.
  *
  * Cells are ordered by block (a cell's first longest edge, by length, then smaller end, then larger end), then
  * dimension, then the layered generator's order. A coface's block is never before its face's, so a column's entries
  * lie in its own block or later ones, and only columns of the same block can have their pivot inside it. Phase 1
  * reduces each block's columns (in reverse order, as cohomology goes) by that block's columns only, while the pivot
  * stays in the block: every pair it makes is one the sequential reduction makes. A column whose pivot leaves its block
  * is deferred. Phase 2a reduces the deferred columns, in parallel, by the columns phase 1 paired (final, so only
  * read); phase 2b finishes them one by one in order. A positive `chunk` merges consecutive blocks into units of at
  * least that many columns, which is the chunk algorithm of Bauer, Kerber and Reininghaus with chunk boundaries on
  * block boundaries (all the argument needs is that a unit is contiguous in the order).
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.BlockParallelReduction <dataset> <n> <maxDim> <threshold|mer> <method> [trials] [seed] [p]
  * }}}
  *
  * `dataset` is `cube<d>` (uniform in the unit cube of R^d), `torus` or `sphere` (in R^3, with noise), or
  * `alpha:<cloud>` for the cloud's alpha complex instead (blocks by filtration value, `fromStream`; threshold ignored);
  * `method` is `seq`, `blocks-<threads>`, `chunks-<threads>-<columns>`, `packed` (`PackedRipserCohomologyEngine`, pairs
  * only), `packed-cocycles`, `verb` (alpha: `AlphaShapes`, then `Persistence`), `check` (every method agrees) or
  * `dump <file>` (the points as CSV, for C++ Ripser). Each run builds the matrices once and reduces `trials` times;
  * times are medians.
  */
object BlockParallelReduction:

  /** One dimension's cells in filtration order: `dim + 1` ascending vertices each, value, block. */
  final class Cells(val dim: Int, val vertices: Array[Int], val value: Array[Double], val block: Array[Int]):
    def size: Int = value.length

  /** The cells of dimensions `0 .. top` within `threshold`, in filtration order; blocks are numbered `0 until edges`.
    */
  def complex(n: Int, distance: (Int, Int) => Double, threshold: Double, top: Int): Vector[Cells] =
    val g = Generator(n, distance, threshold)
    val m = g.edgeCount
    val byBlock = sortIndices(
      m,
      (x, y) =>
        java.lang.Double.compare(g.edgeLength(x), g.edgeLength(y)) match
          case 0 =>
            if g.edgeA(x) != g.edgeA(y) then Integer.compare(g.edgeA(x), g.edgeA(y))
            else Integer.compare(g.edgeB(x), g.edgeB(y))
          case c => c
    )
    val rank = new Array[Int](m)
    for k <- 0 until m do rank(byBlock(k)) = k
    def ordered(layer: Layer): Cells =
      val w = layer.dim + 1
      val key = (i: Int) => rank(layer.root(i))
      var inOrder = true
      var i = 1
      while inOrder && i < layer.size do
        if key(i - 1) > key(i) then inOrder = false
        i += 1
      // Stable: within a block the generator's order is kept.
      val order =
        if inOrder then Array.range(0, layer.size)
        else sortIndices(layer.size, (x, y) => Integer.compare(key(x), key(y)))
      val vs = new Array[Int](layer.size * w)
      val values = new Array[Double](layer.size)
      val blocks = new Array[Int](layer.size)
      i = 0
      while i < layer.size do
        val o = order(i)
        System.arraycopy(layer.vertices, o * w, vs, i * w, w)
        values(i) = g.edgeLength(layer.root(o))
        blocks(i) = key(o)
        i += 1
      Cells(layer.dim, vs, values, blocks)
    val vertices = Cells(0, Array.range(0, n), new Array[Double](n), Array.fill(n)(-1))
    vertices +: Iterator.iterate(g.edges)(g.cofacets).take(top).map(ordered).toVector

  /** The cells of dimensions `0 .. top` of any filtered complex on vertices `0 until n`, ordered by value, then
    * dimension, then the stream's own order, with one block per filtration value. On points in general position the
    * cells of one value are one Vietoris-Rips edge block, or one interval of an alpha complex's radius function.
    */
  def fromStream(stream: LevelwiseSimplexStream[Int, Double], top: Int): Vector[Cells] =
    val levels = (0 to top).map(d => stream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toArray)
    val values = levels.map(_.map(stream.filtrationValue))
    val distinct = values.flatten.distinct.sorted.toArray
    levels.indices.toVector.map { d =>
      val level = levels(d)
      val rank = values(d).map(v => java.util.Arrays.binarySearch(distinct, v))
      val order = sortIndices(level.length, (x, y) => Integer.compare(rank(x), rank(y)))
      val vs = new Array[Int](level.length * (d + 1))
      for i <- level.indices do
        val sorted = level(order(i)).toSeq.sorted
        for t <- 0 to d do vs(i * (d + 1) + t) = sorted(t)
      Cells(d, vs, order.map(values(d)), if d == 0 then Array.fill(level.length)(-1) else order.map(rank))
    }

  /** The coboundary from `columns` (degree-d cells) to `rows` (degree-(d+1) cells) over F_p, in compressed columns:
    * column `j`'s entries are `start(j) until start(j + 1)` of `entryRow` (ascending) and `entryCoeff`.
    */
  final class Coboundary(
    val columns: Cells,
    val rows: Cells,
    val start: Array[Int],
    val entryRow: Array[Int],
    val entryCoeff: Array[Int]
  )

  def coboundary(n: Int, columns: Cells, rows: Cells, p: Int): Coboundary =
    val k = columns.dim + 1
    val binom = Array.tabulate(k + 1, n + 1)((q, v) => SimplexIndexing.binomial(v, q))
    val position = LongIntMap(columns.size)
    for j <- 0 until columns.size do
      var idx = 0L
      for t <- 0 until k do idx += binom(t + 1)(columns.vertices(j * k + t))
      position(idx) = j
    val facet = new Array[Int](rows.size * (k + 1))
    val start = new Array[Int](columns.size + 1)
    var t = 0
    while t < rows.size do
      var s = 0
      while s <= k do
        var idx = 0L
        var q = 1
        var u = 0
        while u <= k do
          if u != s then
            idx += binom(q)(rows.vertices(t * (k + 1) + u))
            q += 1
          u += 1
        val j = position(idx)
        facet(t * (k + 1) + s) = j
        start(j + 1) += 1
        s += 1
      t += 1
    for j <- 0 until columns.size do start(j + 1) += start(j)
    val fill = java.util.Arrays.copyOf(start, columns.size)
    val entryRow = new Array[Int](facet.length)
    val entryCoeff = new Array[Int](facet.length)
    t = 0
    while t < rows.size do
      var s = 0
      while s <= k do
        val j = facet(t * (k + 1) + s)
        entryRow(fill(j)) = t
        // Removing the vertex at position s has sign (-1)^s.
        entryCoeff(fill(j)) = if s % 2 == 0 then 1 else p - 1
        fill(j) += 1
        s += 1
      t += 1
    Coboundary(columns, rows, start, entryRow, entryCoeff)

  /** Per-thread merge buffers. */
  final class Scratch:
    var rows: Array[Int] = new Array[Int](64)
    var coeffs: Array[Int] = new Array[Int](64)
    def ensure(size: Int): Unit =
      if rows.length < size then
        rows = new Array[Int](Integer.highestOneBit(size) * 2)
        coeffs = new Array[Int](rows.length)

  /** One degree's columns as they are reduced: a column not yet changed is its slice of the coboundary. With
    * `cocycles`, also the reduction matrix (`V`, over column indices; a column never changed is itself).
    */
  final class Store(val cob: Coboundary, p: Int, cocycles: Boolean):
    private val size = cob.columns.size
    private val rows = new Array[Array[Int]](size)
    private val coeffs = new Array[Array[Int]](size)
    private val vRows = if cocycles then new Array[Array[Int]](size) else null
    private val vCoeffs = if cocycles then new Array[Array[Int]](size) else null
    private val inverse =
      val inv = new Array[Int](p)
      for a <- 1 until p; b <- 1 until p if a * b % p == 1 do inv(a) = b
      inv

    def pivot(j: Int): Int =
      val r = rows(j)
      if r != null then if r.length == 0 then -1 else r(0)
      else if cob.start(j) == cob.start(j + 1) then -1
      else cob.entryRow(cob.start(j))

    private def lead(j: Int): Int = if rows(j) != null then coeffs(j)(0) else cob.entryCoeff(cob.start(j))

    /** Column `j` and its reduction-matrix column, as (rows, coefficients). */
    def reduced(j: Int): (Array[Int], Array[Int]) =
      if rows(j) != null then (rows(j), coeffs(j))
      else
        (
          java.util.Arrays.copyOfRange(cob.entryRow, cob.start(j), cob.start(j + 1)),
          java.util.Arrays.copyOfRange(cob.entryCoeff, cob.start(j), cob.start(j + 1))
        )
    def cocycle(j: Int): (Array[Int], Array[Int]) =
      if vRows(j) != null then (vRows(j), vCoeffs(j)) else (Array(j), Array(1))

    // a + f * b over F_p into the scratch buffers; returns the length.
    private def merge(
      aR: Array[Int],
      aC: Array[Int],
      aFrom: Int,
      aTo: Int,
      bR: Array[Int],
      bC: Array[Int],
      bFrom: Int,
      bTo: Int,
      f: Int,
      s: Scratch
    ): Int =
      s.ensure(aTo - aFrom + bTo - bFrom)
      val outR = s.rows
      val outC = s.coeffs
      var i = aFrom
      var k = bFrom
      var t = 0
      while i < aTo || k < bTo do
        if k == bTo || (i < aTo && aR(i) < bR(k)) then
          outR(t) = aR(i); outC(t) = aC(i); t += 1; i += 1
        else if i == aTo || bR(k) < aR(i) then
          outR(t) = bR(k); outC(t) = f * bC(k) % p; t += 1; k += 1
        else
          val c = (aC(i) + f * bC(k)) % p
          if c != 0 then
            outR(t) = aR(i); outC(t) = c; t += 1
          i += 1; k += 1
      t

    /** Adds to column `j` the multiple of column `o` that cancels their common pivot. */
    def cancel(j: Int, o: Int, s: Scratch): Unit =
      val f = (p - lead(j)) * inverse(lead(o)) % p
      val jr = rows(j)
      val or = rows(o)
      val len = merge(
        if jr != null then jr else cob.entryRow,
        if jr != null then coeffs(j) else cob.entryCoeff,
        if jr != null then 0 else cob.start(j),
        if jr != null then jr.length else cob.start(j + 1),
        if or != null then or else cob.entryRow,
        if or != null then coeffs(o) else cob.entryCoeff,
        if or != null then 0 else cob.start(o),
        if or != null then or.length else cob.start(o + 1),
        f,
        s
      )
      rows(j) = java.util.Arrays.copyOf(s.rows, len)
      coeffs(j) = java.util.Arrays.copyOf(s.coeffs, len)
      if cocycles then
        val (vjR, vjC) = cocycle(j)
        val (voR, voC) = cocycle(o)
        val vLen = merge(vjR, vjC, 0, vjR.length, voR, voC, 0, voR.length, f, s)
        vRows(j) = java.util.Arrays.copyOf(s.rows, vLen)
        vCoeffs(j) = java.util.Arrays.copyOf(s.coeffs, vLen)

  /** How one degree's reduction went. */
  final class Stats(val degree: Int, val columns: Int, val cleared: Int):
    var nanos1, nanos2a, nanos2b: Long = 0L
    var adds1, adds2a, adds2b: Long = 0L
    // Phase-2b additions of a column phase 1 paired: only waiting for another deferred column made them sequential.
    var adds2bOfPhase1: Long = 0L
    // The longest chain of phase-2b additions in which each column waits for the deferred columns it adds: a bound
    // on how far any schedule doing these additions could shorten phase 2b.
    var criticalPath2b: Long = 0L
    var pairs1, deferred, finished2a: Int = 0
    def nanos: Long = nanos1 + nanos2a + nanos2b
    def adds: Long = adds1 + adds2a + adds2b

  enum Method:
    case Sequential
    // chunk <= 1: every edge block is its own unit.
    case Blocks(threads: Int, chunk: Int = 0)

  /** One degree reduced: `owner(r)` is the column whose pivot is row `r`, or -1. */
  final class Degree(val store: Store, val cleared: Array[Boolean], val owner: Array[Int], val stats: Stats):
    def cob: Coboundary = store.cob

  def reduce(
    cob: Coboundary,
    cleared: Array[Boolean],
    p: Int,
    method: Method,
    cocycles: Boolean,
    pool: ExecutorService
  ): Degree =
    val size = cob.columns.size
    val store = Store(cob, p, cocycles)
    val owner = Array.fill(cob.rows.size)(-1)
    val stats = Stats(cob.columns.dim, size, cleared.count(identity))
    method match
      case Method.Sequential =>
        val t0 = System.nanoTime()
        val s = Scratch()
        var adds = 0L
        var j = size - 1
        while j >= 0 do
          if !cleared(j) then
            var piv = store.pivot(j)
            while piv >= 0 && owner(piv) >= 0 do
              store.cancel(j, owner(piv), s)
              adds += 1
              piv = store.pivot(j)
            if piv >= 0 then owner(piv) = j
          j -= 1
        stats.nanos2b = System.nanoTime() - t0
        stats.adds2b = adds
      case Method.Blocks(threads, chunk) =>
        val t0 = System.nanoTime()
        val colBlock = cob.columns.block
        val rowBlock = cob.rows.block
        // Both are sorted by block, so the last entries are the largest.
        val blocks = 1 + math.max(
          if size == 0 then -1 else colBlock(size - 1),
          if cob.rows.size == 0 then -1 else rowBlock(cob.rows.size - 1)
        )
        // Units: consecutive blocks, merged until a unit has at least `chunk` columns.
        val unitOf = new Array[Int](blocks)
        if chunk <= 1 then for b <- 0 until blocks do unitOf(b) = b
        else
          val counts = new Array[Int](blocks)
          for j <- 0 until size do counts(colBlock(j)) += 1
          var u = 0
          var acc = 0
          for b <- 0 until blocks do
            unitOf(b) = u
            acc += counts(b)
            if acc >= chunk then
              u += 1
              acc = 0
        // Tasks: runs of whole units, about 16 per thread.
        val target = math.max(1, size / (threads * 16))
        val taskStart = IntBuffer()
        var j = 0
        while j < size do
          taskStart += j
          var k = j + 1
          while k < size && (k - j < target || unitOf(colBlock(k)) == unitOf(colBlock(k - 1))) do k += 1
          j = k
        taskStart += size
        val starts = taskStart.result
        val tasks = starts.length - 1
        val deferredOf = Array.fill(tasks)(IntBuffer())
        val paired1 = new Array[Boolean](size)
        def run(work: Int => Long): Long =
          val next = AtomicInteger(0)
          val jobs = (0 until threads).map(_ =>
            new Callable[Long]:
              def call(): Long =
                var total = 0L
                var t = next.getAndIncrement()
                while t >= 0 do
                  val r = work(t)
                  if r < 0 then t = -1
                  else
                    total += r
                    t = next.getAndIncrement()
                total
          )
          pool.invokeAll(jobs.asJava).asScala.map(_.get).sum
        // Phase 1: tasks in any order, each one's columns in reverse order. Rows of a unit are only ever written by
        // that unit's columns, so the tasks share nothing.
        val pairs1 = AtomicInteger(0)
        stats.adds1 = run { t =>
          if t >= tasks then -1L
          else
            val s = Scratch()
            var adds = 0L
            var pairs = 0
            var j = starts(t + 1) - 1
            while j >= starts(t) do
              if !cleared(j) then
                val unit = unitOf(colBlock(j))
                var go = true
                while go do
                  val piv = store.pivot(j)
                  if piv < 0 then go = false
                  else if unitOf(rowBlock(piv)) != unit then
                    deferredOf(t) += j
                    go = false
                  else if owner(piv) < 0 then
                    owner(piv) = j
                    paired1(j) = true
                    pairs += 1
                    go = false
                  else
                    store.cancel(j, owner(piv), s)
                    adds += 1
              j -= 1
            pairs1.addAndGet(pairs)
            adds
        }
        stats.pairs1 = pairs1.get
        val t1 = System.nanoTime()
        val deferred = (tasks - 1 to 0 by -1).iterator.flatMap(t => deferredOf(t).result).toArray
        stats.deferred = deferred.length
        // Phase 2a: each deferred column by the columns phase 1 paired, which no longer change; nothing is written to
        // `owner`, so the columns are independent.
        val slice = math.max(1, math.min(32, deferred.length / (threads * 8)))
        val slices = (deferred.length + slice - 1) / slice
        val finished = AtomicInteger(0)
        stats.adds2a = run { t =>
          if t >= slices then -1L
          else
            val s = Scratch()
            var adds = 0L
            for i <- t * slice until math.min(deferred.length, (t + 1) * slice) do
              val j = deferred(i)
              var piv = store.pivot(j)
              while piv >= 0 && owner(piv) >= 0 do
                store.cancel(j, owner(piv), s)
                adds += 1
                piv = store.pivot(j)
              if piv < 0 then finished.incrementAndGet()
            adds
        }
        stats.finished2a = finished.get
        val t2 = System.nanoTime()
        // Phase 2b: in order, against everything paired so far.
        val s = Scratch()
        var adds = 0L
        var addsOfPhase1 = 0L
        val path = new Array[Long](size)
        for j <- deferred do
          var piv = store.pivot(j)
          var own = 0L
          var waited = 0L
          while piv >= 0 && owner(piv) >= 0 do
            val o = owner(piv)
            if paired1(o) then addsOfPhase1 += 1 else waited = math.max(waited, path(o))
            store.cancel(j, o, s)
            adds += 1
            own += 1
            piv = store.pivot(j)
          if piv >= 0 then owner(piv) = j
          path(j) = waited + own
          stats.criticalPath2b = math.max(stats.criticalPath2b, path(j))
        stats.adds2b = adds
        stats.adds2bOfPhase1 = addsOfPhase1
        val t3 = System.nanoTime()
        stats.nanos1 = t1 - t0
        stats.nanos2a = t2 - t1
        stats.nanos2b = t3 - t2
    Degree(store, cleared, owner, stats)

  /** Persistent cohomology in degrees `0 .. maxDim`: degree 0 by union-find over the edges in order (its deaths clear
    * degree 1), then each degree reduced by `method`, its pivots clearing the next degree.
    */
  final class Result(val cells: Vector[Cells], val h0Deaths: Array[Boolean], val degrees: Vector[Degree]):
    /** Every bar of positive length as (degree, birth, death), sorted. */
    def bars: Vector[(Int, Double, Double)] =
      val edges = cells(1)
      val h0 = (0 until edges.size).collect { case e if h0Deaths(e) && edges.value(e) > 0 => (0, 0.0, edges.value(e)) }
      val components = cells(0).size - h0Deaths.count(identity)
      val essential0 = Vector.fill(components)((0, 0.0, Double.PositiveInfinity))
      val higher = degrees.flatMap { d =>
        val cob = d.cob
        val out = Vector.newBuilder[(Int, Double, Double)]
        val isOwner = new Array[Boolean](cob.columns.size)
        for r <- 0 until cob.rows.size if d.owner(r) >= 0 do
          val j = d.owner(r)
          isOwner(j) = true
          if cob.rows.value(r) > cob.columns.value(j) then
            out += ((cob.columns.dim, cob.columns.value(j), cob.rows.value(r)))
        for j <- 0 until cob.columns.size if !d.cleared(j) && !isOwner(j) do
          out += ((cob.columns.dim, cob.columns.value(j), Double.PositiveInfinity))
        out.result()
      }
      (h0.toVector ++ essential0 ++ higher).sorted

  def h0Deaths(cells: Vector[Cells]): Array[Boolean] =
    val edges = cells(1)
    val parent = Array.range(0, cells(0).size)
    def find(x0: Int): Int =
      var x = x0
      while parent(x) != x do
        parent(x) = parent(parent(x))
        x = parent(x)
      x
    val deaths = new Array[Boolean](edges.size)
    for e <- 0 until edges.size do
      val a = find(edges.vertices(2 * e))
      val b = find(edges.vertices(2 * e + 1))
      if a != b then
        parent(math.max(a, b)) = math.min(a, b)
        deaths(e) = true
    deaths

  def persistence(
    cells: Vector[Cells],
    cobs: Vector[Coboundary],
    p: Int,
    method: Method,
    cocycles: Boolean,
    pool: ExecutorService
  ): Result =
    val deaths = h0Deaths(cells)
    var cleared = deaths
    val degrees = cobs.map { cob =>
      val d = reduce(cob, cleared, p, method, cocycles, pool)
      cleared = d.owner.map(_ >= 0)
      d
    }
    Result(cells, deaths, degrees)

  def endpoint(e: BarcodeEndpoint[Double]): Double = e match
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  /** The packed Ripser engine's bars of positive length, as [[Result.bars]] lists them. */
  def packedBars(
    metricSpace: FiniteMetricSpace[Int],
    maxDim: Int,
    threshold: Double,
    p: Int
  ): Vector[(Int, Double, Double)] =
    val f = FiniteField(p)
    import f.given
    PackedRipserCohomologyEngine[f.Fp](metricSpace, maxDim, maxFiltrationValue = Some(threshold))
      .pairedCohomology(cocycles = false)
      .map(_._1)
      .filterNot(_.isZeroLength)
      .map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
      .toVector
      .sorted

  def points(dataset: String, n: Int, rng: Random): Array[Array[Double]] = dataset match
    case cube if cube.startsWith("cube") && cube.drop(4).nonEmpty && cube.drop(4).forall(_.isDigit) =>
      Array.fill(n)(Array.fill(cube.drop(4).toInt)(rng.nextDouble()))
    case "torus" =>
      Array.fill(n) {
        val (s, t) = (2 * math.Pi * rng.nextDouble(), 2 * math.Pi * rng.nextDouble())
        val noise = () => 0.03 * rng.nextGaussian()
        Array(
          (1 + 0.4 * math.cos(t)) * math.cos(s) + noise(),
          (1 + 0.4 * math.cos(t)) * math.sin(s) + noise(),
          0.4 * math.sin(t) + noise()
        )
      }
    case "sphere" =>
      Array.fill(n) {
        val v = Array.fill(3)(rng.nextGaussian())
        val r = math.sqrt(v.map(x => x * x).sum)
        v.map(x => x / r + 0.03 * rng.nextGaussian())
      }
    case other => throw IllegalArgumentException(s"unknown dataset $other: cube<d>, torus or sphere")

  def method(name: String): Method = name.split('-').toList match
    case List("seq")              => Method.Sequential
    case List("blocks", t)        => Method.Blocks(t.toInt)
    case List("chunks", t, chunk) => Method.Blocks(t.toInt, chunk.toInt)
    case _ => throw IllegalArgumentException(s"unknown method $name: seq, blocks-<threads>, chunks-<threads>-<columns>")

  private def median(xs: Seq[Long]): Double = xs.sorted.apply(xs.size / 2) / 1e6

  def main(args: Array[String]): Unit =
    val dataset = args(0)
    val n = args(1).toInt
    val maxDim = args(2).toInt
    val name = args(4)
    val trials = if args.length > 5 && name != "dump" then args(5).toInt else 5
    val seed = if args.length > 6 then args(6).toLong else 42L
    val p = if args.length > 7 then args(7).toInt else 17
    // `alpha:<cloud>`: the alpha complex of the cloud (threshold ignored) instead of its Vietoris-Rips complex.
    val alpha = dataset.startsWith("alpha:")
    val pts = points(dataset.stripPrefix("alpha:"), n, Random(seed))
    val metricSpace = EuclideanMetricSpace(pts)
    val threshold =
      if alpha then Double.PositiveInfinity
      else if args(3) == "mer" then metricSpace.minimumEnclosingRadius
      else args(3).toDouble
    println(s"$dataset n=$n maxDim=$maxDim threshold=$threshold p=$p method=$name")
    if name == "dump" then
      val out = java.io.PrintWriter(args(5))
      pts.foreach(pt => out.println(pt.mkString(",")))
      out.close()
      return
    if name == "packed" || name == "packed-cocycles" then
      val f = FiniteField(p)
      import f.given
      var count = 0
      val times = (1 to trials).map { _ =>
        val t0 = System.nanoTime()
        val engine = PackedRipserCohomologyEngine[f.Fp](metricSpace, maxDim, maxFiltrationValue = Some(threshold))
        count =
          if name == "packed" then engine.pairedCohomology(cocycles = false).count(!_._1.isZeroLength)
          else engine.persistentCohomology().size
        System.nanoTime() - t0
      }
      println(f"  bars $count, median ${median(times)}%.1f ms")
      return
    // The library's verb on the same alpha complex: the stream, then `Persistence` on it.
    def alphaStream() =
      given Epsilon = Epsilon(1e-5)
      AlphaShapes(pts)
    def verbBars(stream: LevelwiseSimplexStream[Int, Double]): Vector[(Int, Double, Double)] =
      Persistence(Truncated(stream, maxDim), characteristic = p).bars
        .map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
        .toVector
        .sorted
    if name == "verb" then
      val times = (1 to trials).map { _ =>
        val t0 = System.nanoTime()
        val stream = alphaStream()
        val t1 = System.nanoTime()
        val count = verbBars(stream).size
        val t2 = System.nanoTime()
        println(f"  alpha complex ${(t1 - t0) / 1e6}%.1f ms, Persistence ${(t2 - t1) / 1e6}%.1f ms, bars $count")
        t2 - t0
      }
      println(f"  median ${median(times)}%.1f ms")
      return
    val dist = new Array[Double](n * n)
    for i <- 0 until n; j <- 0 until n do dist(i * n + j) = metricSpace.distance(i, j)
    val distance: (Int, Int) => Double = (i, j) => dist(i * n + j)
    val t0 = System.nanoTime()
    lazy val stream = alphaStream()
    val cells = if alpha then fromStream(stream, maxDim + 1) else complex(n, distance, threshold, maxDim + 1)
    val t1 = System.nanoTime()
    val cobs = (1 to maxDim).map(d => coboundary(n, cells(d), cells(d + 1), p)).toVector
    val t2 = System.nanoTime()
    println(
      f"  cells ${cells.map(_.size).mkString(" ")}; generate ${(t1 - t0) / 1e6}%.1f ms, coboundaries ${(t2 - t1) / 1e6}%.1f ms"
    )
    if name == "check" then
      val pool = Executors.newFixedThreadPool(4)
      val seq = persistence(cells, cobs, p, Method.Sequential, cocycles = false, pool)
      for m <- Seq(Method.Blocks(1), Method.Blocks(4), Method.Blocks(4, 1000)) do
        val r = persistence(cells, cobs, p, m, cocycles = false, pool)
        val same = r.degrees.zip(seq.degrees).forall((a, b) => java.util.Arrays.equals(a.owner, b.owner))
        println(s"  $m: same pairs as sequential: $same")
      if alpha then println(s"  same bars as Persistence on the alpha complex: ${seq.bars == verbBars(stream)}")
      else
        println(
          s"  same bars as the packed Ripser engine: ${seq.bars == packedBars(metricSpace, maxDim, threshold, p)}"
        )
      pool.shutdown()
      return
    val m = method(name)
    val pool = Executors.newFixedThreadPool(m match
      case Method.Blocks(t, _) => t
      case Method.Sequential   => 1)
    var bars = 0
    val runs = (1 to trials).map { _ =>
      val start = System.nanoTime()
      val r = persistence(cells, cobs, p, m, cocycles = false, pool)
      val nanos = System.nanoTime() - start
      bars = r.bars.size
      (nanos, r.degrees.map(_.stats))
    }
    pool.shutdown()
    val times = runs.map(_._1)
    println(
      f"  bars $bars, reduction median ${median(times)}%.1f ms, min ${times.min / 1e6}%.1f ms; by degree (medians):"
    )
    for k <- runs.head._2.indices do
      val all = runs.map(_._2(k))
      val s = all.last
      def ms(f: Stats => Long) = median(all.map(f))
      println(
        f"  degree ${s.degree}: ${s.columns}%,d columns (${s.cleared}%,d cleared), ${ms(_.nanos)}%.1f ms: phase 1 ${ms(_.nanos1)}%.1f, 2a ${ms(_.nanos2a)}%.1f, 2b ${ms(_.nanos2b)}%.1f; " +
          f"additions ${s.adds}%,d = ${s.adds1}%,d + ${s.adds2a}%,d + ${s.adds2b}%,d (${s.adds2bOfPhase1}%,d of phase-1 columns, critical path ${s.criticalPath2b}%,d); phase-1 pairs ${s.pairs1}%,d, deferred ${s.deferred}%,d (${s.finished2a}%,d emptied in 2a)"
      )
