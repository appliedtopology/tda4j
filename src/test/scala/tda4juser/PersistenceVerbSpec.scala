package tda4juser

import org.specs2.mutable.Specification

/** The one-call verb, from a user's side of the package boundary: one import, every input shape, and answers that
  * agree with the explicit engine path, between engines, and with the cursor's own truncation contract.
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
    ts.filter((d, b, e) => e > b).map((d, b, e) => (d, math.rint(b * 1e9) / 1e9, if e.isInfinite then e else math.rint(e * 1e9) / 1e9)).sorted

  "Persistence(points)" should {
    "find the circle's loop as its longest H1 bar, with a representative cycle" in {
      val d = Persistence(circle(16), maxFiltrationValue = 2.0)
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
        .and(norm(Persistence(pts, maxFiltrationValue = 2).triples) must beEqualTo(norm(Persistence(pts, maxFiltrationValue = 2.0).triples)))
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
      Seq(1.5, 0.1, 0.4, 0.7, 5.0).forall(f => norm(d.at(f).triples) == norm(cursor.diagramAt(f).filter(_._1 <= 1))) must beTrue
    }
    "report bettiNumbers and hide nothing until asked: significant() drops the short bars" in {
      val d = Persistence(circle(16, noise = 0.02), maxFiltrationValue = 2.0)
      (d.bettiNumbers must beEqualTo(Vector(1, 0)))
        .and(d.significant().size must beLessThanOrEqualTo(d.size))
    }
    "build Cech and alpha complexes, and say what to do when alpha gets a threshold" in {
      val pts = circle(12, noise = 0.05, seed = 2)
      val cech = Persistence(pts, complex = Cech, maxFiltrationValue = 1.2)
      val alpha = Persistence(pts, complex = AlphaShapes)
      (cech.dim(1).longest must beSome)
        .and(alpha.dim(1).longest.map(_.persistence) must beSome(beGreaterThan(0.1)))
        .and(Persistence(pts, complex = AlphaShapes, maxFiltrationValue = 1.0) must throwAn[IllegalArgumentException](
          message = "diagram.at"
        ))
    }
  }

  "the coefficient field" should {
    "default to F_17 and be selectable, with torsion visible only where it should be (RP^2)" in {
      val rp2 = SimplicialSet.realProjectiveSpace(2)
      import rp2.given
      val atZero = rp2.filtered(_ => 0.0)
      (Persistence(atZero, maxDimension = 2, characteristic = 2).bettiNumbers must beEqualTo(Vector(1, 1, 1)))
        .and(Persistence(rp2.filtered(_ => 0.0), maxDimension = 2).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
        .and(Persistence(rp2.filtered(_ => 0.0), maxDimension = 2, characteristic = 0).bettiNumbers must beEqualTo(Vector(1, 0, 0)))
    }
    "reject a characteristic that is neither 0 nor a prime, saying so" in {
      Persistence(circle(5), characteristic = 4) must throwAn[IllegalArgumentException](message = "0 \\(reals\\) or a prime")
    }
  }

  "Persistence(Image(...))" should {
    "see the ring in a ring-shaped image as one H1 bar from 0 to 1 (the whole grid is contractible at the end)" in {
      val ring = Array.tabulate(7, 7)((i, j) => if math.abs(math.hypot(i - 3, j - 3) - 2.2) < 0.8 then 0.0 else 1.0)
      val d = Persistence(Image(ring))
      (d.bettiNumbers must beEqualTo(Vector(1, 0)))
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
