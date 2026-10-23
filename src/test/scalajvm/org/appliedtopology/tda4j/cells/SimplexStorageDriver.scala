package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq
import scala.compiletime.asMatchable
import scala.language.experimental.modularity

/** A/B of how a simplex's vertex set is stored, on the operations the library performs on `Simplex` objects: build from
  * a flat layer, hash-map lookup by a fresh equal key, the faces, lexicographic sort, iteration, and the memory a
  * million of them retain. ONE representation per JVM (`rep=`), so each call site is monomorphic, as in
  * `LayeredInorderBenchmarkDriver`.
  *
  * {{{
  * java -Xms6g -Xmx6g -XX:+UseParallelGC -cp $CP org.appliedtopology.tda4j.SimplexStorageDriver rep=treeset|sortedset-array|array|array-nohash|boxed-array|long [n=1000000]
  * }}}
  *
  * `treeset` is the library's `Simplex[Int]` (a `SortedSet[Int]`). `sortedset-array` is the same `Simplex[Int]` API on
  * a `SortedSet[Int]` over a sorted array (`IntArraySet`), the opaque type unchanged. `array` is a final class around a
  * sorted `Array[Int]` with a cached hash; `array-nohash` the same without the cached field; `boxed-array` a sorted
  * `Array[AnyRef]` of boxed `Integer`s compared through an `Ordering` (what a generic `Simplex[VertexT]` over an array
  * would be). `long` is the floor: the combinatorial-number-system index of the simplex in a `Long`, in an
  * open-addressing `Long -> Int` map (what the packed engines do), not a replacement for `Simplex`: it needs the vertex
  * count in advance and one word per size.
  *
  * Vertices are drawn from `0 until 300` (`C(300, 8)` fits a `Long`), so the maps hold distinct random simplices.
  */
