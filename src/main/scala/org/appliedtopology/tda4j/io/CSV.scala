package org.appliedtopology.tda4j

/* File I/O (the `io/` directory). Loading and saving file formats used across the TDA ecosystem: this package's own `CSV` (generic delimited-text
 * point clouds, distance matrices, and persistence diagrams -- what `RipserPaperBenchmarkSpec` used to hand-roll ad
 * hoc, now routed through here instead), plus adaptors for the formats other major TDA projects actually use: Ripser's
 * point-cloud/distance-matrix/packed-binary formats (`Ripser.scala`), DIPHA's binary container format (`Dipha.scala`),
 * GUDHI's OFF point-cloud and `.pers` diagram formats (`Gudhi.scala`), and Perseus's cubical toplex format
 * (`Perseus.scala`, also what GUDHI's own cubical-complex module reads). Dionysus and JavaPlex need no dedicated
 * adaptor: both consume the same plain whitespace/comma-separated point-cloud and distance-matrix text `CSV`/`Ripser`
 * already read.
 *
 * Every format here that has a primary source (a project's own source code, not a secondhand description) was verified
 * against that source directly before being implemented -- see `.claude/WORKLOG-io-module.md` for exactly what was
 * checked and where, including two real, easy-to-get-backwards details that would have silently produced a
 * plausible-looking wrong answer rather than an error: Ripser's binary format is packed 32-bit `float`, not `double`;
 * and DIPHA's/Perseus's cubical grid axis order is the OPPOSITE of `CubicalImage.fromFlatArray`'s own convention.
 * PHAT's boundary-matrix formats (`Phat.scala`) and Perseus's simplicial toplex formats (in `Perseus.scala`) follow
 * PHAT's `boundary_matrix.h` and Perseus's manual; no file written by PHAT or Perseus themselves has been read back.
 *
 * '''Layering''': the `io/` files are a leaf. They use the stream types (`EuclideanMetricSpace`/`ExplicitMetricSpace`/
 * `CubicalGridStream`) and `PersistenceBar`; nothing outside `io/` (apart from the MATLAB facade and CLI) uses them.
 */

/** Generic delimited-text point clouds, distance matrices, and persistence diagrams -- fields split on `,` or any run
  * of whitespace, so plain-whitespace-separated files (Dionysus/JavaPlex's own convention) parse with these same
  * methods too.
  */
