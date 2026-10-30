package org.appliedtopology.tda4j

import cats.Show

/** An ordered simplex `(v0, v1, ..., vk)`: distinct vertices in a fixed order, a cell of the directed flag complex of a
  * directed graph (the cliques whose edges all point from earlier to later vertices). Unlike a [[Simplex]], the order
  * matters: `(0, 1)` and `(1, 0)` are different edges. Its faces are the tuples with one vertex removed, with the sign
  * `(-1)^i` for removing position `i`.
  */
final case class DirectedSimplex(vertices: Vector[Int]):
  require(vertices.nonEmpty, "DirectedSimplex: a simplex has at least one vertex")
  require(vertices.distinct.size == vertices.size, s"DirectedSimplex: the vertices ${vertices.mkString(",")} repeat")
  def dim: Int = vertices.length - 1
  override def toString: String = vertices.mkString("(", ",", ")")

object DirectedSimplex:
  /** `DirectedSimplex(0, 1, 2)`. */
  def apply(first: Int, rest: Int*): DirectedSimplex = DirectedSimplex(first +: rest.toVector)

  /** Smaller dimension first, then lexicographic by vertex. */
  val intrinsicOrdering: Ordering[DirectedSimplex] = new Ordering[DirectedSimplex]:
    def compare(x: DirectedSimplex, y: DirectedSimplex): Int =
      if x.vertices.length != y.vertices.length then Integer.compare(x.vertices.length, y.vertices.length)
      else
        var i = 0
        var result = 0
        while result == 0 && i < x.vertices.length do
          result = Integer.compare(x.vertices(i), y.vertices(i))
          i += 1
        result

  /** `DirectedSimplex is OrderedCell` with a chosen order, a stream's filtration order for instance. */
  def isOrderedCell(order: Ordering[DirectedSimplex] = intrinsicOrdering): DirectedSimplex is OrderedCell =
    new (DirectedSimplex is OrderedCell):
      override lazy val ordering = order
      extension (s: DirectedSimplex)
        override def dim: Int = s.vertices.length - 1
        override def boundary[CoefficientT: Field as fr]: Seq[(DirectedSimplex, CoefficientT)] =
          if s.vertices.length <= 1 then Seq.empty
          else
            s.vertices.indices.map { i =>
              (DirectedSimplex(s.vertices.patch(i, Nil, 1)), if i % 2 == 0 then fr.one else fr.negate(fr.one))
            }

  given directedSimplexIsOrderedCell: (DirectedSimplex is OrderedCell) = isOrderedCell()

  given directedSimplexShow: Show[DirectedSimplex] = Show.show(_.toString)
end DirectedSimplex