object SimplexStorageDriver:

  private val Vertices = 300

  /** The operations a representation provides, on opaque handles. */
  abstract class Rep:
    def make(vs: Array[Int], from: Int, width: Int): AnyRef
    def face(s: AnyRef, i: Int): AnyRef
    def compare(a: AnyRef, b: AnyRef): Int
    def sum(s: AnyRef): Long

  /** The library's own `Simplex[Int]` API on a `SortedSet[Int]` that `build` makes from an ascending array: the
    * `SimplexOps` call sites are the same for a `TreeSet` and for [[IntArraySet]].
    */
  class SortedSetRep(build: Array[Int] => scala.collection.immutable.SortedSet[Int]) extends Rep:
    def make(vs: Array[Int], from: Int, width: Int): AnyRef =
      build(java.util.Arrays.copyOfRange(vs, from, from + width)).asSimplex.asInstanceOf[AnyRef]
    def face(s: AnyRef, i: Int): AnyRef =
      val spx = s.asInstanceOf[Simplex[Int]]
      val it = spx.iterator
      var k = 0
      while k < i do
        it.next()
        k += 1
      (spx - it.next()).asInstanceOf[AnyRef]
    private val ord = simplexOrdering[Int]
    def compare(a: AnyRef, b: AnyRef): Int = ord.compare(a.asInstanceOf[Simplex[Int]], b.asInstanceOf[Simplex[Int]])
    def sum(s: AnyRef): Long =
      var total = 0L
      s.asInstanceOf[Simplex[Int]].foreach(v => total += v)
      total

  /** A `SortedSet[Int]` on a sorted array, for natural order: what `Simplex[Int]` could be under its unchanged opaque
    * type. Its `hashCode` is `Set`'s (`MurmurHash3.setHash`), computed on the array, so it stays equal to a `TreeSet`'s
    * of the same elements, and `equals` accepts any `Set`.
    */
  final class IntArraySet(val a: Array[Int]) extends scala.collection.immutable.SortedSet[Int]:
    override def ordering: Ordering[Int] = Ordering.Int
    override def knownSize: Int = a.length
    override def size: Int = a.length
    override def isEmpty: Boolean = a.length == 0
    override def iterator: Iterator[Int] = new Iterator[Int]:
      private var i = 0
      def hasNext: Boolean = i < a.length
      def next(): Int =
        val v = a(i)
        i += 1
        v
    override def iteratorFrom(start: Int): Iterator[Int] =
      val at = java.util.Arrays.binarySearch(a, start)
      val from = if at >= 0 then at else -at - 1
      iterator.drop(from)
    override def contains(elem: Int): Boolean = java.util.Arrays.binarySearch(a, elem) >= 0
    override def incl(elem: Int): IntArraySet =
      val at = java.util.Arrays.binarySearch(a, elem)
      if at >= 0 then this
      else
        val ins = -at - 1
        val out = new Array[Int](a.length + 1)
        System.arraycopy(a, 0, out, 0, ins)
        out(ins) = elem
        System.arraycopy(a, ins, out, ins + 1, a.length - ins)
        new IntArraySet(out)
    override def excl(elem: Int): IntArraySet =
      val at = java.util.Arrays.binarySearch(a, elem)
      if at < 0 then this
      else
        val out = new Array[Int](a.length - 1)
        System.arraycopy(a, 0, out, 0, at)
        System.arraycopy(a, at + 1, out, at, a.length - 1 - at)
        new IntArraySet(out)
    override def rangeImpl(from: Option[Int], until: Option[Int]): IntArraySet =
      val lo = from.fold(0) { f =>
        val at = java.util.Arrays.binarySearch(a, f); if at >= 0 then at else -at - 1
      }
      val hi = until.fold(a.length) { u =>
        val at = java.util.Arrays.binarySearch(a, u); if at >= 0 then at else -at - 1
      }
      new IntArraySet(java.util.Arrays.copyOfRange(a, lo, math.max(lo, hi)))
    private var h = 0
    override def hashCode: Int =
      if h != 0 then h
      else
        import scala.util.hashing.MurmurHash3 as M
        var sum = 0
        var xor = 0
        var prod = 1
        var i = 0
        while i < a.length do
          val x = a(i)
          sum += x
          xor ^= x
          prod *= x | 1
          i += 1
        var r = M.setSeed
        r = M.mix(r, sum)
        r = M.mix(r, xor)
        r = M.mixLast(r, prod)
        h = M.finalizeHash(r, a.length)
        h
    override def equals(that: Any): Boolean = that.asMatchable match
      case o: IntArraySet             => java.util.Arrays.equals(a, o.a)
      case o: scala.collection.Set[?] =>
        o.size == a.length && o.forall(e =>
          e.asMatchable match
            case v: Int => contains(v);
            case _      => false
        )
      case _ => false

  /** `cacheHash`: a lazily computed 32-bit hash in the object, as `String` does. */
  final class ArrSimplex(val v: Array[Int], cacheHash: Boolean):
    private var h = 0
    override def hashCode: Int =
      if cacheHash && h != 0 then h
      else
        var x = v.length
        var i = 0
        while i < v.length do
          x = (x ^ v(i)) * 0x9e3779b1
          x ^= x >>> 15
          i += 1
        if cacheHash then h = x
        x
    override def equals(that: Any): Boolean = that.asMatchable match
      case o: ArrSimplex => java.util.Arrays.equals(v, o.v)
      case _             => false

  final class ArrayRep(cacheHash: Boolean) extends Rep:
    def make(vs: Array[Int], from: Int, width: Int): AnyRef =
      new ArrSimplex(java.util.Arrays.copyOfRange(vs, from, from + width), cacheHash)
    def face(s: AnyRef, i: Int): AnyRef =
      val a = s.asInstanceOf[ArrSimplex].v
      val out = new Array[Int](a.length - 1)
      System.arraycopy(a, 0, out, 0, i)
      System.arraycopy(a, i + 1, out, i, a.length - 1 - i)
      new ArrSimplex(out, cacheHash)
    def compare(a: AnyRef, b: AnyRef): Int =
      val x = a.asInstanceOf[ArrSimplex].v
      val y = b.asInstanceOf[ArrSimplex].v
      val n = math.min(x.length, y.length)
      var i = 0
      while i < n do
        if x(i) != y(i) then return Integer.compare(x(i), y(i))
        i += 1
      Integer.compare(x.length, y.length)
    def sum(s: AnyRef): Long =
      val a = s.asInstanceOf[ArrSimplex].v
      var total = 0L
      var i = 0
      while i < a.length do
        total += a(i)
        i += 1
      total

  /** A sorted array of boxed elements, compared through an `Ordering`: a generic `Simplex[VertexT]` on an array. */
  final class BoxedSimplex(val v: Array[AnyRef]):
    private var h = 0
    override def hashCode: Int =
      if h != 0 then h
      else
        var x = v.length
        var i = 0
        while i < v.length do
          x = (x ^ v(i).hashCode) * 0x9e3779b1
          x ^= x >>> 15
          i += 1
        h = x
        x
    override def equals(that: Any): Boolean = that.asMatchable match
      case o: BoxedSimplex =>
        o.v.length == v.length && {
          var i = 0
          while i < v.length && v(i).equals(o.v(i)) do i += 1
          i == v.length
        }
      case _ => false

  object BoxedArrayRep extends Rep:
    private val ord: Ordering[Any] = Ordering.Int.asInstanceOf[Ordering[Any]]
    def make(vs: Array[Int], from: Int, width: Int): AnyRef =
      val a = new Array[AnyRef](width)
      var i = 0
      while i < width do
        a(i) = Integer.valueOf(vs(from + i))
        i += 1
      new BoxedSimplex(a)
    def face(s: AnyRef, i: Int): AnyRef =
      val a = s.asInstanceOf[BoxedSimplex].v
      val out = new Array[AnyRef](a.length - 1)
      System.arraycopy(a, 0, out, 0, i)
      System.arraycopy(a, i + 1, out, i, a.length - 1 - i)
      new BoxedSimplex(out)
    def compare(a: AnyRef, b: AnyRef): Int =
      val x = a.asInstanceOf[BoxedSimplex].v
      val y = b.asInstanceOf[BoxedSimplex].v
      val n = math.min(x.length, y.length)
      var i = 0
      while i < n do
        val c = ord.compare(x(i), y(i))
        if c != 0 then return c
        i += 1
      Integer.compare(x.length, y.length)
    def sum(s: AnyRef): Long =
      var total = 0L
      val a = s.asInstanceOf[BoxedSimplex].v
      var i = 0
      while i < a.length do
        total += a(i).asInstanceOf[Integer].intValue
        i += 1
      total

  // ----- harness

  private val bean =
    java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def allocated: Long = bean.getThreadAllocatedBytes(Thread.currentThread.threadId)
  private var sink = 0L

  private def usedHeap(): Long =
    var i = 0
    while i < 4 do
      System.gc()
      i += 1
    val rt = Runtime.getRuntime
    rt.totalMemory - rt.freeMemory

  /** Median of the timed trials (after two untimed), in ns per `perOp`, and bytes allocated per `perOp`. */
  private def measure(label: String, perOp: Long, trials: Int = 7)(body: => Long): String =
    val times = new Array[Double](trials)
    var allocs = 0L
    var t = 0
    while t < trials do
      val a0 = allocated
      val t0 = System.nanoTime()
      sink += body
      val t1 = System.nanoTime()
      times(t) = (t1 - t0).toDouble / perOp
      if t == trials - 1 then allocs = allocated - a0
      t += 1
    val sorted = times.drop(2).sorted
    f"$label%-26s ${sorted(sorted.length / 2)}%9.1f ns   ${allocs.toDouble / perOp}%8.1f B   (min ${sorted.head}%.1f)"

  private def randomLayer(n: Int, width: Int, seed: Long): Array[Int] =
    val rnd = new java.util.Random(seed)
    val out = new Array[Int](n * width)
    val chosen = new Array[Int](width)
    var i = 0
    while i < n do
      var k = 0
      while k < width do
        var v = rnd.nextInt(Vertices)
        var j = 0
        while j < k do
          if chosen(j) == v then
            v = rnd.nextInt(Vertices)
            j = -1
          j += 1
        chosen(k) = v
        k += 1
      java.util.Arrays.sort(chosen)
      System.arraycopy(chosen, 0, out, i * width, width)
      i += 1
    out

  private def shuffle(a: Array[AnyRef], seed: Long): Unit =
    val rnd = new java.util.Random(seed)
    var i = a.length - 1
    while i > 0 do
      val j = rnd.nextInt(i + 1)
      val t = a(i)
      a(i) = a(j)
      a(j) = t
      i -= 1

  private def runObjects(rep: Rep, n: Int, width: Int): Unit =
    val layer = randomLayer(n, width, 20261023L)
    println(s"-- width $width, n = $n")
    // retained size of n objects (the array of references subtracted: 4 bytes each with compressed oops)
    val before = usedHeap()
    var objs = new Array[AnyRef](n)
    var i = 0
    while i < n do
      objs(i) = rep.make(layer, i * width, width)
      i += 1
    val after = usedHeap()
    println(
      f"retained                   ${(after - before).toDouble / n - 4}%9.1f B per simplex (excluding the reference)"
    )
    println(measure("build from flat layer", n) {
      var k = 0
      var s = 0L
      while k < n do
        s += rep.make(layer, k * width, width).hashCode
        k += 1
      s
    })
    // the map: n entries, probed with fresh equal keys in a shuffled order
    val probes = new Array[AnyRef](n)
    i = 0
    while i < n do
      probes(i) = rep.make(layer, i * width, width)
      i += 1
    shuffle(probes, 7L)
    def mapOf(size: Int): java.util.HashMap[AnyRef, Integer] =
      val m = new java.util.HashMap[AnyRef, Integer](size * 2)
      var k = 0
      while k < size do
        m.put(objs(k), Integer.valueOf(k))
        k += 1
      m
    println(measure("hash map put", n, trials = 5)(mapOf(n).size.toLong))
    val big = mapOf(n)
    println(measure("hash map get, fresh key", n) {
      var k = 0
      var s = 0L
      while k < n do
        val r = big.get(probes(k))
        if r != null then s += r
        k += 1
      s
    })
    val small = 20000
    val smallMap = mapOf(small)
    val smallProbes = java.util.Arrays.copyOf(objs, small)
    i = 0
    while i < small do
      smallProbes(i) = rep.make(layer, i * width, width)
      i += 1
    shuffle(smallProbes, 11L)
    println(measure(s"hash map get, ${small / 1000}k entries", small * 50L) {
      var pass = 0
      var s = 0L
      while pass < 50 do
        var k = 0
        while k < small do
          val r = smallMap.get(smallProbes(k))
          if r != null then s += r
          k += 1
        pass += 1
      s
    })
    val faces = n / 4
    val ring = new Array[AnyRef](1024)
    println(measure("faces (all, per simplex)", faces) {
      var k = 0
      var s = 0L
      while k < faces do
        var f = 0
        while f < width do
          ring((k + f) & 1023) = rep.face(objs(k), f)
          f += 1
        s += ring(k & 1023).hashCode
        k += 1
      s
    })
    println(measure("sort lexicographic", faces, trials = 5) {
      val copy = java.util.Arrays.copyOf(probes, faces)
      java.util.Arrays.sort(copy, (a: AnyRef, b: AnyRef) => rep.compare(a, b))
      rep.sum(copy(0))
    })
    println(measure("iterate (sum vertices)", n) {
      var k = 0
      var s = 0L
      while k < n do
        s += rep.sum(objs(k))
        k += 1
      s
    })
    objs = null

  // ----- the floor: a Long index per simplex

  private final class LongIntMap(capacity: Int):
    private val mask = Integer.highestOneBit(capacity * 2) * 2 - 1
    private val keys = new Array[Long](mask + 1)
    private val vals = new Array[Int](mask + 1)
    java.util.Arrays.fill(keys, -1L)
    private def slot(key: Long): Int =
      var h = key * 0x9e3779b97f4a7c15L
      h ^= h >>> 32
      h.toInt & mask
    def put(key: Long, v: Int): Unit =
      var i = slot(key)
      while keys(i) != -1L && keys(i) != key do i = (i + 1) & mask
      keys(i) = key
      vals(i) = v
    def get(key: Long): Int =
      var i = slot(key)
      while keys(i) != -1L do
        if keys(i) == key then return vals(i)
        i = (i + 1) & mask
      -1

  private def runLong(n: Int, width: Int): Unit =
    val layer = randomLayer(n, width, 20261023L)
    println(s"-- width $width, n = $n")
    val choose = Array.ofDim[Long](width + 1, Vertices + 1)
    var k = 0
    while k <= width do
      var v = 0
      while v <= Vertices do
        choose(k)(v) =
          if k == 0 then 1L
          else if v == 0 then 0L
          else choose(k)(v - 1) + choose(k - 1)(v - 1)
        v += 1
      k += 1
    def index(vs: Array[Int], from: Int, w: Int, skip: Int): Long =
      var s = 0L
      var pos = 0
      var j = 0
      while j < w do
        if j != skip then
          s += choose(pos + 1)(vs(from + j))
          pos += 1
        j += 1
      s
    val keys = new Array[Long](n)
    var i = 0
    while i < n do
      keys(i) = index(layer, i * width, width, -1)
      i += 1
    println(f"retained                   ${8.0}%9.1f B per simplex (excluding the array slot)")
    println(measure("build from flat layer", n) {
      var s = 0L
      var q = 0
      while q < n do
        s += index(layer, q * width, width, -1)
        q += 1
      s
    })
    def mapOf(size: Int): LongIntMap =
      val m = new LongIntMap(size)
      var q = 0
      while q < size do
        m.put(keys(q), q)
        q += 1
      m
    println(measure("hash map put", n, trials = 5)(mapOf(n).get(keys(0)).toLong))
    val big = mapOf(n)
    val order = new Array[Int](n)
    i = 0
    while i < n do
      order(i) = i
      i += 1
    val rnd = new java.util.Random(7L)
    i = n - 1
    while i > 0 do
      val j = rnd.nextInt(i + 1)
      val t = order(i)
      order(i) = order(j)
      order(j) = t
      i -= 1
    println(measure("hash map get, fresh key", n) {
      var s = 0L
      var q = 0
      while q < n do
        val p = order(q)
        s += big.get(index(layer, p * width, width, -1))
        q += 1
      s
    })
    val small = 20000
    val smallMap = mapOf(small)
    println(measure(s"hash map get, ${small / 1000}k entries", small * 50L) {
      var s = 0L
      var pass = 0
      while pass < 50 do
        var q = 0
        while q < small do
          s += smallMap.get(index(layer, order(q) % small * width, width, -1))
          q += 1
        pass += 1
      s
    })
    val faces = n / 4
    println(measure("faces (all, per simplex)", faces) {
      var s = 0L
      var q = 0
      while q < faces do
        var f = 0
        while f < width do
          s += index(layer, q * width, width, f)
          f += 1
        q += 1
      s
    })
    println(measure("sort lexicographic", faces, trials = 5) {
      val copy = java.util.Arrays.copyOf(keys, faces)
      java.util.Arrays.sort(copy)
      copy(0)
    })
    println(measure("iterate (sum vertices)", n) {
      var s = 0L
      var q = 0
      while q < n * width do
        s += layer(q)
        q += 1
      s
    })

  /** `IntArraySet` against the `TreeSet` the library uses today, on random sets: same hash, equal both ways, same
    * `incl`/`excl`/`iterator`/`iteratorFrom`/`contains`, a hash-map lookup across the two classes.
    */
  private def selfCheck(): Unit =
    val rnd = new java.util.Random(1L)
    var round = 0
    while round < 2000 do
      val size = rnd.nextInt(10)
      val elems = Array.fill(size)(rnd.nextInt(40) - 5)
      val tree = scala.collection.immutable.TreeSet.from(elems)
      val arr = IntArraySet(tree.toArray)
      assert(arr.hashCode == tree.hashCode, s"hash of $tree")
      assert(arr == tree && tree == arr, s"equality of $tree")
      assert(arr.toList == tree.toList)
      val v = rnd.nextInt(40) - 5
      assert(arr.contains(v) == tree.contains(v))
      assert((arr + v).toList == (tree + v).toList && (arr - v).toList == (tree - v).toList)
      assert(arr.iteratorFrom(v).toList == tree.iteratorFrom(v).toList)
      assert(
        arr.rangeFrom(v).toList == tree.rangeFrom(v).toList && arr.rangeUntil(v).toList == tree.rangeUntil(v).toList
      )
      val m = new java.util.HashMap[AnyRef, Integer]
      m.put(tree, 1)
      assert(m.get(arr) == 1, "lookup across classes")
      assert(arr != (tree + 100))
      round += 1
    println("self-check of IntArraySet against TreeSet passed")

  def main(args: Array[String]): Unit =
    val opts = args.map(a => a.takeWhile(_ != '=') -> a.dropWhile(_ != '=').drop(1)).toMap
    val n = opts.get("n").map(_.toInt).getOrElse(1000000)
    val rep = opts.getOrElse("rep", "treeset")
    if rep == "sortedset-array" then selfCheck()
    println(s"rep=$rep  java ${System.getProperty("java.version")}  maxMemory ${Runtime.getRuntime.maxMemory >> 20} MB")
    for width <- Seq(3, 5, 8) do
      rep match
        case "treeset" =>
          runObjects(SortedSetRep(vs => Simplex.from(ArraySeq.unsafeWrapArray(vs)).toSortedSet), n, width)
        case "sortedset-array" => runObjects(SortedSetRep(vs => IntArraySet(vs)), n, width)
        case "array"           => runObjects(ArrayRep(true), n, width)
        case "array-nohash"    => runObjects(ArrayRep(false), n, width)
        case "boxed-array"     => runObjects(BoxedArrayRep, n, width)
        case "long"            => runLong(n, width)
        case other             => sys.error(s"unknown rep $other")
    println(s"(sink $sink)")

