package org.appliedtopology.tda4j

import org.specs2.mutable

/** The alpha complex shares its work between threads (the frontier walk evaluates queued facets in batches, the face
  * tables are built on partitions of a hash, values and the sort run in parallel) and promises the same answer however
  * many threads there are: every simplex, every value to the last bit and the stream order. Each example makes a
  * complex twice, once inside `Parallel.sequentially`, and compares everything a user or an engine can read.
  */
class ParallelAlphaSpec extends mutable.Specification:
  given Epsilon = Epsilon(1e-5)

  def cloud(dim: Int, n: Int, seed: Int): Array[Array[Double]] =
    val rng = new scala.util.Random(31000L * dim + seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  /** Everything that can be read from the complex: per dimension the stream (its order is part of the contract) with
    * the bits of each value.
    */
  def everything(alpha: DelaunayAlphaShapes): Seq[(Int, Seq[(Seq[Int], Long)])] =
    (0 to alpha.ambientDimension).map { k =>
      k -> alpha
        .iterateDimension(k)
        .map(s => s.toSeq -> java.lang.Double.doubleToLongBits(alpha.filtrationValue(s)))
        .toSeq
    }

  /** The cases handed to `FaceTable.facesOf` below: random `width`-subsets of `vertices` vertices. */
  def table(width: Int, rows: Int, vertices: Int, seed: Int): DelaunayAlphaShapes.FaceTable =
    val rng = new scala.util.Random(seed)
    val t = DelaunayAlphaShapes.FaceTable(width, rows)
    while t.size < rows do t.add(rng.shuffle((0 until vertices).toList).take(width).sorted.toArray)
    t

  "A Helix triangulation is the same complex, to the bit, with or without threads" >> {
    val problems = for
      (dim, n, seed) <- Seq((5, 140, 1), (4, 200, 2), (6, 70, 3))
      pts = cloud(dim, n, seed)
      sequential = Parallel.sequentially(everything(HelixDelaunay(pts)))
      parallel = everything(HelixDelaunay(pts))
      if sequential != parallel
    yield s"$dim-D, $n points"
    problems must beEmpty
  }

  "A Bowyer-Watson complex is the same complex, to the bit, with or without threads" >> {
    val n = TestPlatform.perPlatform(jvm = 6000, js = 600)
    val pts = cloud(3, n, 4)
    val sequential = Parallel.sequentially(everything(BowyerWatsonDelaunay(pts)))
    (sequential == everything(BowyerWatsonDelaunay(pts))) must beTrue
  }

  "The faces of a table are numbered by first occurrence, however many threads find them" >> {
    // 4-subsets of 60 vertices, 20000 of them: 80000 (row, omitted vertex) pairs, past the size where threads share them.
    val upper = table(4, 20000, 60, 5)
    val (seqFaces, seqRows) = Parallel.sequentially(DelaunayAlphaShapes.FaceTable.facesOf(upper))
    val (parFaces, parRows) = DelaunayAlphaShapes.FaceTable.facesOf(upper)
    val sameRows = seqFaces.size == parFaces.size && (0 until seqFaces.size).forall(r =>
      (0 until 3).forall(i => seqFaces.vertex(r, i) == parFaces.vertex(r, i))
    )
    val found = (0 until parFaces.size).forall { r =>
      val face = Array.tabulate(3)(i => parFaces.vertex(r, i))
      parFaces.indexOf(face) == r
    }
    val absent = parFaces.indexOf(Array(0, 1, 100)) == -1
    (parRows.toSeq == seqRows.toSeq).aka("the row of every pair") must beTrue
    sameRows.aka("the numbered faces") must beTrue
    found.aka("every face found by its vertices") must beTrue
    absent.aka("a face that is not there") must beTrue
  }

  /** The dimensions of `alpha` as `flatLayers` gives them, each as the stream lists it: cells and values. */
  def listing(layers: Iterator[FlatLayerStream.Layer]): Seq[(Int, Seq[Seq[Int]], Seq[Long])] =
    layers.map { layer =>
      val cells = layer.simplicesInStreamOrder
      (layer.dim, cells.map(_.toSeq), layer.values.toSeq.map(java.lang.Double.doubleToLongBits))
    }.toSeq

  def streamListing(alpha: AlphaShapes, dimensions: Range): Seq[(Int, Seq[Seq[Int]], Seq[Long])] =
    dimensions.map { d =>
      val cells = alpha.iterateDimension(d).toVector
      // The layer lists equal values in the stream's order too, but its values are in the layer's order: ascending.
      (d, cells.map(_.toSeq), cells.map(c => java.lang.Double.doubleToLongBits(alpha.filtrationValue(c))))
    }

  "The flat layers of an alpha complex are its dimensions: the same cells, in the stream's order, with the same values" >> {
    val problems = for
      (name, alpha) <- Seq[(String, DelaunayAlphaShapes)](
        "3-D, Bowyer-Watson" -> BowyerWatsonDelaunay(cloud(3, 150, 6)),
        "2-D grid, Bowyer-Watson" -> BowyerWatsonDelaunay(
          Array.tabulate(36)(k => Array((k % 6).toDouble, (k / 6).toDouble))
        ),
        "5-D, Helix" -> HelixDelaunay(cloud(5, 40, 7))
      )
      layers = alpha.flatLayers.getOrElse(sys.error(s"$name has no flat layers"))
      flat = listing(layers)
      if flat != streamListing(alpha, 0 to alpha.ambientDimension)
    yield name
    problems must beEmpty
  }

  "Repeated points leave the alpha complex without flat layers: their cells are not in the tables" >> {
    val pts = cloud(3, 20, 8) :+ cloud(3, 20, 8).head
    BowyerWatsonDelaunay(pts).flatLayers must beNone
  }

  "A radius-limited alpha complex cuts the flat layers where it cuts the stream" >> {
    val full = BowyerWatsonDelaunay(cloud(3, 150, 9))
    val limited = RadiusLimitedAlphaShapes(full, 0.12)
    val flat = listing(limited.flatLayers.get)
    (flat.map(_._1) == (0 until flat.size)).aka("the layers are dimensions 0, 1, ...") must beTrue
    (flat == streamListing(limited, 0 until flat.size)).aka("the layers' cells and values") must beTrue
    // and nothing is left out: the stream has no dimension the layers lack
    (flat.size to full.ambientDimension)
      .forall(d => limited.iterateDimension(d).isEmpty)
      .aka("nothing above the layers") must beTrue
  }

  "The verb gives the same bars and representatives from flat layers as from the cells" >> {
    val pts = cloud(4, 90, 10)
    val alpha = HelixDelaunay(pts)
    val flat = Persistence(Truncated(alpha, 2), characteristic = 17)
    // a stream that does not hand over arrays: the verb reads its cells
    val cells = Persistence(Truncated(LimitedAlphaShapesStream(alpha, 3), 2), characteristic = 17)
    def readable(d: PersistenceDiagram[Simplex[Int]]) = d.bars.map(b => (b.toTriple, b.annotation.toString))
    (readable(flat) == readable(cells)).aka("bars and representatives") must beTrue
  }
