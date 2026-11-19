package org.appliedtopology.tda4j

/** The persistent Laplacian of a filtered complex (Wang, Nguyen and Wei; Mémoli, Wan and Wang): a symmetric positive
  * semidefinite matrix `Δ_k^{s,t}` on the `k`-cells of the complex at `s`, for every pair `s <= t` of filtration
  * values, whose kernel has the dimension of the persistent Betti number `β_k^{s,t}`, the rank of
  * `H_k(K_s) -> H_k(K_t)`, and whose other eigenvalues carry geometric information the barcode does not (the smallest
  * nonzero one is a persistent spectral gap).
  *
  * {{{
  * val rips = VietorisRips(points, maxDimension = 1)
  * val laplacian = PersistentLaplacian(rips, dimension = 1, from = 0.4, to = 0.9)
  * laplacian.bettiNumber     // loops at 0.4 that are still loops at 0.9
  * laplacian.spectralGap     // the smallest nonzero eigenvalue
  * laplacian.eigenvalues     // ascending
  * }}}
  *
  * With `C^{s,t}_{k+1}` the chains of `K_t` whose boundary lies in `K_s`, the persistent Laplacian is `∂ ∂^* + ∂_k^{s
  * *} ∂_k^s`, the first term being the boundary of `C^{s,t}_{k+1}` into the `k`-chains of `K_s` composed with its
  * adjoint, in the inner product in which the cells are orthonormal. Written with the matrices of `K_t` and its rows
  * split into those in `K_s` (`D`) and those in `K_t \ K_s` (`R`), that is `D (1 - R^+ R) D^T + A^T A`, `A` being the
  * boundary of the `k`-cells of `K_s`; at `s = t` it is the Hodge Laplacian. The coefficients are real numbers, the
  * matrices dense: for complexes of a few thousand cells in the dimension asked for. The stream has to hold the cells
  * of dimension `dimension + 1` (a stream cut off at homological degree `k` does, up to `k`).
  */
