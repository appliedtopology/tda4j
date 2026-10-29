package org.appliedtopology.tda4j

import OrderedCell.cellOrdering

import scala.collection.mutable
import scala.compiletime.asMatchable
import scala.util.hashing.MurmurHash3

/** Persistent cohomology for any cell type (simplices, cubes, simplicial-set generators): the reduction of the
  * coboundary matrix, following Bauer's Ripser (arXiv:1908.02518) without its Vietoris-Rips-specific shortcuts. Over a
  * field the barcode is the same as the homology barcode; what this engine adds is cocycle representatives, and it
  * works where the Ripser engines do not (Cech, alpha, cubical, simplicial sets, non-flag complexes).
  *
  * The coboundary is built by inverting the boundaries of the stream's cells, so the stream is read in full first.
  * There is no `maxDim`: the stream decides the top degree. For degrees `0 .. k`, pass a stream with cells up to
  * dimension `k + 1` (`LimitedCofaceSimplexStream(stream, k + 1)` for a coface stream) and ignore the degree-`(k + 1)`
  * bars.
  *
  * Representatives: every bar carries its V-column. For an essential bar it is a cocycle of the whole complex; for a
  * finite bar its coboundary is the bar's reduced column, which is born at or after the bar's death, so it is a cocycle
  * on the subcomplex before the death. `coboundaryOfChain` checks either.
  *
  * Simplices with `Int` vertices (every complex the library builds: alpha, Čech, witness, Dowker, explicit streams) are
  * reduced on flat arrays: each dimension's vertex tuples, a table from a tuple to its cell (the vertices side by side
  * in one `Long` when they fit, else a hash of them), and each coboundary block as compressed columns of signs. Only a
  * column that needs an addition becomes a `Chain`, and only the cells of the representatives returned are looked up
  * again. The bars and representatives are exactly those of reducing the cells themselves.
  *
  * @param packSimplices
  *   reduce simplices with `Int` vertices on flat arrays (see above). `false` reduces the cells themselves, with the
  *   same results, more slowly; [[reducesPacked]] tells which a stream gets.
  */
