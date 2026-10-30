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

  /** The persistent Laplacian of one dimension and one pair of values: `Δ = X X^T` for a factor `X` with a column for
    * each boundary and coboundary direction, so that the eigenvalues are the squares of its singular values (no squared
    * condition number) and the kernel is found by the rank of `X`.
    */
  // `final` and a private constructor: `factor` is the one state, and `matrix` and `eigenvalues` are functions of it.
  final class Result[CellT] private[tda4j] (
    val dimension: Int,
    val from: Double,
    val to: Double,
    /** The cells of `K_s` of this dimension: the rows and columns of the matrix, in the stream's order. */
    val cells: Vector[CellT],
    private val factor: DenseMatrix
  ):
    private lazy val decomposition = factor.svd

    /** The matrix of `Δ_k^{from,to}`, on [[cells]]. */
    lazy val matrix: DenseMatrix = factor * factor.transpose

    /** The eigenvalues, smallest first, as many as there are cells. */
    lazy val eigenvalues: Vector[Double] =
      val squares = decomposition.singularValues.map(s => s * s).sorted.toVector
      Vector.fill(cells.size - squares.size)(0.0) ++ squares

    /** The dimension of the kernel: the persistent Betti number `β_k^{from,to}`, the number of eigenvalues that are
      * zero to rounding.
      */
    lazy val bettiNumber: Int = cells.size - decomposition.rank

    /** The smallest eigenvalue that is not zero to rounding, if there is one. */
    lazy val spectralGap: Option[Double] =
      val tolerance = decomposition.tolerance
      decomposition.singularValues.filter(_ > tolerance).lastOption.map(s => s * s)

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
    def layer(d: Int): Vector[CellT] =
      if d >= 0 && stream.iterateDimension.isDefinedAt(d) then stream.iterateDimension(d).toVector else Vector.empty
    val value = stream.filtrationValue
    val (own, above, below) = (layer(dimension), layer(dimension + 1), layer(dimension - 1))
    val cells = own.filter(value(_) <= from)
    val enteringLater = own.filter(c => value(c) > from && value(c) <= to)
    val coboundaryCells = above.filter(value(_) <= to)
    val (rowOf, laterRowOf) = (cells.zipWithIndex.toMap, enteringLater.zipWithIndex.toMap)
    val n = cells.size
    if n == 0 then return Result(dimension, from, to, cells, DenseMatrix.zeros(0, 0))

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
    Result(dimension, from, to, cells, factor)
