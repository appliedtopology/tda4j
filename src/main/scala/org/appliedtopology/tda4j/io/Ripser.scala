package org.appliedtopology.tda4j

/** Ripser's input formats (`point-cloud`, `lower-distance`, `upper-distance`, `distance`, `binary`, `sparse`), with the
  * conventions of `ripser.cpp`. Not supported: `dipha` (use [[Dipha]]).
  */
object Ripser:
  private val delimiterRegex = "[\\s,]+"

  /** One point per line, whitespace- or comma-separated coordinates -- Ripser's default `--format point-cloud`. */
  def parsePointCloud(text: String): Array[Array[Double]] =
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.split(delimiterRegex).map(_.toDouble))
      .toArray

  def readPointCloud(path: String): Array[Array[Double]] = parsePointCloud(FileAccess.readText(path))

  def readEuclideanMetricSpace(path: String): EuclideanMetricSpace =
    EuclideanMetricSpace(readPointCloud(path))

  def formatPointCloud(points: Array[Array[Double]]): String =
    points.map(_.mkString(",") + "\n").mkString

  def writePointCloud(path: String, points: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatPointCloud(points))

  private def flatValues(text: String): IndexedSeq[Double] =
    text.linesIterator.mkString(" ").split(delimiterRegex).filter(_.nonEmpty).map(_.toDouble).toIndexedSeq

  /** `--format lower-distance`: every value in the file (across line breaks; comma- or whitespace-separated) is one
    * flat token stream: row `i` (`i = 1 until n`) contributes `i` entries `d(i,0),...,d(i,i-1)`, rows concatenated in
    * order -- confirmed against `read_lower_distance_matrix`/`compressed_lower_distance_matrix`'s `init_rows` in
    * `ripser.cpp` directly.
    */
  def parseLowerDistanceMatrix(text: String): Array[Array[Double]] =
    val flat = flatValues(text)
    DistanceMatrices.expandLowerTriangular(flat, DistanceMatrices.sizeFromTriangularCount(flat.size))

  def readLowerDistanceMatrix(path: String): Array[Array[Double]] =
    parseLowerDistanceMatrix(FileAccess.readText(path))

  def readLowerDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readLowerDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def formatLowerDistanceMatrix(matrix: Array[Array[Double]]): String =
    DistanceMatrices.flattenLowerTriangular(matrix).mkString(",") + "\n"

  def writeLowerDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatLowerDistanceMatrix(matrix))

  /** `upper-distance`: the entries above the diagonal, row by row (`d(0,1), ..., d(0,n-1), d(1,2), ...`). */
  def parseUpperDistanceMatrix(text: String): Array[Array[Double]] =
    val flat = flatValues(text)
    DistanceMatrices.expandUpperTriangular(flat, DistanceMatrices.sizeFromTriangularCount(flat.size))

  def readUpperDistanceMatrix(path: String): Array[Array[Double]] =
    parseUpperDistanceMatrix(FileAccess.readText(path))

  def readUpperDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readUpperDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def formatUpperDistanceMatrix(matrix: Array[Array[Double]]): String =
    DistanceMatrices.flattenUpperTriangular(matrix).mkString(",") + "\n"

  def writeUpperDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatUpperDistanceMatrix(matrix))

  /** `--format distance` (Ripser's own default): a dense `n x n` matrix, one row per line, but ONLY the strictly lower
    * triangle of each line is actually read (`for (j = 0; j < i && s >> value; ++j)`) -- so line `i` (0-indexed) must
    * have at least `i` values, and anything at or past the diagonal is ignored outright, never cross-checked against
    * the lower triangle.
    */
  def parseDistanceMatrix(text: String, origin: String = "input"): Array[Array[Double]] =
    val lines = text.linesIterator.toIndexedSeq.reverse.dropWhile(_.trim.isEmpty).reverse
    val n = lines.size
    val flat = lines.zipWithIndex
      .flatMap { case (line, i) =>
        val values = line.trim.split(delimiterRegex).filter(_.nonEmpty)
        require(
          values.length >= i,
          s"$origin: line ${i + 1} has ${values.length} numbers, a distance matrix needs at least $i there " +
            "(the entries below the diagonal)"
        )
        values.take(i)
      }
      .map(_.toDouble)
    DistanceMatrices.expandLowerTriangular(flat, n)

  def readDistanceMatrix(path: String): Array[Array[Double]] =
    parseDistanceMatrix(FileAccess.readText(path), path)

  def readDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  /** `--format sparse`: one `i j d` triple per line (0-based vertex numbers and the distance, separated by commas or
    * whitespace), a pair listed once. The number of points is the largest index plus one, as in `ripser.cpp` (a last
    * vertex with no edge cannot be named). A pair that is not listed has no edge: its distance is `+Infinity` in the
    * result, and a threshold below that leaves it out of the complex. Lines with `i == j` are skipped, as Ripser skips
    * them; a pair listed twice is refused (Ripser would count it as two edges).
    *
    * Ripser's default threshold is infinite, so a complex built from this matrix needs the largest listed distance as
    * its `maxFiltrationValue` ([[maxFiniteDistance]]) to be the flag complex of the listed edges.
    */
  def parseSparseDistanceMatrix(text: String, origin: String = "input"): Array[Array[Double]] =
    val triples = sparseTriples(text, origin)
    val n = if triples.isEmpty then 0 else triples.map((i, j, _, _) => math.max(i, j)).max + 1
    val matrix = Array.tabulate(n, n)((i, j) => if i == j then 0.0 else Double.PositiveInfinity)
    triples.foreach { (i, j, d, number) =>
      require(
        matrix(i)(j).isInfinite,
        s"$origin, line $number: the pair ($i, $j) is listed twice; list each pair once"
      )
      matrix(i)(j) = d
      matrix(j)(i) = d
    }
    matrix

  // The `i j d` lines as (i, j, d, line number), loops dropped.
  private def sparseTriples(text: String, origin: String): IndexedSeq[(Int, Int, Double, Int)] =
    text.linesIterator.zipWithIndex
      .map((line, i) => (line.trim, i + 1))
      .filter((line, _) => line.nonEmpty)
      .map { (line, number) =>
        val fields = line.split(delimiterRegex)
        require(fields.length == 3, s"$origin, line $number: expected 'i j distance', got ${fields.length} fields")
        def index(token: String): Int =
          token.toIntOption.filter(_ >= 0).getOrElse {
            throw new IllegalArgumentException(s"$origin, line $number: '$token' is not a vertex number (0, 1, 2, ...)")
          }
        val distance = fields(2).toDoubleOption.getOrElse {
          throw new IllegalArgumentException(s"$origin, line $number: '${fields(2)}' is not a number")
        }
        require(!distance.isNaN, s"$origin, line $number: the distance is NaN")
        (index(fields(0)), index(fields(1)), distance, number)
      }
      .filter((i, j, _, _) => i != j)
      .toIndexedSeq

  /** The `sparse` text as a [[WeightedGraph]]: the same lines, held as neighbour lists (`n + 2 m` numbers) instead of
    * an `n x n` matrix, for a network too large for a matrix. `Persistence(graph)` is then the flag complex of the
    * listed edges ([[FlagComplex]]) up to the longest one. The rules are those of [[parseSparseDistanceMatrix]]: the
    * number of vertices is the largest index plus one, loops are skipped and a pair listed twice is refused.
    */
  def parseSparseGraph(text: String, origin: String = "input"): WeightedGraph =
    val triples = sparseTriples(text, origin)
    val seen = scala.collection.mutable.HashSet.empty[(Int, Int)]
    triples.foreach { (i, j, _, number) =>
      require(
        seen.add((math.min(i, j), math.max(i, j))),
        s"$origin, line $number: the pair ($i, $j) is listed twice; list each pair once"
      )
    }
    val n = if triples.isEmpty then 0 else triples.map((i, j, _, _) => math.max(i, j)).max + 1
    WeightedGraph(n, triples.map((i, j, d, _) => (i, j, d)))

  def readSparseGraph(path: String): WeightedGraph =
    parseSparseGraph(FileAccess.readText(path), path)

  def readSparseDistanceMatrix(path: String): Array[Array[Double]] =
    parseSparseDistanceMatrix(FileAccess.readText(path), path)

  def readSparseDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readSparseDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  /** The largest distance in `matrix` that is not infinite (0 if there is none): the threshold at which a sparse matrix
    * is the flag complex of its listed edges.
    */
  def maxFiniteDistance(matrix: Array[Array[Double]]): Double =
    matrix.iterator.flatMap(_.iterator).filter(d => !d.isInfinite).maxOption.getOrElse(0.0)

  /** The `sparse` text of `matrix`: a line `i j d` for each pair `i < j` whose distance is finite. */
  def formatSparseDistanceMatrix(matrix: Array[Array[Double]]): String =
    val n = matrix.size
    (for
      i <- 0 until n
      j <- (i + 1) until n
      if !matrix(i)(j).isInfinite
    yield s"$i $j ${matrix(i)(j)}\n").mkString

  def writeSparseDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeText(path, formatSparseDistanceMatrix(matrix))

  /** `--format binary`: no header at all, just raw little-endian IEEE-754 `float32` values read until EOF, in the same
    * flat lower-triangular order as `readLowerDistanceMatrix` -- Ripser's own `value_t` is `typedef float value_t`, NOT
    * `double` (confirmed directly against `ripser.cpp`'s own typedef), so every value narrows to 32 bits on write and
    * widens back to `Double` on read; this is real precision loss, matching what real `ripser --format binary` output
    * already has baked in, not something this reader introduces.
    */
  def readBinaryLowerDistanceMatrix(path: String): Array[Array[Double]] =
    parseBinaryLowerDistanceMatrix(FileAccess.readBytes(path))

  /** [[readBinaryLowerDistanceMatrix]] on the bytes of such a file. */
  def parseBinaryLowerDistanceMatrix(bytes: Array[Byte]): Array[Array[Double]] =
    val buf = BinaryIO.wrapLE(bytes)
    val count = buf.remaining() / 4
    val flat = new Array[Double](count)
    var i = 0
    while i < count do
      flat(i) = buf.getFloat().toDouble
      i += 1
    DistanceMatrices.expandLowerTriangular(flat.toIndexedSeq, DistanceMatrices.sizeFromTriangularCount(count))

  def readBinaryExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readBinaryLowerDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def formatBinaryLowerDistanceMatrix(matrix: Array[Array[Double]]): Array[Byte] =
    val flat = DistanceMatrices.flattenLowerTriangular(matrix)
    val buf = BinaryIO.newBufferLE(flat.length * 4)
    flat.foreach(v => buf.putFloat(v.toFloat))
    buf.array()

  def writeBinaryLowerDistanceMatrix(path: String, matrix: Array[Array[Double]]): Unit =
    FileAccess.writeBytes(path, formatBinaryLowerDistanceMatrix(matrix))
