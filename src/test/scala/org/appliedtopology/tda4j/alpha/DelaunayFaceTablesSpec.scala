package org.appliedtopology.tda4j

import org.specs2.mutable

/** `DelaunayAlphaShapes` keeps the faces of a triangulation and their alpha values in flat tables, and makes the
  * `Simplex` objects of a dimension when that dimension is first asked for (the verb reads the dimensions up to
  * `maxDimension + 1` and never the millions of faces above them). These examples pin that the tables answer like the
  * objects would:
  *   - the stream of a dimension does not depend on which dimensions were asked for before it;
  *   - every dimension is in `filtrationOrdering` order, ties included, and values never decrease to a coface;
  *   - a top simplex has the smallest circumradius of the cells that contain it, on random clouds and on grids, where
  *     whole cubes are cospherical and tiled;
  *   - repeated points are vertices joined to the one kept by an edge of value 0, and a simplex outside the complex is
  *     an error rather than a wrong value;
  *   - a dimension outside `0..ambient` is not in the stream.
  */
class DelaunayFaceTablesSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def cloud(dim: Int, n: Int, seed: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(7000L * dim + seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  def grid(side: Int, dim: Int): Array[Array[Double]] =
    Array.tabulate(math.pow(side, dim).toInt)(k =>
      Array.tabulate(dim)(a => ((k / math.pow(side, a).toInt) % side).toDouble)
    )

  val inputs: Seq[(String, Array[Array[Double]])] =
    Seq(
      "3-D cloud" -> cloud(3, 30, 1),
      "5-D cloud" -> cloud(5, 25, 2),
      "6x6 grid" -> grid(6, 2),
      "4x4x4 grid" -> grid(4, 3)
    )

  "The stream of a dimension does not depend on the dimensions asked for before it" >> {
    val problems = for
      (name, pts) <- inputs
      forward = HelixDelaunay(pts)
      backward = HelixDelaunay(pts)
      top = forward.ambientDimension
      ascending = (0 to top).map(d => forward.iterateDimension(d).toVector)
      descending = (top to 0 by -1).map(d => backward.iterateDimension(d).toVector).reverse
      if ascending != descending
    yield name
    problems must beEmpty
  }

  "Every dimension is in filtrationOrdering order, and values do not decrease from a face to a coface" >> {
    val problems = for
      (name, pts) <- inputs
      helix = HelixDelaunay(pts)
      order = helix.filtrationOrdering.reverse
      d <- 0 to helix.ambientDimension
      stream = helix.iterateDimension(d).toVector
      problem <- Seq(
        Option.when(stream != stream.sorted(using order))("out of order"),
        Option.when(
          d > 0 && stream.exists(s => s.toSeq.exists(v => helix.filtrationValue(s - v) > helix.filtrationValue(s)))
        )("a face with a larger value than its coface")
      ).flatten
    yield s"$name, dimension $d: $problem"
    problems must beEmpty
  }

  "A top simplex has the smallest circumradius of the cells that contain it" >> {
    val problems = for
      (name, pts) <- inputs
      helix = HelixDelaunay(pts)
      s <- helix.iterateDimension(helix.ambientDimension).toVector
      expected = helix.validated.filter(c => s.toSet.subsetOf(c.simplex.toSet)).map(_.circumsphere.radius).min
      if helix.filtrationValue(s) != expected
    yield s"$name: ${s.toSeq.mkString(",")} has ${helix.filtrationValue(s)}, expected $expected"
    problems must beEmpty
  }

  "A simplex claimed by several cells keeps the smallest radius, and a larger cell gives its radius to its subsets" >> {
    def cell(radius: Double, vertices: Int*) =
      DelaunaySimplex(Simplex.from(vertices), Hypersphere(AlphaShapes.Point(Array(0.0, 0.0)), radius))
    val radii = HelixDelaunay.smallestCircumradii(
      Seq(
        cell(3.0, 0, 1, 2), // claimed twice: the 2.0 below wins
        cell(2.0, 0, 1, 2),
        cell(5.0, 3, 4, 5, 6), // a cluster of four taken whole: each of its triples gets 5.0 ...
        cell(1.0, 4, 5, 6) // ... unless a cell of its own has less
      ),
      top = 2
    )
    (radii(Simplex(0, 1, 2)) must be_==(2.0)) and
      (radii(Simplex(3, 4, 5)) must be_==(5.0)) and
      (radii(Simplex(3, 4, 6)) must be_==(5.0)) and
      (radii(Simplex(3, 5, 6)) must be_==(5.0)) and
      (radii(Simplex(4, 5, 6)) must be_==(1.0)) and
      (radii.size must be_==(5))
  }

  "Repeated points are vertices joined to the one kept by an edge of value 0" >> {
    val base = cloud(3, 20, 3)
    val pts = base ++ Array(base(3).clone(), base(7).clone())
    val delaunay = BowyerWatsonDelaunay(pts)
    val vertices = delaunay.iterateDimension(0).toVector
    val zeroEdges = delaunay.iterateDimension(1).filter(e => delaunay.filtrationValue(e) == 0.0).toVector
    (vertices.size must be_==(22)) and
      (delaunay.filtrationValue(Simplex(20)) must be_==(0.0)) and
      (delaunay.filtrationValue(Simplex(21)) must be_==(0.0)) and
      (zeroEdges.map(_.toSet).toSet must be_==(Set(Set(3, 20), Set(7, 21))))
  }

  "A simplex outside the complex is an error" >> {
    val helix = HelixDelaunay(cloud(3, 20, 4))
    helix.filtrationValue(Simplex(0, 1, 2, 3, 4)) must throwA[NoSuchElementException]
  }

  "Only the dimensions 0 to the ambient dimension are in the stream" >> {
    val helix = HelixDelaunay(cloud(4, 20, 5))
    val top = helix.ambientDimension
    (helix.iterateDimension.isDefinedAt(-1) must beFalse) and
      (helix.iterateDimension.isDefinedAt(top + 1) must beFalse) and
      (helix.iterateDimension.isDefinedAt(top) must beTrue) and
      (helix.simplicesInDimension(top + 1) must throwA[NoSuchElementException]) and
      (helix.simplicesMap.keySet must be_==((0 to top).toSet))
  }
