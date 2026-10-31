package org.appliedtopology.tda4j

/** The pair `(X, A)` as a stream: the cells of `X` that are not in the subcomplex `A`, with boundaries that leave out
  * the faces in `A`, so that its persistent homology is the relative one, `H(X_t, A_t)` with `A_t = A ∩ X_t`.
  *
  * {{{
  * val rips = VietorisRips(points, maxDimension = 2)
  * Persistence(Relative.ofVertices(rips, Set(0, 1, 2)))   // the pairs of the Rips complex modulo the one on 0, 1, 2
  * }}}
  *
  * The subcomplex has to be closed under faces (a face of a cell in `A` is in `A`), and each of its cells enters `X` no
  * later than it would as a cell of `X` on its own; neither is checked. The bars carry chains of [[RelativeCell]]s, the
  * cell of `X` being `cell`. For a connected complex whose vertices all enter together, relative to one vertex, that is
  * the reduced homology: the bar of the whole complex in degree 0 is gone and the rest stays.
  */
object Relative:

  /** `stream` modulo the cells for which `inSubcomplex` holds. */
  def apply[CellT: OrderedCell as cells](
    stream: StratifiedCellStream[CellT, Double],
    inSubcomplex: CellT => Boolean
  ): StratifiedCellStream[RelativeCell[CellT], Double] =
    val context = RelativeCell.Context(inSubcomplex, cells)
    val ambientValue = stream.filtrationValue
    val ambientOrdering = stream.filtrationOrdering
    new StratifiedCellStream[RelativeCell[CellT], Double]:
      val filtrationValue: PartialFunction[RelativeCell[CellT], Double] =
        new PartialFunction[RelativeCell[CellT], Double]:
          def isDefinedAt(r: RelativeCell[CellT]): Boolean = ambientValue.isDefinedAt(r.cell)
          def apply(r: RelativeCell[CellT]): Double = ambientValue(r.cell)
          override def applyOrElse[A1 <: RelativeCell[CellT], B1 >: Double](r: A1, default: A1 => B1): B1 =
            ambientValue.applyOrElse(r.cell, (_: CellT) => default(r))
      val filtrationOrdering: Ordering[RelativeCell[CellT]] = new Ordering[RelativeCell[CellT]]:
        def compare(x: RelativeCell[CellT], y: RelativeCell[CellT]): Int = ambientOrdering.compare(x.cell, y.cell)
      def smallest: Double = stream.smallest
      def largest: Double = stream.largest
      override def homologyDegreeLimit: Option[Int] = stream.homologyDegreeLimit
      def iterateDimension: PartialFunction[Int, Iterator[RelativeCell[CellT]]] =
        case d if stream.iterateDimension.isDefinedAt(d) =>
          stream.iterateDimension(d).filterNot(inSubcomplex).map(RelativeCell(_)(context))

  /** `grid` modulo its boundary, `H(X_t, X_t ∩ ∂X)` for the image or volume `X` the grid fills: the cubes that are not
    * on the boundary ([[CubicalGridStream.onBoundary]]). A curve that runs from boundary to boundary is a cycle of the
    * pair, like a closed one. The image itself is the top class, born when its last pixel enters.
    *
    * {{{
    * val grid = CubicalImage.fromGrayscale2D(pixels)
    * Persistence(Relative.ofBoundary(grid), maxDimension = 2)
    * }}}
    */
  def ofBoundary(grid: CubicalGridStream): StratifiedCellStream[RelativeCell[Cube], Double] =
    apply(grid, grid.onBoundary)

  /** `stream` of simplices modulo the subcomplex of the simplices all of whose vertices are in `vertices`. */
  def ofVertices[VertexT: Ordering](
    stream: StratifiedCellStream[Simplex[VertexT], Double],
    vertices: Set[VertexT]
  ): StratifiedCellStream[RelativeCell[Simplex[VertexT]], Double] =
    apply(stream, simplex => simplex.toList.forall(vertices))
end Relative
