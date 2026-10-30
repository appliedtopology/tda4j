package org.appliedtopology.tda4j

/** Filtered simplicial complexes built the slow way, for oracles that must not share code with the library: the
  * Vietoris-Rips complex by enumerating vertex subsets, boundaries by the alternating sum, and matrices of linear maps
  * as plain integer rows.
  */
object BruteForceComplexes:

  /** The simplices of a filtered complex with their filtration values. */
  type Complex = Map[Simplex[Int], Double]

  /** The Vietoris-Rips complex of `points` with simplices up to `size` vertices. */
  def rips(points: Array[Array[Double]], size: Int): Complex =
    def distance(a: Int, b: Int) = math.sqrt(points(a).zip(points(b)).map((x, y) => (x - y) * (x - y)).sum)
    (for
      count <- 1 to size
      vertices <- points.indices.combinations(count)
    yield Simplex.from(vertices) -> (if count == 1 then 0.0
                                     else vertices.combinations(2).map(pair => distance(pair(0), pair(1))).max)).toMap

  /** The simplices of dimension `k`, in a fixed order. */
  def ofDim(complex: Complex, k: Int): Vector[Simplex[Int]] =
    if k < 0 then Vector.empty else complex.keys.filter(_.dim == k).toVector.sortBy(_.toList.mkString(","))

  /** The faces of a simplex with the signs of the alternating sum. */
  def boundary(simplex: Simplex[Int]): Seq[(Simplex[Int], Int)] =
    val vertices = simplex.toList
    if vertices.size < 2 then Seq.empty
    else vertices.indices.map(i => (Simplex.from(vertices.patch(i, Nil, 1)), if i % 2 == 0 then 1 else -1))

  /** The matrix of a linear map, a column for each `from` simplex: `image(column)` gives its entries by `to` simplex.
    */
  def matrixOf(
    to: Vector[Simplex[Int]],
    from: Vector[Simplex[Int]]
  )(image: Simplex[Int] => Seq[(Simplex[Int], Int)]): Seq[Seq[Int]] =
    val columns = from.map(s => image(s).groupMapReduce(_._1)(_._2)(_ + _))
    to.indices.map(r => from.indices.map(c => columns(c).getOrElse(to(r), 0)))

  /** `vector` over the simplices `inside`, as a vector over all of `whole` (zero elsewhere). */
  def embed(vector: Seq[Int], inside: Vector[Simplex[Int]], whole: Vector[Simplex[Int]]): Seq[Int] =
    val at = whole.zipWithIndex.toMap
    val result = Array.fill(whole.size)(0)
    inside.zip(vector).foreach((cell, c) => result(at(cell)) = c)
    result.toSeq
end BruteForceComplexes