object PersistentLaplacian:

  /** The smallest singular value that counts: below it a factor entry is rounding, not a boundary coefficient. */
  private val FloorOfRank = 1e-9

  /** What a persistent Laplacian is, whichever way it was built: a symmetric positive semidefinite matrix `Δ = X X^T`
    * on [[cells]], for a factor `X` with a column for each boundary and coboundary direction, so that the eigenvalues
    * are the squares of its singular values (no squared condition number) and the kernel is found by the rank of `X`.
    */
  // `abstract` with a private constructor: `factor` is the one state, and `matrix` and `eigenvalues` are functions of it;
  // the two ways to build one are the subclasses below (openness reason 1: an unchecked constructor).
  abstract class Spectrum[CellT] private[tda4j] (
    val dimension: Int,
    /** The cells the matrix is written on: its rows and columns, in the order of the stream or collection they came
      * from.
      */
    val cells: Vector[CellT],
    private val factor: DenseMatrix,
    private val cellOrdering: Ordering[CellT],
    /** The weight of each cell: 1, unless [[PersistentLaplacian.ofMap]] merged several simplices onto it, and then
      * their number. As a chain the cell has the length `1 / sqrt(weight)`, and the matrix is on the cells scaled to
      * length one.
      */
    val weights: Vector[Double]
  ):
    private lazy val decomposition = factor.svd
    // the decomposition's own tolerance is relative to its largest singular value, so a factor that is zero up to
    // rounding would count its noise as rank; the entries are of size one, so a floor of 1e-9 never hides a real value
    private lazy val cutoff = math.max(decomposition.tolerance, FloorOfRank)
    private lazy val rank = decomposition.rank(cutoff)
    // from the orthonormal coordinate of a cell to its coefficient in a chain
    private val cellScales: Vector[Double] = weights.map(math.sqrt)

    /** The matrix of `Δ`, on [[cells]], in the basis of the cells scaled to length one (the cells themselves, unless
      * [[PersistentLaplacian.ofMap]] merged cells: see there).
      */
    lazy val matrix: DenseMatrix = factor * factor.transpose

    /** The eigenvalues, smallest first, as many as there are cells. */
    lazy val eigenvalues: Vector[Double] =
      val squares = decomposition.singularValues.map(s => s * s).sorted.toVector
      Vector.fill(cells.size - squares.size)(0.0) ++ squares

    /** The dimension of the kernel, the number of eigenvalues that are zero to rounding: the persistent Betti number,
      * the rank of the map the Laplacian belongs to on homology.
      */
    lazy val bettiNumber: Int = cells.size - rank

    /** An orthonormal basis of the kernel, [[bettiNumber]] chains on [[cells]]: the persistent harmonic chains. Their
      * classes are a basis of the image of the map on homology. The space is canonical and the basis is not (a kernel
      * of dimension one is its chain up to sign: each is signed to make its first nonzero entry, in the order of
      * [[cells]], positive); entries below `1e-12` are left out. Computed from a second singular value decomposition,
      * of the transpose of the factor, whose right singular vectors are an orthogonal matrix by construction, whatever
      * the rounding. The coefficients are those of the chain on the cells (the orthonormal coordinates of the matrix
      * are scaled back).
      */
    lazy val kernel: Vector[Chain[CellT, Double]] =
      given Double is Field = Field.DoubleApproximated(1e-9)
      given Ordering[CellT] = cellOrdering
      val n = cells.size
      if n == 0 then Vector.empty
      else
        // a factor with fewer columns than rows is padded with zero columns: its transpose is then tall, and V is n x n
        val padded =
          if factor.cols >= n then factor
          else DenseMatrix.tabulate(n, n)((i, j) => if j < factor.cols then factor(i, j) else 0.0)
        val right = padded.transpose.svd.v
        (rank until n).toVector.map { k =>
          // the entries of one right singular vector, scaled back from the orthonormal coordinates to chain coefficients
          val entries =
            cells.indices.filter(i => math.abs(right(i, k)) > 1e-12).map(i => (cells(i), right(i, k) * cellScales(i)))
          val sign = entries.headOption.fold(1.0)((_, x) => if x < 0 then -1.0 else 1.0)
          Chain.from(entries.map((cell, x) => (cell, sign * x)))
        }

    /** The smallest eigenvalue that is not zero to rounding, if there is one. */
    lazy val spectralGap: Option[Double] =
      decomposition.singularValues.filter(_ > cutoff).lastOption.map(s => s * s)

  /** The persistent Laplacian of one dimension and one pair of values. */
  // `final`: a value type whose state is the factor.
  final class Result[CellT] private[tda4j] (
    dimension: Int,
    val from: Double,
    val to: Double,
    /** The cells of `K_s` of this dimension: the rows and columns of the matrix, in the stream's order. */
    cells: Vector[CellT],
    factor: DenseMatrix,
    cellOrdering: Ordering[CellT]
  ) extends Spectrum[CellT](dimension, cells, factor, cellOrdering, Vector.fill(cells.size)(1.0))

  /** The persistent Laplacian of a simplicial map, from [[ofMap]]: on the simplices of the target that the map hits.
    * `kernel` is spanned by the cycles of the source's image that are orthogonal to the boundaries from the target that
    * the image of the source's cycles allows: its dimension, [[bettiNumber]], is the rank of the map on homology.
    */
  final class MapResult[CellT] private[tda4j] (
    dimension: Int,
    cells: Vector[CellT],
    factor: DenseMatrix,
    cellOrdering: Ordering[CellT],
    weights: Vector[Double]
  ) extends Spectrum[CellT](dimension, cells, factor, cellOrdering, weights)

  /** The persistent Laplacian of `stream` in `dimension`, from the filtration value `from` to `to` (`from <= to`). */
  def apply[CellT: OrderedCell as cellStructure](
    stream: StratifiedCellStream[CellT, Double],
    dimension: Int,
    from: Double,
    to: Double
  ): Result[CellT] =
    require(dimension >= 0, s"PersistentLaplacian: the dimension is $dimension, but it cannot be negative")
    require(from <= to, s"PersistentLaplacian: from = $from is after to = $to; give the earlier value first")
    stream.homologyDegreeLimit.foreach(limit =>
      require(
        dimension <= limit,
        s"PersistentLaplacian: the stream holds the cells for homology in degrees up to $limit; build it with maxDimension >= $dimension"
      )
    )
    given Double is Field = Field.DoubleApproximated(1e-9)
    def layer(d: Int): Vector[CellT] = layerOf(stream, d)
    val value = stream.filtrationValue
    val (own, above, below) = (layer(dimension), layer(dimension + 1), layer(dimension - 1))
    val cells = own.filter(value(_) <= from)
    val enteringLater = own.filter(c => value(c) > from && value(c) <= to)
    val coboundaryCells = above.filter(value(_) <= to)
    val (rowOf, laterRowOf) = (cells.zipWithIndex.toMap, enteringLater.zipWithIndex.toMap)
    val n = cells.size
    if n == 0 then return Result(dimension, from, to, cells, DenseMatrix.zeros(0, 0), cellStructure.ordering)

    // D: the boundary of the (k+1)-cells of K_t on the k-cells of K_s; R: on those that enter after s.
    val d = Array.ofDim[Double](n, coboundaryCells.size)
    val r = Array.ofDim[Double](enteringLater.size, coboundaryCells.size)
    for (cell, column) <- coboundaryCells.zipWithIndex; (face, coefficient) <- cellStructure.boundary(cell)[Double] do
      rowOf.get(face).foreach(row => d(row)(column) = coefficient)
      laterRowOf.get(face).foreach(row => r(row)(column) = coefficient)

    // E = D (1 - V_r V_r^T), the rows of D with the part removed that the chains with a boundary outside K_s do not span.
    val e =
      val dMatrix = DenseMatrix(d)
      if enteringLater.isEmpty || coboundaryCells.isEmpty then dMatrix
      else
        val reduction = DenseMatrix(r).svd
        val rowSpace = DenseMatrix.tabulate(coboundaryCells.size, reduction.rank)((i, j) => reduction.v(i, j))
        dMatrix - (dMatrix * rowSpace) * rowSpace.transpose

    // A^T: the transpose of the boundary of the k-cells of K_s.
    val facesOf = cells.map(cellStructure.boundary(_)[Double])
    val faceColumn = scala.collection.mutable.LinkedHashMap.empty[CellT, Int]
    for faces <- facesOf; (face, _) <- faces do
      require(
        value.isDefinedAt(face) && value(face) <= from,
        s"PersistentLaplacian: the stream is not a filtration: a face of a cell that has entered by $from has not"
      )
      faceColumn.getOrElseUpdate(face, faceColumn.size)
    val downColumns = faceColumn.size
    val down = Array.ofDim[Double](n, downColumns)
    for (faces, row) <- facesOf.zipWithIndex; (face, coefficient) <- faces do down(row)(faceColumn(face)) = coefficient
    val factor = DenseMatrix.tabulate(n, downColumns + coboundaryCells.size)((i, j) =>
      if j < downColumns then down(i)(j) else e(i, j - downColumns)
    )
    Result(dimension, from, to, cells, factor, cellStructure.ordering)

  private def layerOf[CellT](stream: StratifiedCellStream[CellT, Double], d: Int): Vector[CellT] =
    if d >= 0 && stream.iterateDimension.isDefinedAt(d) then stream.iterateDimension(d).toVector else Vector.empty

  /** The persistent Laplacian of the simplicial map `vertexMap` from the complex `source` to the complex `target`, in
    * `dimension` `k` (Gülen, Mémoli, Wan and Wang, *Persistent Laplacians of simplicial maps*): a symmetric positive
    * semidefinite matrix on the `k`-simplices of the target that the map hits, whose kernel has the dimension of the
    * rank of `H_k(source) -> H_k(target)`, and whose other eigenvalues carry what the barcode of the map does not. For
    * an inclusion it is the [[apply persistent Laplacian]] of the pair of complexes.
    *
    * {{{
    * // two vertices of a path folded onto one: the map of an edge to a point
    * val path = Seq(∆(0), ∆(1), ∆(2), ∆(0, 1), ∆(1, 2))
    * val folded = Seq(∆(0), ∆(1), ∆(0, 1))
    * PersistentLaplacian.ofMap(path, folded, dimension = 0)(v => if v == 2 then 0 else v).bettiNumber   // 1
    * }}}
    *
    * `source` and `target` hold simplices (every dimension, or just `k - 1` to `k + 1`) and `vertexMap` has to send
    * each simplex of the source to a simplex of the target, or to a simplex that the target would hold had it the cell:
    * the image of a simplex with two vertices on one image is not a `k`-simplex, and the map sends it to zero. The
    * image of `v0 < ... < vk` is the oriented simplex on `f(v0), ..., f(vk)`, and the target's order of the vertices
    * (the `Ordering` given for `W`) is the one its simplices' boundaries are written in.
    *
    * Where the map sends several simplices of the source onto one simplex of the target, the target's simplex gets the
    * weight `w`, the number of simplices of the source above it, and its chain the length `1 / sqrt(w)`: the weights
    * the construction asks for, with which the map of chains is a partial isometry (a simplex nothing maps onto has
    * weight 1). The matrix and its eigenvalues are in the orthonormal coordinates of that inner product;
    * [[Spectrum.weights]] says which cells are scaled, and `kernel` hands back chains on the target's cells. Without a
    * merge (an inclusion, or any map that is injective on the `k`-simplices) every weight is 1 and the cells are
    * orthonormal. The source holds its `(k+1)`-simplices as well, for their weights: they set the lengths of the
    * target's cells there, which change the eigenvalues but not the kernel.
    */
  def ofMap[V: Ordering, W: Ordering](
    source: Iterable[Simplex[V]],
    target: Iterable[Simplex[W]],
    dimension: Int
  )(vertexMap: V => W): MapResult[Simplex[W]] =
    require(dimension >= 0, s"PersistentLaplacian: the dimension is $dimension, but it cannot be negative")
    given Double is Field = Field.DoubleApproximated(1e-9)
    val sourceCells = summon[Simplex[V] is OrderedCell]
    val targetCells = summon[Simplex[W] is OrderedCell]
    val order = summon[Ordering[W]]
    def ofDimension[X](cells: Iterable[Simplex[X]], d: Int): Vector[Simplex[X]] =
      cells.iterator.filter(_.underlying.size == d + 1).toVector.distinct
    val q = dimension
    val (own, below, above) = (ofDimension(source, q), ofDimension(source, q - 1), ofDimension(source, q + 1))
    val (cells, coboundaryCells) = (ofDimension(target, q), ofDimension(target, q + 1))
    val (cellRow, coboundaryColumn) = (cells.zipWithIndex.toMap, coboundaryCells.zipWithIndex.toMap)
    val (ownColumn, belowRow) = (own.zipWithIndex.toMap, below.zipWithIndex.toMap)

    // what the map does to a simplex of the source: the oriented simplex it lands on, or nothing if two vertices merge
    def image(simplex: Simplex[V]): Option[(Simplex[W], Double)] =
      val images = simplex.underlying.toVector.map(vertexMap)
      val landed = Simplex.from(images)
      if landed.underlying.size < images.size then None
      else
        val inversions =
          images.indices.iterator.map(i => (i + 1 until images.size).count(j => order.gt(images(i), images(j)))).sum
        Some((landed, if inversions % 2 == 0 then 1.0 else -1.0))
    def mapped(simplices: Vector[Simplex[V]], row: Map[Simplex[W], Int], what: String) =
      simplices.map(simplex =>
        image(simplex).map { (landed, sign) =>
          require(
            row.contains(landed),
            s"PersistentLaplacian.ofMap: the simplex ${simplex.underlying.mkString("{", ", ", "}")} of the source goes to " +
              s"${landed.underlying.mkString("{", ", ", "}")}, which the target does not hold among its $what; " +
              "vertexMap has to be a simplicial map from the source to the target"
          )
          (row(landed), sign)
        }
      )
    val ownImage = mapped(own, cellRow, s"${q}-simplices")
    val aboveImage = mapped(above, coboundaryColumn, s"${q + 1}-simplices")

    // the weight of a simplex of the target is the number of simplices of the source above it (1 if none)
    def weights(images: Vector[Option[(Int, Double)]], size: Int): Vector[Double] =
      val counts = new Array[Int](size)
      images.foreach(_.foreach((row, _) => counts(row) += 1))
      counts.iterator.map(n => math.max(1, n).toDouble).toVector
    val (cellWeight, coboundaryWeight) = (weights(ownImage, cells.size), weights(aboveImage, coboundaryCells.size))
    val hit = ownImage.flatMap(_.map(_._1)).toSet
    val (a, aBelow, b, c) = (own.size, below.size, cells.size, coboundaryCells.size)

    // F: the chain map on the orthonormal coordinates, a partial isometry; D: boundary of the source; E: of the target
    val f = Array.ofDim[Double](b, a)
    for (entry, column) <- ownImage.zipWithIndex; (row, sign) <- entry do
      f(row)(column) = sign / math.sqrt(cellWeight(row))
    val d = Array.ofDim[Double](aBelow, a)
    for (simplex, column) <- own.zipWithIndex; (face, coefficient) <- sourceCells.boundary(simplex)[Double] do
      val row = belowRow.getOrElse(
        face,
        throw IllegalArgumentException(
          s"PersistentLaplacian.ofMap: the source holds the simplex ${simplex.underlying.mkString("{", ", ", "}")} but not its face " +
            s"${face.underlying.mkString("{", ", ", "}")}"
        )
      )
      d(row)(column) = coefficient
    val e = Array.ofDim[Double](b, c)
    for (simplex, column) <- coboundaryCells.zipWithIndex; (face, coefficient) <- targetCells.boundary(simplex)[Double]
    do
      val row = cellRow.getOrElse(
        face,
        throw IllegalArgumentException(
          s"PersistentLaplacian.ofMap: the target holds the simplex ${simplex.underlying.mkString("{", ", ", "}")} but not its face " +
            s"${face.underlying.mkString("{", ", ", "}")}"
        )
      )
      e(row)(column) = coefficient * math.sqrt(coboundaryWeight(column) / cellWeight(row))
    // `tabulate`, not `apply`: an empty array of rows has no width to read
    def matrix(rows: Int, cols: Int, entries: Array[Array[Double]]) =
      DenseMatrix.tabulate(rows, cols)((i, j) => entries(i)(j))
    val (fMatrix, dMatrix, eMatrix) = (matrix(b, a, f), matrix(aBelow, a, d), matrix(b, c, e))

    // down: B = D^T on the chains of the source whose coboundary is orthogonal to the kernel of F; the part of it that F keeps
    val keptByF = fMatrix.transpose * fMatrix
    val down =
      if aBelow == 0 || a == 0 then DenseMatrix.zeros(b, 0)
      else
        val coboundary = dMatrix.transpose
        val orthogonalToKernel = coboundary - keptByF * coboundary
        fMatrix * (coboundary * nullSpace(orthogonalToKernel))
    // up: the boundaries of the chains of the target whose boundary is the image of a cycle of the source
    val up =
      if c == 0 || b == 0 then DenseMatrix.zeros(b, 0)
      else
        val imageOfCycles = fMatrix * nullSpace(dMatrix)
        val outside =
          if imageOfCycles.cols == 0 then eMatrix
          else
            val span = imageOfCycles.svd
            val rank = span.rank(math.max(span.tolerance, FloorOfRank))
            val basis = DenseMatrix.tabulate(b, rank)((i, j) => span.u(i, j))
            eMatrix - basis * (basis.transpose * eMatrix)
        eMatrix * nullSpace(outside)

    val rows = cells.indices.filter(hit).toVector
    val factor = DenseMatrix.tabulate(rows.size, down.cols + up.cols)((i, j) =>
      if j < down.cols then down(rows(i), j) else up(rows(i), j - down.cols)
    )
    MapResult(q, rows.map(cells), factor, targetCells.ordering, rows.map(cellWeight))

  /** The persistent Laplacian of the simplicial map `vertexMap` from the complex of `source` at the filtration value
    * `from` to the complex of `target` at `to`: [[ofMap]] on their simplices of those values. For the two steps of a
    * filtered complex, `source` and `target` are one stream, and a map that is the identity on vertices is the
    * persistent Laplacian of the stream from `from` to `to`.
    */
  def ofMap[V: Ordering, W: Ordering](
    source: StratifiedCellStream[Simplex[V], Double],
    from: Double,
    target: StratifiedCellStream[Simplex[W], Double],
    to: Double,
    dimension: Int
  )(vertexMap: V => W): MapResult[Simplex[W]] =
    require(dimension >= 0, s"PersistentLaplacian: the dimension is $dimension, but it cannot be negative")
    Seq(source, target).foreach(stream =>
      stream.homologyDegreeLimit.foreach(limit =>
        require(
          dimension <= limit,
          s"PersistentLaplacian.ofMap: a stream holds the cells for homology in degrees up to $limit; build it with maxDimension >= $dimension"
        )
      )
    )
    def cut[X](stream: StratifiedCellStream[Simplex[X], Double], value: Double): Vector[Simplex[X]] =
      val filtration = stream.filtrationValue
      (dimension - 1 to dimension + 1).toVector.flatMap(layerOf(stream, _)).filter(filtration(_) <= value)
    ofMap(cut(source, from), cut(target, to), dimension)(vertexMap)

  /** An orthonormal basis of the null space of `matrix`, as columns: the right singular vectors of the matrix, padded
    * with zero rows until it has as many rows as columns (so that the vectors are all of them, an orthogonal matrix by
    * construction), beyond its rank.
    */
  private def nullSpace(matrix: DenseMatrix): DenseMatrix =
    val n = matrix.cols
    if n == 0 then DenseMatrix.zeros(0, 0)
    else
      val padded =
        if matrix.rows >= n then matrix
        else DenseMatrix.tabulate(n, n)((i, j) => if i < matrix.rows then matrix(i, j) else 0.0)
      val decomposition = padded.svd
      val rank = decomposition.rank(math.max(decomposition.tolerance, FloorOfRank))
      DenseMatrix.tabulate(n, n - rank)((i, j) => decomposition.v(i, rank + j))
