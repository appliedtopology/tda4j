package org.appliedtopology.tda4j

/** PHAT's boundary-matrix formats, with the conventions of `phat/boundary_matrix.h` (`load_ascii`, `save_ascii`,
  * `load_binary`, `save_binary`): a matrix over Z/2 whose columns are numbered from 0, each with a dimension and the
  * sorted row indices of its nonzero entries.
  *
  *   - ASCII: one column per line, the dimension first and then the row indices, separated by whitespace; empty lines
  *     and lines starting with `#` are skipped.
  *   - Binary: an `Int64` column count, then for each column its dimension (`Int64`), its number of entries `N`
  *     (`Int64`) and the `N` row indices (`Int64`). PHAT writes the machine's byte order; this reader and writer use
  *     little-endian, which is the byte order of x86 and ARM machines.
  *
  * PHAT reads a matrix whose columns are in filtration order, so a face's row comes before the column of its coface:
  * [[fromStream]] builds that matrix from a stream, sorting its cells by the stream's `filtrationOrdering`.
  */
object Phat:

  /** A boundary matrix over Z/2: column `j` has dimension `dimensions(j)` and the nonzero rows `columns(j)`,
    * increasing.
    */
  final case class Matrix(dimensions: IndexedSeq[Int], columns: IndexedSeq[IndexedSeq[Long]]):
    require(dimensions.size == columns.size, "every column needs a dimension")
    def size: Int = dimensions.size

  /** The boundary matrix over Z/2 of the cells of `stream` in filtration order (by filtration value, then dimension, so
    * a face comes before its cofaces; ties are broken by the stream's `filtrationOrdering`): column `j` is cell `j`,
    * its rows the positions of the faces in the boundary.
    */
  def fromStream[CellT: OrderedCell](stream: CellStream[CellT, Double]): Matrix =
    val ordering =
      Ordering
        .by[CellT, (Double, Int)](cell => (stream.filtrationValue(cell), cell.dim))
        .orElse(stream.filtrationOrdering)
    fromCells(stream.iterator.toIndexedSeq.sorted(using ordering))

  /** The boundary matrix over Z/2 of `cells`, which must be listed in filtration order with every face of a cell listed
    * before it: column `j` is cell `j`, its rows the positions of the faces in the boundary.
    */
  def fromCells[CellT: OrderedCell](cells: Iterable[CellT]): Matrix =
    val field = FiniteField(2)
    import field.given
    val ordered = cells.toIndexedSeq
    val position: Map[CellT, Long] = ordered.zipWithIndex.map((cell, j) => (cell, j.toLong)).toMap
    val zero = field.Fp(0)
    val columns: IndexedSeq[IndexedSeq[Long]] = ordered.map { cell =>
      cell
        .boundary[field.Fp]
        .collect { case (face, c) if !field.fpField.isEqual(c, zero) => position(face) }
        .sorted
        .toIndexedSeq
    }
    Matrix(ordered.map(_.dim), columns)

  /** The text of `matrix` in PHAT's ASCII format. */
  def formatAscii(matrix: Matrix): String =
    matrix.dimensions
      .zip(matrix.columns)
      .map((dim, rows) => (dim.toString +: rows.map(_.toString)).mkString(" ") + "\n")
      .mkString

  /** [[formatAscii]] read back. Entries are sorted, as PHAT sorts them when it loads a file. */
  def parseAscii(text: String, origin: String = "input"): Matrix =
    val columns = text.linesIterator.zipWithIndex
      .map((line, i) => (line.trim, i + 1))
      .filter((line, _) => line.nonEmpty && !line.startsWith("#"))
      .map { (line, number) =>
        val fields = line.split("\\s+")
        val numbers = fields.map { token =>
          token.toLongOption.getOrElse(
            throw new IllegalArgumentException(s"$origin, line $number: '$token' is not an integer")
          )
        }
        require(numbers.head >= 0, s"$origin, line $number: the dimension is negative")
        require(numbers.tail.forall(_ >= 0), s"$origin, line $number: a row index is negative")
        (numbers.head.toInt, numbers.tail.toIndexedSeq.sorted)
      }
      .toIndexedSeq
    Matrix(columns.map(_._1), columns.map(_._2))

  def writeAscii(path: String, matrix: Matrix): Unit = FileAccess.writeText(path, formatAscii(matrix))

  def readAscii(path: String): Matrix = parseAscii(FileAccess.readText(path), path)

  /** The bytes of `matrix` in PHAT's binary format (little-endian). */
  def formatBinary(matrix: Matrix): Array[Byte] =
    val entries = matrix.columns.map(_.size.toLong).sum
    val buf = BinaryIO.newBufferLE(Math.toIntExact(8L + 16L * matrix.size + 8L * entries))
    buf.putLong(matrix.size.toLong)
    matrix.dimensions.zip(matrix.columns).foreach { (dim, rows) =>
      buf.putLong(dim.toLong)
      buf.putLong(rows.size.toLong)
      rows.foreach(buf.putLong)
    }
    buf.array()

  /** [[formatBinary]] read back; the entries are taken as stored, not sorted (PHAT does not sort them either). */
  def parseBinary(bytes: Array[Byte], origin: String = "input"): Matrix =
    val buf = BinaryIO.wrapLE(bytes)
    require(bytes.length >= 8, s"$origin: too short to be a PHAT binary file (needs the column count)")
    val columns = buf.getLong()
    require(
      columns >= 0 && columns <= (bytes.length - 8L) / 16,
      s"$origin: the column count $columns does not fit the file"
    )
    val dims = IndexedSeq.newBuilder[Int]
    val rows = IndexedSeq.newBuilder[IndexedSeq[Long]]
    var j = 0L
    while j < columns do
      require(buf.remaining() >= 16, s"$origin: the file ends inside column $j")
      dims += buf.getLong().toInt
      val n = buf.getLong()
      require(n >= 0 && n <= buf.remaining() / 8, s"$origin: column $j declares $n entries and the file ends first")
      rows += IndexedSeq.fill(n.toInt)(buf.getLong())
      j += 1
    require(!buf.hasRemaining, s"$origin: ${buf.remaining()} bytes follow the last column")
    Matrix(dims.result(), rows.result())

  def writeBinary(path: String, matrix: Matrix): Unit = FileAccess.writeBytes(path, formatBinary(matrix))

  def readBinary(path: String): Matrix = parseBinary(FileAccess.readBytes(path), path)
