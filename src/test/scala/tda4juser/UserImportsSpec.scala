package tda4juser

import org.specs2.mutable.Specification

import scala.compiletime.testing.typeCheckErrors

/** What a user's code sees -- deliberately OUTSIDE `org.appliedtopology.tda4j`, where nothing is lexically visible
  * unless imported. Pins the given-priority rules of `.claude/WORKLOG-package-flatten.md`: the library's default
  * instances live in companions (implicit scope), so a single `import org.appliedtopology.tda4j.*` finds them, and any
  * given the user writes beats them without an ambiguity.
  */
class UserImportsSpec extends Specification:
  import org.appliedtopology.tda4j.*

  private val points = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0), Array(1.0, 1.0))
  private def stream = VietorisRips(EuclideanMetricSpace(points), 1, Some(2.0))

  "one plain `import org.appliedtopology.tda4j.*`" should {
    "find the default simplex instances (no `given` selector needed)" in {
      summon[Simplex[Int] is OrderedCell].dim(∆(1, 2, 3)) must beEqualTo(2)
      summon[Ordering[Simplex[Int]]].compare(∆(1, 2), ∆(1, 3)) must beLessThan(0)
      cats.Show[Simplex[Int]].show(∆(3, 1)) must beEqualTo("∆(1,3)")
      summon[Ordering[Cube]] must not(beNull)
    }
    "let a stream's filtration ordering win over the default, without ambiguity" in {
      val s = stream
      given Ordering[Simplex[Int]] = s.filtrationOrdering
      summon[Ordering[Simplex[Int]]] must beTheSameAs(s.filtrationOrdering)
    }
    "derive the Ordering from a user-supplied cell instance at the use site" in {
      val s = stream
      given (Simplex[Int] is OrderedCell) = simplexIsOrderedCell[Int](s.filtrationOrdering)
      summon[Ordering[Simplex[Int]]] must beTheSameAs(s.filtrationOrdering)
    }
    "not let a library given decide an unconstrained type parameter" in {
      // Before the fix, `PositiveInfinity()` silently became `PositiveInfinity[Cube]()` via the blanket
      // `[CellT: OrderedCell] => Ordering[CellT]` given; now there is nothing lexical to pick.
      typeCheckErrors("PositiveInfinity()") must not(beEmpty)
      PositiveInfinity[Double]() must beEqualTo(PositiveInfinity[Double]())
    }
  }

  "a missing coefficient field or cell structure" should {
    "produce a message that says what to do, not just 'no given instance'" in {
      // separate blocks: a given declared later in the same block would be visible to the earlier check
      val noField = typeCheckErrors("SimplicialHomologyEngine[Int, Double, Double]()").map(_.message).mkString
      val noCell =
        enum G:
          case V
        given Ordering[G] = Ordering.by(_.ordinal)
        given doubles: (Double is Field) = Field.DoubleApproximated(1e-9)
        typeCheckErrors("CellularHomologyEngine[G, Double, Double]()").map(_.message).mkString
      (noField must contain("tda4j: no coefficient field for Double"))
        .and(noCell must contain("tda4j: no cell structure for"))
        .and(noCell must contain("import x.given"))
    }
  }

  "importing both the package and a TDAlab" should {
    "not make re-exported names ambiguous" in {
      val tdalab = TDAlab(2)
      import tdalab.{*, given}
      typeCheckErrors("∆(1, 2, 3)") must beEmpty
      typeCheckErrors("VietorisRips(EuclideanMetricSpace(points), 1)") must beEmpty
      typeCheckErrors("summon[Ordering[Simplex[Int]]]") must beEmpty
      typeCheckErrors("val c: Chain[Simplex[Int], CoefficientT] = ∆(1, 2)") must beEmpty
    }
  }

/** No package import at all: a TDAlab instance must be enough on its own. */
class TDAlabAloneSpec extends Specification:
  "a TDAlab alone" should {
    "be the only import a lab user needs" in {
      typeCheckErrors(
        """
        val lab = org.appliedtopology.tda4j.TDAlab(3)
        import lab.{*, given}
        val c: Chain[Simplex[Int], CoefficientT] = ∆(1, 2, 3)
        val s = VietorisRips(EuclideanMetricSpace(Array(Array(0.0), Array(1.0))), 1)
        summon[Ordering[Simplex[Int]]]
        CSV
        PersistenceFilter
        """
      ) must beEmpty
    }
  }

/** Engines with every type argument inferred, from the user's side. */
class InferredEngineSpec extends Specification:
  import org.appliedtopology.tda4j.*
  private val pts = Array.tabulate(10)(i => Array(math.cos(i * 0.628), math.sin(i * 0.628)))

  "SimplicialHomologyEngine.persistentHomology(stream)" should {
    "infer vertex, coefficient and filtration types and agree with the explicit form" in {
      given Double is Field = Field.DoubleApproximated(1e-9)
      val s = VietorisRips(EuclideanMetricSpace(pts), 1, 1.5)
      SimplicialHomologyEngine.persistentHomology(s).diagramAt(Double.PositiveInfinity).sorted must beEqualTo(
        SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(s).diagramAt(Double.PositiveInfinity).sorted
      )
    }
    "refuse loudly, never guess, when no coefficient field or two of them are in scope" in {
      val none =
        typeCheckErrors("SimplicialHomologyEngine.persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1))")
      val two =
        val f3 = FiniteField(3)
        import f3.given
        given Double is Field = Field.DoubleApproximated(1e-9)
        typeCheckErrors("SimplicialHomologyEngine.persistentHomology(VietorisRips(EuclideanMetricSpace(pts), 1))")
      (none.map(_.message).mkString must contain("tda4j: no coefficient field")).and(two must not(beEmpty))
    }
    "work the same way for the chunks engine" in {
      val f5 = FiniteField(5)
      import f5.given
      val s = VietorisRips(EuclideanMetricSpace(pts.toSeq), 1, 1.5)
      PersistenceInChunksEngine.persistentHomology(s, 1).diagramAt(Double.PositiveInfinity).sorted must beEqualTo(
        PersistenceInChunksEngine[Int, f5.Fp](1).persistentHomology(s).diagramAt(Double.PositiveInfinity).sorted
      )
    }
  }
