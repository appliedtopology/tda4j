package org.appliedtopology.tda4j

import scala.collection.immutable.ArraySeq

/** A stream of simplices with `Int` vertices and `Double` values that can hand over its dimensions as flat arrays,
  * without building a [[Simplex]] for each cell. A cohomology engine ([[CellularCohomologyEngine]]) reduces such a
  * stream straight from the arrays, and builds a `Simplex` only for the cells of the representatives it returns.
  *
  * The coface streams behind [[Cech]], [[Witness]], [[Dowker]], [[DtmRips]] and [[SparseRips]] are the implementations.
  * To build a new filtered complex on arrays, extend [[RipserCofaceSimplexStream]] and give it a
  * [[RipserCofaceSimplexStream.FlatFiltration]].
  */
trait FlatLayerStream:

  /** The dimensions `0, 1, ...` as [[FlatLayerStream.Layer]]s, each built when the iterator reaches it, up to the last
    * one that has a cell. `None` for a stream that cannot give its cells as arrays (then iterate it for cells).
    */
  def flatLayers: Option[Iterator[FlatLayerStream.Layer]]

object FlatLayerStream:

  /** The simplex with the ascending vertices `vertices(from until from + width)`. */
  def simplexOf(vertices: Array[Int], from: Int, width: Int): Simplex[Int] =
    Simplex.from(ArraySeq.unsafeWrapArray(java.util.Arrays.copyOfRange(vertices, from, from + width)))

  /** One dimension of a complex on flat arrays, oldest cell first: ascending value, equal values in ascending
    * colexicographic order of the vertices (the order a persistent-cohomology reduction takes the cells in, and the
    * reverse of each run of equal values in the stream's own listing).
    *
    * Cell `i` has the ascending vertices `vertices(i * (dim + 1) until (i + 1) * (dim + 1))` and the filtration value
    * `values(i)`.
    */
  open class Layer(val dim: Int, val vertices: Array[Int], val values: Array[Double]):
    require(vertices.length == (dim + 1) * values.length, "a layer holds dim + 1 vertices per value")

    def size: Int = values.length

    /** Cell `i` as a [[Simplex]]. */
    def simplex(i: Int): Simplex[Int] = simplexOf(vertices, i * (dim + 1), dim + 1)

    /** The cells as simplices in the stream's own listing order: ascending value, equal values in descending
      * colexicographic order.
      */
    def simplicesInStreamOrder: Vector[Simplex[Int]] =
      val out = Vector.newBuilder[Simplex[Int]]
      out.sizeHint(size)
      var start = 0
      while start < size do
        var end = start + 1
        while end < size && java.lang.Double.compare(values(start), values(end)) == 0 do end += 1
        var i = end - 1
        while i >= start do
          out += simplex(i)
          i -= 1
        start = end
      out.result()