object CSV:
  private val delimiterRegex = "[\\s,]+"

  private def parseNumber(origin: String, lineNumber: Int, token: String): Double =
    token.toDoubleOption.getOrElse(
      throw new IllegalArgumentException(s"$origin, line $lineNumber: '$token' is not a number")
    )

  private def numericRows(text: String, origin: String): IndexedSeq[IndexedSeq[Double]] =
    text.linesIterator.zipWithIndex
      .map((line, i) => (line.trim, i + 1))
      .filter((line, _) => line.nonEmpty)
      .map((line, number) => line.split(delimiterRegex).toIndexedSeq.map(parseNumber(origin, number, _)))
      .toIndexedSeq

  /** The rows of `text`, where row `i` of `rows` must hold `width(rows, i)` numbers, or a message that says which row
    * is wrong.
    */
  private def shapedRows(text: String, origin: String)(
    width: (IndexedSeq[IndexedSeq[Double]], Int) => Int
  ): IndexedSeq[IndexedSeq[Double]] =
    val rows = numericRows(text, origin)
    rows.zipWithIndex.foreach { (row, i) =>
      val expected = width(rows, i)
      require(
        row.size == expected,
        s"$origin: row ${i + 1} has ${row.size} numbers, expected $expected (is this the format you meant?)"
      )
    }
    rows

  private def rowsText(rows: Iterable[Iterable[Double]]): String =
    rows.map(_.mkString(",") + "\n").mkString

  /** One point per line, coordinates separated by `,` or whitespace. `origin` names the text in error messages. */
  def parsePointCloud(text: String, origin: String = "input"): Array[Array[Double]] =
    shapedRows(text, origin)((rows, _) => rows.head.size).map(_.toArray).toArray

  def readPointCloud(path: String): Array[Array[Double]] = parsePointCloud(FileAccess.readText(path), path)

  def readEuclideanMetricSpace(path: String): EuclideanMetricSpace =
    EuclideanMetricSpace(readPointCloud(path))

  /** The text of a point cloud file: one point per line. */
  def formatPointCloud(points: Array[Array[Double]]): String =
    rowsText(points.toIndexedSeq.map(_.toIndexedSeq))

  def writePointCloud(path: String, points: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatPointCloud(points))

  /** A full `n x n` distance matrix, one row per line -- both triangles and the diagonal are read as given; this does
    * NOT check that they agree (a caller wanting that checked should build from `readLowerTriangularDistanceMatrix`
    * instead, which only ever reads one triangle).
    */
  def parseFullDistanceMatrix(text: String, origin: String = "input"): Array[Array[Double]] =
    shapedRows(text, origin)((rows, _) => rows.size).map(_.toArray).toArray

  def readFullDistanceMatrix(path: String): Array[Array[Double]] =
    parseFullDistanceMatrix(FileAccess.readText(path), path)

  def readExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readFullDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def formatFullDistanceMatrix(matrix: Array[Array[Double]]): String =
    rowsText(matrix.toIndexedSeq.map(_.toIndexedSeq))

  def writeFullDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatFullDistanceMatrix(matrix))

  /** Row `i` (`i = 1 until n`) has exactly `i` entries, `d(i,0),...,d(i,i-1)` -- no diagonal, no upper triangle, and
    * row `0` (which would have zero entries) is omitted entirely rather than written as a blank line -- so an `n`-point
    * matrix is `n-1` physical lines, the same row layout Ripser's own text formats use, just one row per physical line
    * instead of one flat token stream spanning line breaks (see `Ripser.readLowerDistanceMatrix`, which reads the
    * identical convention).
    */
  def parseLowerTriangularDistanceMatrix(text: String, origin: String = "input"): Array[Array[Double]] =
    val rows = shapedRows(text, origin)((_, i) => i + 1)
    DistanceMatrices.expandLowerTriangular(rows.flatten, rows.size + 1)

  def readLowerTriangularDistanceMatrix(path: String): Array[Array[Double]] =
    parseLowerTriangularDistanceMatrix(FileAccess.readText(path), path)

  def readLowerTriangularExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readLowerTriangularDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def formatLowerTriangularDistanceMatrix(matrix: Array[Array[Double]]): String =
    (1 until matrix.size).map(i => (0 until i).map(j => matrix(i)(j)).mkString(",") + "\n").mkString

  def writeLowerTriangularDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatLowerTriangularDistanceMatrix(matrix))

  /** `dim,birth,death` per line, no header, no comments -- `death` (or `birth`) may be `inf`/`-inf`/`infinity` (any
    * case) for an infinite endpoint. See `Gudhi.readPersistenceDiagram` for a format that also supports comments and an
    * optional coefficient-field column.
    */
  def parsePersistenceDiagram(text: String): Seq[PersistenceBar[Double, Nothing]] =
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        val fields = line.split(delimiterRegex)
        require(fields.length == 3, s"expected 3 fields (dim,birth,death), got ${fields.length} in line '$line'")
        Endpoints.toBar(fields(0).toInt, fields(1), fields(2))
      }
      .toSeq

  def readPersistenceDiagram(path: String): Seq[PersistenceBar[Double, Nothing]] =
    parsePersistenceDiagram(FileAccess.readText(path))

  /** One bar as a line of the format (`dimension,lower,upper`), without the line break. */
  def formatBar(bar: PersistenceBar[Double, ?]): String =
    s"${bar.dim},${Endpoints.formatEndpoint(bar.lower)},${Endpoints.formatEndpoint(bar.upper)}"

  def formatPersistenceDiagram(bars: Seq[PersistenceBar[Double, ?]]): String =
    bars.map(bar => formatBar(bar) + "\n").mkString

  def writePersistenceDiagram(path: String, bars: Seq[PersistenceBar[Double, ?]]): Unit =
    FileAccess.writeText(path, formatPersistenceDiagram(bars))
