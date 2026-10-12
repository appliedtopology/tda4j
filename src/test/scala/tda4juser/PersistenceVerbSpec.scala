package tda4juser

import org.specs2.mutable.Specification

/** The one-call verb, from a user's side of the package boundary: one import, every input shape, and answers that agree
  * with the explicit engine path, between engines, and with the cursor's own truncation contract.
  */
class PersistenceVerbSpec extends Specification:
  import org.appliedtopology.tda4j.*
  import org.appliedtopology.tda4j.sset.*

  private def circle(n: Int, noise: Double = 0.0, seed: Int = 1): Array[Array[Double]] =
    val rnd = new scala.util.Random(seed)
    Array.tabulate(n) { i =>
      val t = 2 * math.Pi * i / n
      Array(math.cos(t) + noise * rnd.nextGaussian(), math.sin(t) + noise * rnd.nextGaussian())
    }
  private def norm(ts: List[(Int, Double, Double)]) =
    ts.filter((d, b, e) => e > b)
      .map((d, b, e) => (d, math.rint(b * 1e9) / 1e9, if e.isInfinite then e else math.rint(e * 1e9) / 1e9))
      .sorted

  "Persistence(points)" should {
    "find the circle's loop as its longest H1 bar, with a representative cycle from Engine.Chunks" in {
      val d = Persistence(circle(16), maxFiltrationValue = 2.0, engine = Persistence.Engine.Chunks)
      import d.given
      val loop = d.dim(1).longest.get
      val rep = loop.annotation.get
      (loop.persistence must beGreaterThan(0.5))
        .and(rep.rawEntries.nonEmpty must beTrue)
        .and(Chain.from(rep.boundary).isZero() must beTrue)
    }
    "agree with the explicit engine path over F_17 (the default field)" in {
      val pts = circle(12, noise = 0.05)
      val viaVerb = Persistence(pts, maxFiltrationValue = 1.5).triples
      val ff = FiniteField(17)
      import ff.given
      val viaEngine = SimplicialHomologyEngine[Int, ff.Fp, Double]()
        .persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1, Some(1.5)))
        .diagramAt(Double.PositiveInfinity)
        .filter(_._1 <= 1)
      norm(viaVerb) must beEqualTo(norm(viaEngine))
    }
    "accept every point-cloud shape and every way of giving the threshold, with the same answer" in {
      val pts = circle(10, noise = 0.05, seed = 3)
      val reference = norm(Persistence(pts, maxFiltrationValue = 1.5).triples)
      val shapes = Seq(
        Persistence(pts.toSeq.map(_.toSeq), maxFiltrationValue = 1.5),
        Persistence(pts.toSeq, maxFiltrationValue = Some(1.5)),
        Persistence(pts.toList.map(_.toVector), maxFiltrationValue = 1.5),
        Persistence(EuclideanMetricSpace(pts), maxFiltrationValue = 1.5)
      )
      (shapes.map(d => norm(d.triples)) must contain(be_==(reference)).forall)
        .and(
          norm(Persistence(pts, maxFiltrationValue = 2).triples) must beEqualTo(
            norm(Persistence(pts, maxFiltrationValue = 2.0).triples)
          )
        )
    }
    "give the same diagram with the naive and the chunks engine" in {
      val pts = circle(11, noise = 0.1, seed = 5)
      norm(Persistence(pts, maxFiltrationValue = 1.2, engine = Persistence.Engine.Naive).triples) must beEqualTo(
        norm(Persistence(pts, maxFiltrationValue = 1.2).triples)
      )
    }
    "read .at(f) exactly as the cursor's diagramAt(f) does" in {
      val pts = circle(10, noise = 0.1, seed = 7)
      val d = Persistence(pts, maxFiltrationValue = 1.5)
      val ff = FiniteField(17)
      import ff.given
      val cursor = SimplicialHomologyEngine[Int, ff.Fp, Double]()
        .persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1, Some(1.5)))
      Seq(1.5, 0.1, 0.4, 0.7, 5.0).forall(f =>
        norm(d.at(f).triples) == norm(cursor.diagramAt(f).filter(_._1 <= 1))
      ) must beTrue
    }
    "compute degrees 0..2 with Ripser, the same bars as Engine.Chunks, with cocycles when asked for" in {
      val pts = circle(12, noise = 0.05)
      val auto = Persistence(pts, maxFiltrationValue = 1.5, representatives = Representatives.Cocycles)
      val chunks = Persistence(pts, maxFiltrationValue = 1.5, engine = Persistence.Engine.Chunks)
      import auto.given
      // a 1-cocycle evaluates to zero on every triangle's boundary: check it on all triangles up to the threshold
      val ms = EuclideanMetricSpace(pts)
      val cocycle = auto.dim(1).longest.get.representative.terms.toMap
      val triangles = for
        a <- 0 until 12; b <- a + 1 until 12; c <- b + 1 until 12
        if Seq(ms.distance(a, b), ms.distance(a, c), ms.distance(b, c)).max <= 1.5
      yield Simplex(a, b, c)
      val fieldOps = auto.coefficientField
      def onBoundary(t: Simplex[Int]) = t
        .boundary[auto.Coefficient]
        .map((e, sign) => fieldOps.times(sign, cocycle.getOrElse(e, fieldOps.zero)))
        .foldLeft(fieldOps.zero)(fieldOps.plus)
      (auto.maxDimension must beEqualTo(2))
        .and(norm(auto.triples) must beEqualTo(norm(chunks.triples)))
        .and(triangles.forall(t => fieldOps.isEqual(onBoundary(t), fieldOps.zero)) must beTrue)
    }
    "report bettiNumbers and hide nothing until asked: significant() drops the short bars" in {
      val d = Persistence(circle(16, noise = 0.02), maxFiltrationValue = 2.0)
      (d.bettiNumbers must beEqualTo(Vector(1, 0, 0)))
        .and(d.significant().size must beLessThanOrEqualTo(d.size))
    }
    "build Cech and alpha complexes, and say what to do when alpha gets a threshold" in {
      val pts = circle(12, noise = 0.05, seed = 2)
      val cech = Persistence(pts, complex = Cech, maxFiltrationValue = 1.2)
      val alpha = Persistence(pts, complex = AlphaShapes)
      (cech.dim(1).longest must beSome)
        .and(alpha.dim(1).longest.map(_.persistence) must beSome(beGreaterThan(0.1)))
        .and(
          // Above every alpha value of this circle, the radius changes nothing.
          Persistence(pts, complex = AlphaShapes, maxFiltrationValue = 3.0).triples.sorted must beEqualTo(
            alpha.triples.sorted
          )
        )
    }
  }

  "Persistence(stream)" should {
    "compute the degrees a truncated stream was built for, and refuse a higher one" in {
      val ms = EuclideanMetricSpace(circle(8))
      val forH1 = VietorisRips(ms, maxDimension = 1, maxFiltrationValue = Double.PositiveInfinity)
      val forH2 = VietorisRips(ms, maxDimension = 2, maxFiltrationValue = Double.PositiveInfinity)
      // forH1 holds every triangle and no tetrahedra: read off H2, each triangle would be a fake essential class
      (Persistence(forH1).maxDimension must beEqualTo(1))
        .and(Persistence(forH2).maxDimension must beEqualTo(2))
        .and(Persistence(forH2).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
        .and(
          Persistence(forH1, maxDimension = 2) must throwAn[IllegalArgumentException](message =
            "built for degrees 0..1"
          )
        )
        .and(Persistence(Truncated(forH2, 1), maxDimension = 2) must throwAn[IllegalArgumentException])
    }
    "compute every degree of a complete complex, such as the octahedron's boundary" in {
      val triangles = for a <- Seq(0, 1); b <- Seq(2, 3); c <- Seq(4, 5) yield Simplex(a, b, c)
      val sphere = ExplicitStreamBuilder.fromFacets(triangles)
      Persistence(sphere, maxDimension = 2).bettiNumbers must beEqualTo(Vector(1, 0, 1))
    }
  }

  "the coefficient field" should {
    "default to F_17 and be selectable, with torsion visible only where it should be (RP^2)" in {
      val rp2 = SimplicialSet.realProjectiveSpace(2)
      import rp2.given
      val atZero = rp2.filtered(_ => 0.0)
      (Persistence(atZero, maxDimension = 2, characteristic = 2).bettiNumbers must beEqualTo(Vector(1, 1, 1)))
        .and(Persistence(rp2.filtered(_ => 0.0), maxDimension = 2).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
        .and(
          Persistence(rp2.filtered(_ => 0.0), maxDimension = 2, characteristic = 0).bettiNumbers must beEqualTo(
            Vector(1, 0, 0)
          )
        )
    }
    "reject a characteristic that is neither 0 nor a prime, saying so" in {
      Persistence(circle(5), characteristic = 4) must throwAn[IllegalArgumentException](message =
        "0 \\(reals\\) or a prime"
      )
    }
  }

  "Persistence(Image(...))" should {
    "use the fast cubical engine by default: the bars of Engine.Chunks, with cycles as representatives" in {
      val rnd = new scala.util.Random(5)
      val pixels = Array.tabulate(12, 12)((i, j) => math.sin(i / 2.0) * math.cos(j / 3.0) + 0.2 * rnd.nextDouble())
      val auto = Persistence(Image(pixels))
      val fast = Persistence(Image(pixels), engine = Persistence.Engine.FastCubical)
      val chunks = Persistence(Image(pixels), engine = Persistence.Engine.Chunks)
      import auto.given
      (norm(auto.triples) must beEqualTo(norm(chunks.triples)))
        .and(norm(auto.triples) must beEqualTo(norm(fast.triples)))
        .and(auto.bars.forall(b => Chain.from(b.representative.boundary).isZero()) must beTrue)
        .and(Persistence(circle(6), engine = Persistence.Engine.FastCubical) must throwAn[IllegalArgumentException])
    }
    "see the ring in a ring-shaped image as one H1 bar from 0 to 1 (the whole grid is contractible at the end)" in {
      val ring = Array.tabulate(7, 7)((i, j) => if math.abs(math.hypot(i - 3, j - 3) - 2.2) < 0.8 then 0.0 else 1.0)
      val d = Persistence(Image(ring))
      (d.bettiNumbers must beEqualTo(Vector(1, 0, 0)))
        .and(d.dim(1).significant().triples must beEqualTo(List((1, 0.0, 1.0))))
    }
  }

  "a cursor snapshot" should {
    "be an immutable diagram of the run so far" in {
      val pts = circle(10, noise = 0.1, seed = 9)
      given Double is Field = Field.DoubleApproximated(1e-9)
      val state = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1, Some(1.5)))
      val snap = state.snapshotAt(0.5)
      state.advanceAll()
      norm(snap.triples.filter(_._1 <= 1)) must beEqualTo(norm(state.diagramAt(0.5).filter(_._1 <= 1)))
    }
  }