/** The share of a real run that is `Simplex` objects: `Persistence(points, complex = AlphaShapes)` or `Cech` (streams
  * that build a `Simplex` per cell), timed per trial, to be run under `-XX:StartFlightRecording` and read with an
  * inclusive-frame aggregation (`.claude/WORKLOG-simplex-storage.md`).
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.SimplexShareDriver points.txt [complex=alpha|cech|radius] [maxDimension=2] [trials=4]
  * }}}
  */
object SimplexShareDriver:
  def main(args: Array[String]): Unit =
    val points = PaperBenchmarkDriver.readMatrix(args(0))
    val opts = args.drop(1).map(a => a.takeWhile(_ != '=') -> a.dropWhile(_ != '=').drop(1)).toMap
    val maxDim = opts.getOrElse("maxDimension", "2").toInt
    val trials = opts.getOrElse("trials", "4").toInt
    if opts.get("complex").contains("radius") then
      // `FiniteMetricSpace.minimumEnclosingRadius` alone: the all-pairs scan `Persistence` runs for a point cloud's scale
      for t <- 1 to trials do
        val t0 = System.nanoTime()
        val r = EuclideanMetricSpace(points).minimumEnclosingRadius
        println(f"trial $t: minimumEnclosingRadius = $r%.4f in ${(System.nanoTime() - t0) / 1e9}%.2f s")
      return
    val complex: PointCloudComplex = opts.getOrElse("complex", "alpha") match
      case "cech" => Cech
      case _      => AlphaShapes
    println(s"${points.length} points in R^${points.head.length}, $complex, degrees 0..$maxDim")
    for t <- 1 to trials do
      val t0 = System.nanoTime()
      val bars = Persistence(points, maxDimension = maxDim, complex = complex, characteristic = 17).bars.size
      println(f"trial $t: ${(System.nanoTime() - t0) / 1e9}%.2f s, $bars bars")
