package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The benchmark driver's V-construction input (`construction=V`): an array of vertex values is run as the
  * T-construction image that gives every cell the largest value of its vertices. The refinement is checked cell by cell
  * against the definition, and the barcode on a vertex array where the V and the T construction disagree.
  */
class PaperBenchmarkDriverSpec extends Specification:
  private val refine = PaperBenchmarkDriver.refineVertexValues

  // The definition: cell (i_1, .., i_d) of the (2 n_k - 1)-grid has the vertices i_k / 2 on the axes where i_k is even
  // and i_k / 2, i_k / 2 + 1 where it is odd; its value is the largest value among them.
  private def definition(values: Array[Double], shape: IndexedSeq[Int]): Array[Double] =
    val refinedShape = shape.map(2 * _ - 1)
    Array.tabulate(refinedShape.product) { flat =>
      val index = refinedShape.indices.map(axis => (flat / refinedShape.drop(axis + 1).product) % refinedShape(axis))
      val vertices = index
        .map(i => if i % 2 == 0 then List(i / 2) else List(i / 2, i / 2 + 1))
        .foldLeft(List(List.empty[Int]))((prefixes, choices) => for prefix <- prefixes; c <- choices yield prefix :+ c)
      vertices.map(v => values(v.zip(shape).foldLeft(0) { case (flatIndex, (i, n)) => flatIndex * n + i })).max
    }

  private def randomValues(shape: IndexedSeq[Int], seed: Long): Array[Double] =
    val rng = scala.util.Random(seed)
    Array.fill(shape.product)(rng.nextInt(7).toDouble) // ties on purpose

  private def bars(values: Array[Double], shape: IndexedSeq[Int], dim: Int) =
    Persistence(Image(values.toIndexedSeq, shape), maxDimension = dim, characteristic = 2).triples.sorted

  "refineVertexValues" should {
    "give every cell the largest value of its vertices, in 1, 2 and 3 dimensions" in {
      val shapes = List(IndexedSeq(5), IndexedSeq(3, 4), IndexedSeq(4, 1), IndexedSeq(2, 3, 2), IndexedSeq(3, 3, 3))
      val wrong = for
        shape <- shapes
        seed <- 1L to 4L
        values = randomValues(shape, seed)
        (refined, refinedShape) = refine(values, shape)
        if refinedShape != shape.map(2 * _ - 1) || !java.util.Arrays.equals(refined, definition(values, shape))
      yield s"shape $shape seed $seed"
      wrong must beEmpty
    }
    "keep the vertex values where every index is even" in {
      val values = randomValues(IndexedSeq(4, 5), 11L)
      val (refined, shape) = refine(values, IndexedSeq(4, 5))
      val kept = for i <- 0 until 4; j <- 0 until 5 yield refined(2 * i * shape(1) + 2 * j) == values(i * 5 + j)
      kept.forall(identity) must beTrue
    }
  }

  "the V-construction through the refinement" should {
    // Two low vertices on a diagonal of one square. Each is a component at 0; the edges of the square all contain a
    // vertex of value 5, so they are born at 5, and the two components join there. As top cells of a T-construction
    // image the same four values touch at their common corner, which is born at 0: one component, no finite bar.
    val checkerboard = Array(0.0, 5.0, 5.0, 0.0)
    val shape = IndexedSeq(2, 2)

    "see the two components of the checkerboard that the T-construction joins" in {
      val (refined, refinedShape) = refine(checkerboard, shape)
      bars(refined, refinedShape, 1) === List((0, 0.0, 5.0), (0, 0.0, Double.PositiveInfinity))
      bars(checkerboard, shape, 1) === List((0, 0.0, Double.PositiveInfinity))
    }
    // A ring of low vertices around a high one: the loop of the ring is born at 0 and filled at 5, when the squares
    // around the central vertex appear.
    "see the loop around a high vertex, born with the ring and filled with the squares that contain the vertex" in {
      val ring = Array(0.0, 0.0, 0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0)
      val (refined, refinedShape) = refine(ring, IndexedSeq(3, 3))
      bars(refined, refinedShape, 1) === List((0, 0.0, Double.PositiveInfinity), (1, 0.0, 5.0))
    }
  }
