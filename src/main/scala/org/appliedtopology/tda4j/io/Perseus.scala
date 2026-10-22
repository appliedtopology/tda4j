package org.appliedtopology.tda4j

/** Perseus's cubical toplex (dense grid) input format and its persistence-interval output format, as read by GUDHI.
  *
  * Perseus lists the first axis fastest, the opposite of [[CubicalImage.fromFlatArray]]; the readers and writers
  * convert. A value of `-1` (a missing cube) is read as `NaN`: masked, not in the complex, in either direction.
  * Periodic boundaries (negative sizes, a GUDHI extension) are refused.
  *
  * Perseus's simplicial toplex formats (`simtop` and `nmfsimtop`, as described in Perseus's manual) are read and
  * written as lists of facets with birth times; [[toplexStream]] makes the filtered complex of such a list.
  */
object Perseus:

  /** The shape and values of a cubical toplex file, converted to the axis order of [[CubicalImage.fromFlatArray]], with
    * `-1` read as `NaN` (masked). Format: the dimension `d`, `d` positive sizes, then the values with the first axis
    * fastest.
    */
  def parseCubicalImageData(text: String, origin: String = "input"): (IndexedSeq[Int], IndexedSeq[Double]) =
    val tokens = text.linesIterator.flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)
    def next(): String =
      require(tokens.hasNext, s"$origin: the file ends before the grid is complete")
      tokens.next()
    val d = next().toInt
    val sizes = new Array[Int](d)
    var i = 0
    while i < d do
      sizes(i) = next().toInt
      i += 1
    require(
      sizes.forall(_ > 0),
      s"periodic boundaries (a negative grid size) are not supported by CubicalGridStream: sizes = ${sizes.toSeq}"
    )
    val n = sizes.product
    val raw = new Array[Double](n)
    i = 0
    while i < n do
      val v = next().toDouble
      raw(i) = if v == -1.0 then Double.NaN else v
      i += 1
    require(!tokens.hasNext, s"$origin: the file has values beyond the ${sizes.mkString(" x ")} grid it declares")
    (sizes.reverse.toIndexedSeq, raw.toIndexedSeq)

  def readCubicalImageData(path: String): (IndexedSeq[Int], IndexedSeq[Double]) =
    parseCubicalImageData(FileAccess.readText(path), path)

  /** The toplex of `path` as a grid; with `nanMask = false` a missing cube (`-1`, read as NaN) is an error. */
  def readCubicalToplex(path: String, sublevel: Boolean = true, nanMask: Boolean = true): CubicalGridStream =
    val (shape, flatValues) = readCubicalImageData(path)
    CubicalImage.fromFlatArray(shape, flatValues, sublevel, nanMask = nanMask)

  /** `shape`/`flatValues` in `CubicalImage.fromFlatArray`'s own last-axis-fastest convention -- reversed internally to
    * Perseus's own first-axis-fastest convention before writing. `Double.NaN` (masked) is written as `-1`. An infinite
    * value is refused: the format has none, and `-1` would read back as a mask.
    */
  def writeCubicalToplex(path: String, shape: IndexedSeq[Int], flatValues: IndexedSeq[Double]): Unit =
    FileAccess.writeText(path, formatCubicalToplex(shape, flatValues))

  def formatCubicalToplex(shape: IndexedSeq[Int], flatValues: IndexedSeq[Double]): String =
    require(flatValues.size == shape.product, "flatValues size must equal the product of shape")
    require(
      !flatValues.exists(_.isInfinite),
      "Perseus's cubical format has no infinite value: write a missing cell as NaN (it becomes -1), or a finite value"
    )
    val sizes = shape.reverse
    val lines = Iterator(shape.size.toString) ++ sizes.iterator.map(_.toString) ++
      flatValues.iterator.map(v => if v.isNaN then "-1" else v.toString)
    lines.map(_ + "\n").mkString

  private def endpointToStep(e: BarcodeEndpoint[Double]): Long = e match
    case ClosedEndpoint(v)  => math.round(v)
    case OpenEndpoint(v)    => math.round(v)
    case NegativeInfinity() =>
      throw new IllegalArgumentException("cannot write a -infinity endpoint in Perseus's integer-step format")
    case PositiveInfinity() =>
      throw new IllegalArgumentException("a +infinity endpoint should already have been handled as essential (-1)")

  /** Perseus's own output convention: one file per dimension (`<pathPrefix>_<dim>.txt`), two whitespace-separated
    * integer columns per line, `birth death` -- a `death` of `-1` means the class is essential. Perseus's own
    * birth/death values are filtration STEP indices (integers), not raw filtration values -- this writer rounds
    * whatever `Double`s it's given to their nearest integer; callers wanting genuine step-index semantics should
    * convert their own filtration values to step indices first.
    */
  def writePersistenceIntervals(pathPrefix: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    formatPersistenceIntervals(bars).foreach { case (dim, text) =>
      FileAccess.writeText(s"${pathPrefix}_$dim.txt", text)
    }

  /** The text of each dimension's output file, by dimension. */
  def formatPersistenceIntervals(bars: Seq[PersistenceBar[Double, ?]]): Map[Int, String] =
    bars.groupBy(_.dim).map { case (dim, dimBars) =>
      dim -> dimBars.map { bar =>
        val birth = endpointToStep(bar.lower)
        val death = bar.upper match
          case PositiveInfinity() => -1L
          case other              => endpointToStep(other)
        s"$birth $death\n"
      }.mkString
    }

  /** Reads one Perseus output file (`<...>_<dim>.txt`) back into bars of the given dimension -- `dim` must be supplied
    * by the caller, since Perseus's own per-dimension output files don't repeat it inside the file.
    */
  def readPersistenceIntervals(path: String, dim: Int): Seq[PersistenceBar[Double, Nothing]] =
    parsePersistenceIntervals(FileAccess.readText(path), dim)

  /** [[readPersistenceIntervals]] on the text of such a file. */
  def parsePersistenceIntervals(text: String, dim: Int): Seq[PersistenceBar[Double, Nothing]] =
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        val fields = line.split("\\s+")
        require(fields.length == 2, s"expected 2 fields (birth,death), got ${fields.length} in line '$line'")
        val birth = fields(0).toDouble
        if fields(1) == "-1" then PersistenceBar[Double](dim, birth)
        else PersistenceBar[Double](dim, birth, fields(1).toDouble)
      }
      .toSeq

  // ---- simplicial toplex input (`simtop` and `nmfsimtop`) ----

  private def toplexTokens(text: String, origin: String): Iterator[Long] =
    text.linesIterator
      .flatMap(_.trim.split("\\s+"))
      .filter(_.nonEmpty)
      .map { token =>
        token.toLongOption.getOrElse(throw new IllegalArgumentException(s"$origin: '$token' is not an integer"))
      }

  /** Gives each distinct vertex (a tuple of `n` coordinates) a number: the coordinate itself when `n == 1`, else the
    * order of first appearance.
    */
  private final class VertexNumbering(n: Int):
    private val numbers = scala.collection.mutable.LinkedHashMap.empty[Seq[Long], Int]
    def apply(coordinates: Seq[Long]): Int =
      if n == 1 then Math.toIntExact(coordinates.head)
      else numbers.getOrElseUpdate(coordinates, numbers.size)

  private def toplexFacet(numbering: VertexNumbering, n: Int, coordinates: Seq[Long]): Simplex[Int] =
    Simplex.from(coordinates.grouped(n).map(numbering(_)).toList)

  /** `simtop`: the dimension `d` of every simplex, the number `n` of coordinates per vertex, then for each simplex its
    * `d + 1` vertices (`n` numbers each, in any order) and its integer birth time. A simplex with birth time `-1` is
    * ignored. With `n == 1` the vertices are the numbers themselves; otherwise they are numbered in the order they
    * first appear. Faces are not listed: each is born with the earliest facet that contains it ([[toplexStream]]).
    */
  def parseUniformToplex(text: String, origin: String = "input"): Seq[(Double, Simplex[Int])] =
    val tokens = toplexTokens(text, origin)
    def next(what: String): Long =
      require(tokens.hasNext, s"$origin: the file ends before $what")
      tokens.next()
    val d = Math.toIntExact(next("the dimension"))
    val n = Math.toIntExact(next("the number of coordinates per vertex"))
    require(d >= 0 && n >= 1, s"$origin: the dimension must be at least 0 and the coordinates per vertex at least 1")
    val numbering = VertexNumbering(n)
    val facets = Seq.newBuilder[(Double, Simplex[Int])]
    while tokens.hasNext do
      val coordinates = Seq.fill(n * (d + 1))(next("a simplex's vertices"))
      val birth = next("a simplex's birth time")
      if birth != -1L then facets += ((birth.toDouble, toplexFacet(numbering, n, coordinates)))
    facets.result()

  /** `nmfsimtop`: the number `n` of coordinates per vertex, then for each simplex its own dimension `m`, its `m + 1`
    * vertices and its birth time. As [[parseUniformToplex]] otherwise.
    */
  def parseNonUniformToplex(text: String, origin: String = "input"): Seq[(Double, Simplex[Int])] =
    val tokens = toplexTokens(text, origin)
    def next(what: String): Long =
      require(tokens.hasNext, s"$origin: the file ends before $what")
      tokens.next()
    val n = Math.toIntExact(next("the number of coordinates per vertex"))
    require(n >= 1, s"$origin: the coordinates per vertex must be at least 1")
    val numbering = VertexNumbering(n)
    val facets = Seq.newBuilder[(Double, Simplex[Int])]
    while tokens.hasNext do
      val m = Math.toIntExact(next("a simplex's dimension"))
      require(m >= 0, s"$origin: a simplex has dimension $m")
      val coordinates = Seq.fill(n * (m + 1))(next("a simplex's vertices"))
      val birth = next("a simplex's birth time")
      if birth != -1L then facets += ((birth.toDouble, toplexFacet(numbering, n, coordinates)))
    facets.result()

  def readUniformToplex(path: String): Seq[(Double, Simplex[Int])] = parseUniformToplex(FileAccess.readText(path), path)

  def readNonUniformToplex(path: String): Seq[(Double, Simplex[Int])] =
    parseNonUniformToplex(FileAccess.readText(path), path)

  private def integerBirth(birth: Double): Long =
    require(birth == math.rint(birth) && birth >= 0, s"Perseus's birth times are non-negative integers, got $birth")
    birth.toLong

  /** The `simtop` text of `facets` (one coordinate per vertex): every facet must have the same dimension. */
  def formatUniformToplex(facets: Seq[(Double, Simplex[Int])]): String =
    require(facets.nonEmpty, "cannot write a toplex file without simplices")
    val d = facets.head._2.dim
    require(
      facets.forall(_._2.dim == d),
      "a uniform toplex needs simplices of one dimension; use formatNonUniformToplex"
    )
    s"$d\n1\n" + facets
      .map((birth, facet) => (facet.toSeq.map(_.toString) :+ integerBirth(birth)).mkString(" ") + "\n")
      .mkString

  /** The `nmfsimtop` text of `facets` (one coordinate per vertex). */
  def formatNonUniformToplex(facets: Seq[(Double, Simplex[Int])]): String =
    "1\n" + facets
      .map((birth, facet) =>
        (facet.dim.toString +: facet.toSeq.map(_.toString) :+ integerBirth(birth)).mkString(" ") + "\n"
      )
      .mkString

  /** The filtered complex generated by `facets`: each facet enters at its birth time, each face at the earliest birth
    * of the facets that contain it (Perseus lowers a face's birth to its parent's in the same way).
    */
  def toplexStream(facets: Seq[(Double, Simplex[Int])]): ExplicitStream[Int, Double] =
    ExplicitStreamBuilder.fromFilteredFacets(facets)