open class CellularCohomologyEngine[CellT: OrderedCell, CoefficientT: Field, FiltrationT: Ordering](
  val packSimplices: Boolean = true
):

  private var largest: Option[FiltrationT] = None

  /** The largest filtration value among the cells of the stream the last run read, `None` before any run: what a caller
    * needs of the whole stream without iterating it again.
    */
  def largestFiltrationValue: Option[FiltrationT] = largest

  private def readCells(theStream: CellStream[CellT, FiltrationT]): Vector[CellT] =
    largest = None
    theStream.iterator.toVector

  private def noteLargest(values: Iterator[FiltrationT]): Unit =
    val ordering = summon[Ordering[FiltrationT]]
    largest = values.reduceOption(ordering.max)

  /** Every bar of `stream`'s persistent cohomology with its representative (see the class doc); zero-length bars only
    * if `includeZeroLength`. Works one degree at a time, so memory is bounded by the largest coboundary block. For a
    * stream built for degrees `0..k` (its `homologyDegreeLimit`), bars of higher degree are left out, as in
    * [[persistentHomology]]: the cells of degree `k + 1` are all there to kill degree-`k` classes, and the ones that
    * kill none would each become a bar and a representative of their own.
    */
  def persistentCohomology(
    stream: => CellStream[CellT, FiltrationT],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    val theStream = stream
    lazy val cells = readCells(theStream)
    val limit = degreeLimit(theStream)
    packed(theStream, cells, limit) match
      case Some(p) => p.cocycleBars(limit, includeZeroLength)
      case None    =>
        PersistenceBar.dropZeroLength(paired(theStream, cells, limit)._1.map(_._1), includeZeroLength)

  // The top degree whose bars are the complex's homology: a stream built for degrees 0..k says k.
  private def degreeLimit(theStream: CellStream[CellT, FiltrationT]): Int = theStream match
    case s: StratifiedCellStream[?, ?] => s.homologyDegreeLimit.getOrElse(Int.MaxValue)
    case _                             => Int.MaxValue

  /** Whether `stream` is reduced on flat arrays (simplices with `Int` vertices, ascending under the vertex order, under
    * the library's own cell instance; see the class doc) rather than as cells. Reads the whole stream.
    */
  def reducesPacked(stream: => CellStream[CellT, FiltrationT]): Boolean =
    val theStream = stream
    packedLevels(theStream, readCells(theStream)).isDefined

  /** The same bars with '''cycles''' as representatives: the pairing is computed by cohomology, then only the boundary
    * columns of the death cells are reduced ([[Involution]]). A finite bar's cycle is the reduced boundary of its death
    * cell; an essential bar's is a cycle whose youngest cell is its birth cell. For a stream built for degrees `0..k`
    * (its `homologyDegreeLimit`), bars of higher degree are left out: they are not the complex's homology.
    */
  def persistentHomology(
    stream: => CellStream[CellT, FiltrationT],
    includeZeroLength: Boolean = false
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    val theStream = stream
    // A stream truncated for degrees 0..k leaves its top cells as "essential" classes of degree k + 1 that are not
    // the complex's homology; reducing their boundaries would redo all the work the involution skips. Essential pairs
    // are never pivots, so leaving them out changes no other cycle.
    val limit = degreeLimit(theStream)
    lazy val cells = readCells(theStream)
    packed(theStream, cells, limit)
      .map(_.cycleBars(limit, includeZeroLength))
      .getOrElse:
        cellHomology(theStream, cells, limit, includeZeroLength)

  private def cellHomology(
    theStream: CellStream[CellT, FiltrationT],
    cells: Vector[CellT],
    limit: Int,
    includeZeroLength: Boolean
  ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
    reduction(theStream, cells) match
      case None    => List.empty
      case Some(r) =>
        // The involution runs on the same cell numbers: within one dimension, a larger number is younger.
        val kept = r.entries.filter(_.dim <= limit)
        val numberOf = mutable.Map.empty[Int, mutable.HashMap[CellT, Int]]
        def boundary(i: Int, dim: Int): Seq[(Int, CoefficientT)] =
          val faces = numberOf.getOrElseUpdate(dim - 1, r.numbering(dim - 1))
          r.cellsByDim(dim)(i).boundary[CoefficientT].flatMap((face, c) => faces.get(face).map(j => (j, c)))
        val cycles = Involution.cycles[Int, CoefficientT](
          kept.map(e => Involution.Pair(e.dim, e.birth, Option.when(e.death >= 0)(e.death))),
          Ordering.Int.reverse,
          boundary
        )
        val youngestFirst = r.olderFirst.reverse
        val bars = kept.zip(cycles).toList.map { case (e, (cycle, _)) =>
          val cells = r.cellsByDim(e.dim)
          val rep = Chain.from(cycle.terms.map((i, c) => (cells(i), c)))(using youngestFirst)
          new PersistenceBar(e.dim, r.lower(e), r.upper(e), Some(rep))
        }
        PersistenceBar.dropZeroLength(bars, includeZeroLength)

  /** Every bar, zero-length ones included, with the cells that open and close it, and the order (oldest first within a
    * dimension) the pairing was computed under.
    */
  def pairedCohomology(
    stream: => CellStream[CellT, FiltrationT]
  ): (List[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])], Ordering[CellT]) =
    val theStream = stream
    lazy val cells = readCells(theStream)
    packed(theStream, cells, Int.MaxValue).map(_.pairs).getOrElse(paired(theStream, cells, Int.MaxValue))

  private def paired(
    theStream: CellStream[CellT, FiltrationT],
    cells: Vector[CellT],
    limit: Int
  ): (List[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])], Ordering[CellT]) =
    reduction(theStream, cells) match
      case None    => (List.empty, Ordering.by[CellT, Int](_ => 0))
      case Some(r) =>
        val paired = r.entries.iterator.filter(_.dim <= limit).toList.map { e =>
          val cells = r.cellsByDim(e.dim)
          val rep = Chain.from(e.cocycle.map((i, c) => (cells(i), c)))(using r.olderFirst)
          val death = Option.when(e.death >= 0)(r.cellsByDim(e.dim + 1)(e.death))
          (PersistenceBar(e.dim, r.lower(e), r.upper(e), Some(rep)), Involution.Pair(e.dim, cells(e.birth), death))
        }
        (paired, r.olderFirst)

  /** One bar of the reduction, over cell numbers: `birth` in dimension `dim`, `death` in `dim + 1` (`-1` if essential),
    * and the V-column of `birth` (its cocycle representative).
    */
  private final case class Entry(dim: Int, birth: Int, death: Int, cocycle: Seq[(Int, CoefficientT)])

  /** The reduction's result: the cells of each dimension, oldest first (a cell's number is its position), the bars in
    * the order they were found (degree ascending, youngest birth first), and that order on cells.
    */
  private final class Reduction(
    val cellsByDim: IndexedSeq[Vector[CellT]],
    val entries: IndexedSeq[Entry],
    val olderFirst: Ordering[CellT],
    cellFv: CellT => FiltrationT
  ):
    def numbering(d: Int): mutable.HashMap[CellT, Int] = numberCells(cellsByDim(d))
    def lower(e: Entry): BarcodeEndpoint[FiltrationT] = ClosedEndpoint(cellFv(cellsByDim(e.dim)(e.birth)))
    def upper(e: Entry): BarcodeEndpoint[FiltrationT] =
      if e.death < 0 then PositiveInfinity() else OpenEndpoint(cellFv(cellsByDim(e.dim + 1)(e.death)))

  /** Each cell's position in `cells`. */
  private def numberCells(cells: Vector[CellT]): mutable.HashMap[CellT, Int] =
    val m = new mutable.HashMap[CellT, Int](cells.length * 2, mutable.HashMap.defaultLoadFactor)
    var i = 0
    while i < cells.length do
      m(cells(i)) = i
      i += 1
    m

  // The coboundary reduction (Bauer's Ripser without its Vietoris-Rips shortcuts) over dense cell numbers: each
  // dimension's cells sorted oldest first under the engine's order, a cell's number its position. The reduction then
  // compares and hashes `Int`s instead of cells, with the same pivots, bars and V-columns as reducing over the cells
  // themselves (CohomologyNumberingSpec; WORKLOG-dense-numbering.md, WORKLOG-dense-cohomology.md).
  private def reduction(theStream: CellStream[CellT, FiltrationT], cells: Vector[CellT]): Option[Reduction] =
    val grouped: Map[Int, Vector[CellT]] = cells.groupBy(_.dim)
    if grouped.isEmpty then None
    else
      val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
      def cellFv(c: CellT): FiltrationT = fv.applyOrElse(c, (_: CellT) => theStream.smallest)
      noteLargest(cells.iterator.map(cellFv))

      // Ascending filtration value, then the stream's tie-break. Not `filtrationOrdering.reverse`: that would also flip
      // the tie-break. Only cells of one dimension are ever compared under it.
      val cohomologyOrdering: Ordering[CellT] =
        Ordering.by[CellT, FiltrationT](cellFv).orElse(theStream.filtrationOrdering)

      val topDim = grouped.keys.max
      // A d-cell forces its faces to exist, so a gap means a malformed stream; without this check a missing
      // dimension would silently reduce as an empty one.
      require(
        grouped.keySet == (0 to topDim).toSet,
        s"CellularCohomologyEngine requires a dimension-contiguous cell set (0..$topDim, no gaps), got " +
          s"dimensions ${grouped.keySet.toSeq.sorted.mkString(", ")}"
      )
      val cellsByDim: IndexedSeq[Vector[CellT]] = (0 to topDim).map(d => grouped(d).sorted(using cohomologyOrdering))

      val idOrdering: Ordering[Int] = Ordering.Int // ascending number = oldest first = a column's leading term
      val fr = summon[CoefficientT is Field]
      val entries = mutable.ArrayBuffer.empty[Entry]
      var numberOf = numberCells(cellsByDim(0))
      // cleared(i): cell i of the current dimension is the death of a bar one dimension down, so it opens no class.
      var cleared = new Array[Boolean](cellsByDim(0).length)

      for d <- 0 to topDim do
        val cells = cellsByDim(d)
        val up = if d < topDim then cellsByDim(d + 1) else Vector.empty

        // This dimension's coboundary block only: built here, dropped at the end of the iteration. Faces outside the
        // stream are skipped; they would never be reduced.
        val coboundary = Array.fill(cells.length)(mutable.ArrayBuffer.empty[(Int, CoefficientT)])
        var j = 0
        while j < up.length do
          up(j).boundary[CoefficientT].foreach { case (face, c) =>
            numberOf.get(face).foreach(i => coboundary(i) += ((j, c)))
          }
          j += 1

        val nextCleared = new Array[Boolean](up.length)
        val basis = mutable.HashMap.empty[Int, Chain[Int, CoefficientT]]
        val generators = mutable.HashMap.empty[Int, Seq[(Int, CoefficientT)]]
        var i = cells.length - 1
        while i >= 0 do // youngest first
          if !cleared(i) then
            val z = Chain.from(coboundary(i).toSeq)(using idOrdering)
            val (reduced, log) =
              Chain.reduceBy(z, basis, Chain.empty[Int, CoefficientT](using idOrdering))(using idOrdering)
            // V-column: i minus the V-columns of the pivots the reduction used, with their coefficients.
            val acc = mutable.HashMap(i -> fr.one)
            log.rawEntries.foreach { case (pivot, coeff) =>
              generators
                .getOrElse(
                  pivot,
                  throw new IllegalStateException(s"pivot $pivot has a basis entry but no generators entry")
                )
                .foreach { case (k, c) => acc(k) = fr.minus(acc.getOrElse(k, fr.zero), fr.times(coeff, c)) }
            }
            val vterms = acc.toSeq.filterNot((_, c) => fr.isEqual(c, fr.zero))
            if reduced.isZero() then entries += Entry(d, i, -1, vterms)
            else
              val pivot = reduced.leadingCell.get
              basis(pivot) = reduced
              generators(pivot) = vterms
              nextCleared(pivot) = true
              entries += Entry(d, i, pivot, vterms)
          i -= 1

        if d < topDim then numberOf = numberCells(up)
        cleared = nextCleared

      Some(Reduction(cellsByDim, entries.toIndexedSeq, cohomologyOrdering, cellFv))

  // Simplices with `Int` vertices on flat arrays (#193, WORKLOG-layered-inorder.md). The same reduction as `reduction`
  // above, column for column: the same cell numbers, the same pairs, and every `Chain` operation it performs is the one
  // `reduction` performs (a column that needs an addition is reduced by `Chain.reduceBy`, with the columns no addition
  // touched handed to it on demand), so the representatives agree term for term, over the reals too.

  /** The cells of dimension `dim`, oldest first: cell `i` has vertices `vertices((dim + 1) * i until (dim + 1) * (i +
    * 1))`, ascending, and value `values(i)`; `face` finds a cell's number from its vertices.
    */
  private final class PackedCells(
    val dim: Int,
    val size: Int,
    val vertices: Array[Int],
    // The cells and their values, or `null` for a level read from a stream's flat layers: then a cell is built from its
    // vertices when a representative asks for it, and a value is read from `doubles`.
    cells: Array[AnyRef],
    values: Array[AnyRef],
    doubles: Array[Double]
  ):
    private val width = dim + 1
    // Open addressing: a slot holds a cell's number + 1, or 0 when empty; built at the first lookup (nothing looks up
    // the top dimension's cells). A cell listed twice keeps its last number, as `numberCells` does.
    private var slots: Array[Int] = null
    private var mask = 0
    // When the vertices, all non-negative, fit side by side in a `Long` with a number to spare, a slot holds the cell's
    // vertices and its number + 1 in one `Long` (`keys`, 0 when empty): a lookup reads one slot, not a slot and then
    // the cell's vertices. `keyBits` is the bits of a vertex, `idBits` those of a number + 1.
    private var keys: Array[Long] = null
    private var keyBits = 0
    private var idBits = 0
    private var largestVertex = 0

    private def hash(src: Array[Int], from: Int, length: Int, skip: Int): Int =
      var h = 0x3c074a61
      var r = 0
      while r < length do
        if r != skip then h = MurmurHash3.mix(h, src(from + r))
        r += 1
      MurmurHash3.finalizeHash(h, width)

    private def matches(i: Int, src: Array[Int], from: Int, length: Int, skip: Int): Boolean =
      var k = i * width
      var r = 0
      var same = true
      while same && r < length do
        if r != skip then
          same = vertices(k) == src(from + r)
          k += 1
        r += 1
      same

    private def slotOf(src: Array[Int], from: Int, length: Int, skip: Int): Int =
      var slot = hash(src, from, length, skip) & mask
      while slots(slot) != 0 && !matches(slots(slot) - 1, src, from, length, skip) do slot = (slot + 1) & mask
      slot

    private def index(): Unit =
      var capacity = 4
      while capacity < 2 * size do capacity <<= 1
      mask = capacity - 1
      var smallest = 0
      var largest = 0
      var k = 0
      while k < size * width do
        smallest = math.min(smallest, vertices(k))
        largest = math.max(largest, vertices(k))
        k += 1
      val vertexBits = 32 - Integer.numberOfLeadingZeros(largest)
      val numberBits = 32 - Integer.numberOfLeadingZeros(size)
      if smallest == 0 && width * math.max(vertexBits, 1) + numberBits <= 63 then
        keyBits = math.max(vertexBits, 1)
        idBits = numberBits
        largestVertex = largest
        val table = new Array[Long](capacity)
        var i = 0
        while i < size do
          val key = keyOf(vertices, i * width, width, -1)
          var slot = keySlot(key)
          while table(slot) != 0L && (table(slot) >>> idBits) != key do slot = (slot + 1) & mask
          table(slot) = (key << idBits) | (i + 1).toLong
          i += 1
        keys = table
      else
        val table = new Array[Int](capacity)
        slots = table
        var i = 0
        while i < size do
          table(slotOf(vertices, i * width, width, -1)) = i + 1
          i += 1

    // The vertices of `src(from until from + length)` without the one at `from + skip`, `keyBits` each, the first on top;
    // -1 if one is not a vertex of this level.
    private def keyOf(src: Array[Int], from: Int, length: Int, skip: Int): Long =
      var key = 0L
      var r = 0
      while r < length && key >= 0L do
        if r != skip then
          val v = src(from + r)
          key = if v < 0 || v > largestVertex then -1L else (key << keyBits) | v.toLong
        r += 1
      key

    private def keySlot(key: Long): Int = ((key * 0x9e3779b97f4a7c15L) >>> 32).toInt & mask

    /** Builds the index `face` reads, which it would build at its first call: call it before lookups from several
      * threads.
      */
    def prepare(): Unit = if slots == null && keys == null then index()

    /** The number of the cell whose vertices are `src(from until from + dim + 2)` without the one at `from + skip`, or
      * -1 if there is none.
      */
    def face(src: Array[Int], from: Int, skip: Int): Int =
      if slots == null && keys == null then index()
      if keys != null then
        val key = keyOf(src, from, width + 1, skip)
        if key < 0L then -1
        else
          var slot = keySlot(key)
          var found = -1
          var entry = keys(slot)
          while entry != 0L && found < 0 do
            if (entry >>> idBits) == key then found = (entry & ((1L << idBits) - 1L)).toInt - 1
            else
              slot = (slot + 1) & mask
              entry = keys(slot)
          found
      else slots(slotOf(src, from, width + 1, skip)) - 1

    def cell(i: Int): CellT =
      if cells != null then cells(i).asInstanceOf[CellT]
      else FlatLayerStream.simplexOf(vertices, i * width, width).asInstanceOf[CellT]
    def value(i: Int): FiltrationT =
      if values != null then values(i).asInstanceOf[FiltrationT]
      else java.lang.Double.valueOf(doubles(i)).asInstanceOf[FiltrationT]

  // The order `reduction` puts each dimension in: ascending value, then the stream's tie-break, a stable sort of the
  // stream's own listing. A stream lists each dimension oldest first, so each run of equal values comes reversed, and
  // the sort is a reversal of each run that is checked strictly; anything else gets the stable sort.
  private def cohomologyOrder(
    cells: Array[AnyRef],
    values: Array[AnyRef],
    tieBreak: Ordering[CellT]
  ): Array[Int] =
    val byValue = summon[Ordering[FiltrationT]]
    def value(i: Int) = values(i).asInstanceOf[FiltrationT]
    def cell(i: Int) = cells(i).asInstanceOf[CellT]
    val n = cells.length
    val order = Array.range(0, n)
    def stableSort(from: Int, until: Int): Unit =
      val part = order.slice(from, until)
      scala.util.Sorting.stableSort(
        part,
        (a: Int, b: Int) =>
          val c = byValue.compare(value(a), value(b))
          (if c != 0 then c else tieBreak.compare(cell(a), cell(b))) < 0
      )
      System.arraycopy(part, 0, order, from, until - from)
    if (1 until n).exists(i => byValue.compare(value(i - 1), value(i)) > 0) then stableSort(0, n)
    else
      var start = 0
      while start < n do
        var end = start + 1
        while end < n && byValue.compare(value(start), value(end)) == 0 do end += 1
        if end - start > 1 then
          var a = start
          var b = end - 1
          while a < b do
            val t = order(a)
            order(a) = order(b)
            order(b) = t
            a += 1
            b -= 1
          if (start + 1 until end).exists(k => tieBreak.compare(cell(order(k - 1)), cell(order(k))) >= 0) then
            var k = start
            while k < end do
              order(k) = k
              k += 1
            stableSort(start, end)
        start = end
    order

  /** `cells` as levels, if they are simplices with `Int` vertices under the library's own cell instance, and form
    * dimensions `0..k` without gaps (anything else goes to `reduction`, which reports a gap).
    */
  private def packedLevels(
    theStream: CellStream[CellT, FiltrationT],
    cells: => Vector[CellT]
  ): Option[Array[PackedCells]] =
    if !packSimplices || !summon[CellT is OrderedCell].isInstanceOf[StandardSimplexBoundary] then None
    else flatLevels(theStream).orElse(levelsOfCells(theStream, cells))

  // The levels of a stream that holds its dimensions as flat arrays (`FlatLayerStream`), already in the order the
  // reduction takes them: no cell is built, and the stream is not read again to find its largest value. Only for
  // `Double` values under their natural order, which is what such a stream has.
  private def flatLevels(theStream: CellStream[CellT, FiltrationT]): Option[Array[PackedCells]] =
    val ordering = summon[Ordering[FiltrationT]].asInstanceOf[Ordering[Any]]
    val natural = ordering.compare(1.0, 2.0) < 0 && ordering.compare(2.0, 1.0) > 0 && ordering.compare(1.0, 1.0) == 0
    theStream match
      case flat: FlatLayerStream if natural =>
        flat.flatLayers.flatMap { layers =>
          val levels = layers.map(l => new PackedCells(l.dim, l.size, l.vertices, null, null, l.values)).toArray
          // A gap is for the cell reduction to report.
          if levels.isEmpty || levels.exists(_.size == 0) then None
          else
            // A layer ends in its largest value, the layers being ascending.
            largest = levels.iterator.map(l => l.value(l.size - 1)).reduceOption(summon[Ordering[FiltrationT]].max)
            Some(levels)
        }
      case _ => None

  private def levelsOfCells(
    theStream: CellStream[CellT, FiltrationT],
    cells: Vector[CellT]
  ): Option[Array[PackedCells]] =
    if cells.isEmpty then None
    else
      val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
      val cellsByDim = mutable.ArrayBuffer.empty[mutable.ArrayBuffer[AnyRef]]
      val valuesByDim = mutable.ArrayBuffer.empty[mutable.ArrayBuffer[AnyRef]]
      val verticesByDim = mutable.ArrayBuffer.empty[mutable.ArrayBuilder.ofInt]
      // Copies a cell's vertices; false unless they are `Int`s, strictly ascending (then a set's equality is its
      // tuple's, and its boundary's signs follow the tuple). On Scala.js a `Double` vertex with an integral value
      // matches `Int` too; it is the same number there, with the same order and equality, so the result is the same.
      def copied(spx: Simplex[Any], to: mutable.ArrayBuilder.ofInt): Boolean =
        val it = spx.iterator
        var previous = 0
        var first = true
        var ok = true
        while ok && it.hasNext do
          it.next().asMatchable match
            case v: Int if first || v > previous =>
              to += v
              previous = v
              first = false
            case _ => ok = false
        ok
      val it = cells.iterator
      var ok = true
      while ok && it.hasNext do
        val c = it.next()
        val d = c.dim
        if d < 0 then ok = false
        else
          while cellsByDim.size <= d do
            cellsByDim += mutable.ArrayBuffer.empty
            valuesByDim += mutable.ArrayBuffer.empty
            verticesByDim += new mutable.ArrayBuilder.ofInt
          ok = copied(c.asInstanceOf[Simplex[Any]], verticesByDim(d))
          cellsByDim(d) += c.asInstanceOf[AnyRef]
          valuesByDim(d) += fv.applyOrElse(c, (_: CellT) => theStream.smallest).asInstanceOf[AnyRef]
      noteLargest(valuesByDim.iterator.flatMap(_.iterator).map(_.asInstanceOf[FiltrationT]))
      if !ok || cellsByDim.exists(_.isEmpty) then None
      else
        val tieBreak = theStream.filtrationOrdering
        Some(Array.tabulate(cellsByDim.size) { d =>
          val (cs, vs, ts) = (cellsByDim(d).toArray, valuesByDim(d).toArray, verticesByDim(d).result())
          val order = cohomologyOrder(cs, vs, tieBreak)
          val width = d + 1
          val tuples = new Array[Int](ts.length)
          var i = 0
          while i < order.length do
            System.arraycopy(ts, order(i) * width, tuples, i * width, width)
            i += 1
          new PackedCells(d, order.length, tuples, order.map(cs(_)), order.map(vs(_)), null)
        })

  private def packed(
    theStream: CellStream[CellT, FiltrationT],
    cells: => Vector[CellT],
    limit: Int
  ): Option[PackedReduction] =
    packedLevels(theStream, cells).map(new PackedReduction(theStream, _, limit))

  /** The reduction on levels. Bars are numbered in the order they are found (degree ascending, youngest birth first),
    * as `reduction` lists them: bar `e` is born at cell `births(e)` of dimension `dims(e)` and dies at `deaths(e)` one
    * dimension up (-1: essential). A bar whose column needed no addition has the V-column of its own cell alone;
    * `cocycles` holds the others. Only the degrees up to `limit` are reduced: a bar of a higher degree needs no degree
    * below it, and the cells of the top dimension of a truncated stream are most of the stream.
    */
  private final class PackedReduction(
    theStream: CellStream[CellT, FiltrationT],
    levels: Array[PackedCells],
    limit: Int
  ):
    private val fr = summon[CoefficientT is Field]
    private val one = fr.one
    private val minusOne = fr.negate(fr.one)
    private val topDim = levels.length - 1
    private val fv: PartialFunction[CellT, FiltrationT] = theStream.filtrationValue
    private def cellFv(c: CellT): FiltrationT = fv.applyOrElse(c, (_: CellT) => theStream.smallest)
    private val olderFirst: Ordering[CellT] =
      Ordering.by[CellT, FiltrationT](cellFv).orElse(theStream.filtrationOrdering)

    private val dims = mutable.ArrayBuilder.ofInt()
    private val births = mutable.ArrayBuilder.ofInt()
    private val deaths = mutable.ArrayBuilder.ofInt()
    private val cocycles = mutable.HashMap.empty[Int, Seq[(Int, CoefficientT)]]
    // youngestFacet(d)(j): the largest number among the faces of cell j of dimension d (-1 if none is in the stream).
    private val youngestFacet: Array[Array[Int]] = new Array[Array[Int]](levels.length)
    private var count = 0

    private def found(dim: Int, birth: Int, death: Int, cocycle: Option[Seq[(Int, CoefficientT)]] = None): Unit =
      dims += dim
      births += birth
      deaths += death
      cocycle.foreach(cocycles(count) = _)
      count += 1

    // The coboundary block of dimension `d`: column i (a cell of `d` not cleared) is rows(offsets(i) until
    // offsets(i + 1)), cofacet numbers ascending, each as `2 * number + (1 if its sign is -1)`. Records the youngest
    // facet of every cell one dimension up.
    private def coboundaries(d: Int, cleared: Array[Boolean]): (Array[Int], Array[Int]) =
      val (lower, upper) = (levels(d), levels(d + 1))
      val width = d + 2
      val faces = new Array[Int](upper.size * width)
      val offsets = new Array[Int](lower.size + 1)
      val youngest = new Array[Int](upper.size)
      lower.prepare() // before the lookups below share it
      // The faces of every cell above, found independently of each other (the lookups only read the index) ...
      Parallel.ranges(upper.size, 2048) { (from, until) =>
        var j = from
        while j < until do
          var y = -1
          var r = 0
          while r < width do
            val f = lower.face(upper.vertices, j * width, r)
            faces(j * width + r) = f
            if f > y then y = f
            r += 1
          youngest(j) = y
          j += 1
      }
      youngestFacet(d + 1) = youngest
      // ... and counted per column, then written into their columns. A task takes a range of columns and reads every
      // face in order, so the tasks write to disjoint places and each column lists its cofacets in ascending order,
      // however many tasks there are (a pass that reads everything costs far less than scattering writes from one).
      val parts = if faces.length < (1 << 12) then 1 else math.min(Parallel.threads, 8)
      val bounds = Array.tabulate(parts + 1)(p => (lower.size.toLong * p / parts).toInt)
      Parallel.tasks(parts) { p =>
        val (low, high) = (bounds(p), bounds(p + 1))
        var j = 0
        while j < faces.length do
          val f = faces(j)
          if f >= low && f < high && !cleared(f) then offsets(f + 1) += 1
          j += 1
      }
      var i = 0
      while i < lower.size do
        offsets(i + 1) += offsets(i)
        i += 1
      val rows = new Array[Int](offsets(lower.size))
      val next = offsets.clone()
      Parallel.tasks(parts) { p =>
        val (low, high) = (bounds(p), bounds(p + 1))
        var j = 0
        while j < upper.size do
          var r = 0
          while r < width do
            val f = faces(j * width + r)
            if f >= low && f < high && !cleared(f) then
              rows(next(f)) = (j << 1) | (r & 1)
              next(f) += 1
            r += 1
          j += 1
      }
      (offsets, rows)

    locally:
      val idOrdering: Ordering[Int] = Ordering.Int // ascending number = oldest first = a column's leading term
      var cleared = new Array[Boolean](levels(0).size)
      for d <- 0 to math.min(topDim, limit) do
        val size = levels(d).size
        val (offsets, rows) =
          if d < topDim then coboundaries(d, cleared) else (new Array[Int](size + 1), Array.empty[Int])
        val upSize = if d < topDim then levels(d + 1).size else 0
        // owner(j): the column whose pivot is row j, or -1. Columns that needed an addition also keep their reduced
        // column and V-column, under their pivot; the others are their own coboundary and their own cell.
        val owner = Array.fill(upSize)(-1)
        val basis = mutable.HashMap.empty[Int, Chain[Int, CoefficientT]]
        val generators = mutable.HashMap.empty[Int, Seq[(Int, CoefficientT)]]
        def column(i: Int): Seq[(Int, CoefficientT)] =
          (offsets(i) until offsets(i + 1)).map(k => (rows(k) >>> 1, if (rows(k) & 1) == 0 then one else minusOne))
        // A column no addition touched, as `reduction` stores it.
        def untouched(pivot: Int): Option[Chain[Int, CoefficientT]] =
          Option.when(owner(pivot) >= 0)(
            Chain.from(column(owner(pivot)).map((j, c) => (j, fr.plus(fr.zero, c))))(using idOrdering)
          )
        val nextCleared = new Array[Boolean](upSize)
        var i = size - 1
        while i >= 0 do // youngest first
          if !cleared(i) then
            if offsets(i) == offsets(i + 1) then found(d, i, -1)
            else if owner(rows(offsets(i)) >>> 1) < 0 then
              val pivot = rows(offsets(i)) >>> 1
              owner(pivot) = i
              nextCleared(pivot) = true
              found(d, i, pivot)
            else
              val z = Chain.from(column(i))(using idOrdering)
              val (reduced, log) =
                Chain.reduceBy(z, basis, Chain.empty[Int, CoefficientT](using idOrdering), untouched)(using idOrdering)
              val acc = mutable.HashMap(i -> fr.one)
              log.rawEntries.foreach { case (pivot, coeff) =>
                generators
                  .getOrElse(pivot, Seq((owner(pivot), fr.one)))
                  .foreach { case (k, c) => acc(k) = fr.minus(acc.getOrElse(k, fr.zero), fr.times(coeff, c)) }
              }
              val vterms = acc.toSeq.filterNot((_, c) => fr.isEqual(c, fr.zero))
              if reduced.isZero() then found(d, i, -1, Some(vterms))
              else
                val pivot = reduced.leadingCell.get
                basis(pivot) = reduced
                generators(pivot) = vterms
                owner(pivot) = i
                nextCleared(pivot) = true
                found(d, i, pivot, Some(vterms))
          i -= 1
        cleared = nextCleared

    private val barDims = dims.result()
    private val barBirths = births.result()
    private val barDeaths = deaths.result()

    private def cocycle(e: Int): Seq[(Int, CoefficientT)] = cocycles.getOrElse(e, Seq((barBirths(e), fr.one)))
    private def lower(e: Int): BarcodeEndpoint[FiltrationT] = ClosedEndpoint(levels(barDims(e)).value(barBirths(e)))
    private def upper(e: Int): BarcodeEndpoint[FiltrationT] =
      if barDeaths(e) < 0 then PositiveInfinity() else OpenEndpoint(levels(barDims(e) + 1).value(barDeaths(e)))
    private def isZeroLength(e: Int): Boolean =
      barDeaths(e) >= 0 &&
        summon[Ordering[FiltrationT]]
          .equiv(levels(barDims(e)).value(barBirths(e)), levels(barDims(e) + 1).value(barDeaths(e)))

    private def cocycleChain(e: Int): Chain[CellT, CoefficientT] =
      val level = levels(barDims(e))
      Chain.from(cocycle(e).map((i, c) => (level.cell(i), c)))(using olderFirst)

    def pairs
      : (List[(PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]], Involution.Pair[CellT])], Ordering[CellT]) =
      val paired = List.tabulate(count) { e =>
        val (dim, level) = (barDims(e), levels(barDims(e)))
        val death = Option.when(barDeaths(e) >= 0)(levels(dim + 1).cell(barDeaths(e)))
        (
          PersistenceBar(dim, lower(e), upper(e), Some(cocycleChain(e))),
          Involution.Pair(dim, level.cell(barBirths(e)), death)
        )
      }
      (paired, olderFirst)

    def cocycleBars(
      limit: Int,
      includeZeroLength: Boolean
    ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
      (0 until count).iterator
        .filter(e => barDims(e) <= limit && (includeZeroLength || !isZeroLength(e)))
        .map(e => PersistenceBar(barDims(e), lower(e), upper(e), Some(cocycleChain(e))))
        .toList

    // The faces of cell `j` of dimension `dim` that are in the stream, with their signs, as `boundary` lists them.
    private def boundary(dim: Int, j: Int): Seq[(Int, CoefficientT)] =
      val width = dim + 1
      val vertices = levels(dim).vertices
      (0 until width).flatMap { r =>
        val f = levels(dim - 1).face(vertices, j * width, r)
        Option.when(f >= 0)((f, if r % 2 == 0 then fr.one else fr.negate(fr.one)))
      }

    /** The bars of degree at most `limit` with cycle representatives: `Involution.cycles` on cell numbers, where a
      * death cell whose youngest face is its birth cell needs no reduction, and only the cycles returned are built.
      */
    def cycleBars(
      limit: Int,
      includeZeroLength: Boolean
    ): List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]] =
      given youngestFirst: Ordering[Int] = Ordering.Int.reverse
      val chainRM = summon[Chain[Int, CoefficientT] is RingModule]
      import chainRM.*
      val kept = (0 until count).filter(e => barDims(e) <= limit)
      // pairedBirth(d)(t): the birth paired with death cell t of dimension d, deathOf(d)(s) the death paired with birth
      // cell s of dimension d (-1 for none), over the kept bars.
      val pairedBirth = levels.map(level => Array.fill(level.size)(-1))
      val deathOf = levels.map(level => Array.fill(level.size)(-1))
      for e <- kept if barDeaths(e) >= 0 do
        pairedBirth(barDims(e) + 1)(barDeaths(e)) = barBirths(e)
        deathOf(barDims(e))(barBirths(e)) = barDeaths(e)
      // Per death dimension, as in `Involution.cycles`: the columns that needed a reduction (reduced and V-column, under
      // their birth cell); `processed(d)`: death cells of dimension d below it have been reduced.
      val basisByDim = mutable.Map.empty[Int, mutable.Map[Int, Chain[Int, CoefficientT]]]
      val generatorsByDim = mutable.Map.empty[Int, mutable.Map[Int, Chain[Int, CoefficientT]]]
      val processed = Array.fill(levels.length)(0)
      def trivialDeath(d: Int, sigma: Int): Int =
        val tau = deathOf(d - 1)(sigma)
        if tau >= 0 && tau < processed(d) && youngestFacet(d)(tau) == sigma then tau else -1
      def unreduced(d: Int, tau: Int): Chain[Int, CoefficientT] =
        val c = Chain.from(boundary(d, tau).map((f, x) => (f, fr.plus(fr.zero, x))))
        c.collapseAll()
        c

      def reduce(z: Chain[Int, CoefficientT], seed: Int, d: Int): (Chain[Int, CoefficientT], Chain[Int, CoefficientT]) =
        val basis = basisByDim.getOrElseUpdate(d, mutable.Map.empty)
        val generators = generatorsByDim.getOrElseUpdate(d, mutable.Map.empty)
        def fallback(sigma: Int): Option[Chain[Int, CoefficientT]] =
          val tau = trivialDeath(d, sigma)
          Option.when(tau >= 0)(unreduced(d, tau))
        val (reduced, log) = Chain.reduceBy(z, basis, Chain.empty, fallback)
        val vcol = log.rawEntries.foldLeft(Chain(seed)) { case (acc, (pivot, coeff)) =>
          acc - coeff ⊠ generators.getOrElse(
            pivot, {
              val tau = trivialDeath(d, pivot)
              if tau < 0 then
                throw new IllegalStateException(s"Involution: pivot $pivot has a reduced column but no V-column")
              val g = Chain[Int, CoefficientT](tau)
              g.collapseAll()
              g
            }
          )
        }
        vcol.collapseAll()
        reduced.collapseAll()
        (reduced, vcol)

      // Each death dimension reads and writes its own tables only (`basisByDim(d)`, `generatorsByDim(d)`, `processed(d)`),
      // so the dimensions are reduced at the same time; the maps and the face indexes are made first, here.
      for d <- 1 to topDim do
        basisByDim.getOrElseUpdate(d, mutable.Map.empty)
        generatorsByDim.getOrElseUpdate(d, mutable.Map.empty)
        levels(d - 1).prepare()
      Parallel.foreach((1 to topDim).toVector) { d =>
        var tau = 0
        while tau < levels(d).size do
          val sigma = pairedBirth(d)(tau)
          processed(d) = tau
          if sigma >= 0 && youngestFacet(d)(tau) != sigma then
            val (reduced, vcol) = reduce(Chain.from(boundary(d, tau)), tau, d)
            reduced.leadingCell match
              case Some(pivot) if pivot == sigma =>
                basisByDim(d)(sigma) = reduced
                generatorsByDim(d)(sigma) = vcol
              case other =>
                throw new IllegalStateException(
                  s"Involution: the boundary of $tau reduces to pivot $other, not to its paired birth cell $sigma " +
                    "(the pairing and the order disagree)"
                )
          tau += 1
        processed(d) = Int.MaxValue
      }

      val youngestCellFirst = olderFirst.reverse
      kept.iterator
        .filter(e => includeZeroLength || !isZeroLength(e))
        .map { e =>
          val (dim, sigma, tau) = (barDims(e), barBirths(e), barDeaths(e))
          val cycle: Seq[(Int, CoefficientT)] =
            if tau >= 0 then
              basisByDim.get(dim + 1).flatMap(_.get(sigma)) match
                case Some(reduced) => reduced.terms
                case None          =>
                  boundary(dim + 1, tau).map((f, x) => (f, fr.plus(fr.zero, x))).sortBy(_._1)(using youngestFirst)
            else if dim == 0 then Chain[Int, CoefficientT](sigma).terms
            else
              val (reduced, vcol) = reduce(Chain.from(boundary(dim, sigma)), sigma, dim)
              if !reduced.isZero() then
                throw new IllegalStateException(
                  s"Involution: the boundary of the essential cell $sigma does not reduce to zero"
                )
              vcol.terms
          val level = levels(dim)
          val rep = Chain.from(cycle.map((i, c) => (level.cell(i), c)))(using youngestCellFirst)
          new PersistenceBar(dim, lower(e), upper(e), Some(rep))
        }
        .toList

  /** The coboundary of `chain`, a chain of dimension-`d` cells, computed against `cofacets`, the dimension-`(d + 1)`
    * cells to consider. For checking representatives: `coboundaryOfChain(rep, cellsOfDimension(d + 1)).isZero()`.
    * Requires every cell of `chain` to have the same dimension `d` and every cofacet dimension `d + 1`.
    */
  def coboundaryOfChain(
    chain: Chain[CellT, CoefficientT],
    cofacets: IterableOnce[CellT]
  ): Chain[CellT, CoefficientT] =
    val fr = summon[CoefficientT is Field]
    // groupMapReduce, not .toMap: rawEntries can hold duplicate (cell, coeff) pairs for one cell (deferred
    // arithmetic, e.g. `a + a`), and .toMap would silently keep only one of them.
    val chainMap: Map[CellT, CoefficientT] = chain.rawEntries.groupMapReduce(_._1)(_._2)(fr.plus)
    require(
      chainMap.keys.map(_.dim).toSet.sizeIs <= 1,
      s"coboundaryOfChain requires a homogeneous chain (every cell the same dimension), got dimensions " +
        s"${chainMap.keys.map(_.dim).toSet.toSeq.sorted.mkString(", ")}"
    )
    val chainDim: Option[Int] = chainMap.keys.headOption.map(_.dim)
    val terms = mutable.ArrayBuffer.empty[(CellT, CoefficientT)]
    cofacets.iterator.foreach { cof =>
      require(
        chainDim.forall(_ + 1 == cof.dim),
        s"coboundaryOfChain requires every cofacet to be exactly one dimension above the chain " +
          s"(expected dimension ${chainDim.map(_ + 1)}, got a cofacet of dimension ${cof.dim})"
      )
      cof.boundary[CoefficientT].foreach { case (face, coeff) =>
        chainMap.get(face).foreach { chainCoeff =>
          terms += ((cof, fr.times(chainCoeff, coeff)))
        }
      }
    }
    Chain.from(terms.toSeq)