/** All four engines of the verb give the same bars; the Ripser engine's cocycles come back over simplices. */
class PersistenceEnginesSpec extends org.specs2.mutable.Specification:
  import org.appliedtopology.tda4j.*

  "Persistence(points, engine = ...)" should {
    "give the same bars with every engine, and Ripser's representatives over simplices" in {
      val rnd = new scala.util.Random(7)
      val points = Array.fill(15)(Array(rnd.nextDouble(), rnd.nextDouble()))
      def rounded(d: PersistenceDiagram[Simplex[Int]]) =
        d.triples.map((k, b, e) => (k, math.rint(b * 1e9), if e.isInfinite then e else math.rint(e * 1e9))).sorted
      val forPoints = Persistence.Engine.values.toList.filterNot(_ == Persistence.Engine.FastCubical)
      val diagrams = forPoints.map(e => Persistence(points, engine = e))
      val ripser = Persistence(points, engine = Persistence.Engine.Ripser)
      (diagrams.map(rounded).distinct.size must beEqualTo(1)) and
        (ripser.bars.forall(b => b.representative.cells.forall(_.size == b.dim + 1)) must beTrue)
    }
    "give cocycles from every engine but FastCubical: the same bars and cocycles as the cohomology engine's" in {
      val points = Array.tabulate(9)(i => Array(math.cos(i * 0.7), math.sin(i * 0.7)))
      def norm(ts: List[(Int, Double, Double)]) =
        ts.map((d, b, e) => (d, math.rint(b * 1e9), if e.isInfinite then e else math.rint(e * 1e9))).sorted
      def run(e: Persistence.Engine) =
        Persistence(points, engine = e, maxDimension = 1, representatives = Representatives.Cocycles)
      val engines = List(Persistence.Engine.Chunks, Persistence.Engine.Naive, Persistence.Engine.Ripser)
      val reference = run(Persistence.Engine.Cohomology)
      (engines.map(e => norm(run(e).triples)).distinct must beEqualTo(List(norm(reference.triples))))
        .and(
          Persistence(
            Image(Array(Array(0.0, 1.0), Array(1.0, 0.0))),
            engine = Persistence.Engine.FastCubical,
            representatives = Representatives.Cocycles
          ) must throwAn[IllegalArgumentException](message = "Engine.Cohomology")
        )
    }
    "refuse engine = Ripser for an input that is not a Vietoris-Rips complex of points" in {
      Persistence(Image(Array(Array(0.0, 1.0), Array(1.0, 0.0))), engine = Persistence.Engine.Ripser) must
        throwAn[IllegalArgumentException]
    }
  }
