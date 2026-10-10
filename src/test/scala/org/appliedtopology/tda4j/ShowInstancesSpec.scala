package org.appliedtopology.tda4j

import org.specs2.mutable
import cats.Show
import cats.syntax.show.toShow
import org.appliedtopology.tda4j.sset.*

/** The `Show` instances beyond simplices, chains and field elements: cubes, barcode endpoints, bars, diagrams, the
  * involution's pairs and the simplicial-set generators. Each is checked at the static type a user holds (cats' `Show`
  * is invariant, so an instance for `PersistenceDiagram[C]` alone would not be found for
  * `PersistenceDiagram.Of[C, F]`).
  */
class ShowInstancesSpec extends mutable.Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  "Cube" should {
    "show as a product of intervals" in {
      Cube.fromVector(Vector(1, 4)).show must beEqualTo("Cube([0,1]x{2})")
    }
    "make chains of cubes showable" in {
      given Ordering[Cube] = Cube.ordering
      val c = Chain(Cube.fromVector(Vector(1, 4)) -> 1.0, Cube.fromVector(Vector(2, 3)) -> -1.0)
      c.show must beEqualTo("1.0 ⊠ Cube([0,1]x{2}) + -1.0 ⊠ Cube({1}x[1,2])")
    }
  }

  "Chain" should {
    given Ordering[Simplex[Int]] = Simplex.ordering[Int]
    "show each cell once, with the coefficients summed" in {
      Chain(∆(1, 2) -> 1.0, ∆(1, 2) -> 1.0, ∆(2, 3) -> 1.0).show must beEqualTo("2.0 ⊠ ∆(1,2) + 1.0 ⊠ ∆(2,3)")
    }
    "leave out cells whose coefficients cancel, and show the zero chain as 0" in {
      Chain(∆(1, 2) -> 1.0, ∆(1, 2) -> -1.0, ∆(2, 3) -> 3.0).show must beEqualTo("3.0 ⊠ ∆(2,3)")
      Chain(∆(1, 2) -> 1.0, ∆(1, 2) -> -1.0).show must beEqualTo("0")
    }
    "show coefficients by their field, F_p included" in {
      val f5 = FiniteField(5)
      import f5.given
      val c = Chain.from(Seq(∆(0, 1) -> f5.Fp(4)))(using Simplex.ordering[Int], summon[f5.Fp is Field])
      c.show must beEqualTo(s"${f5.Fp(4).show} ⊠ ∆(0,1)")
    }
    "need no OrderedCell for the cells, only a Show" in {
      Chain(KleinGenerator.A -> 1.0, KleinGenerator.T1 -> 2.0).show must beEqualTo("1.0 ⊠ A + 2.0 ⊠ T1")
    }
  }

  "BarcodeEndpoint" should {
    "show each kind, at its own static type" in {
      ClosedEndpoint(1.0).show must beEqualTo("[1.0]")
      OpenEndpoint(2.5).show must beEqualTo("(2.5)")
      PositiveInfinity[Double]().show must beEqualTo("+∞")
      NegativeInfinity[Double]().show must beEqualTo("-∞")
      (ClosedEndpoint(1.0): BarcodeEndpoint[Double]).show must beEqualTo("[1.0]")
    }
  }

  "PersistenceBar" should {
    "show a bar without an annotation type" in {
      PersistenceBar[Double](1, 0.5, 1.5).show must beEqualTo("1: [0.5, 1.5)")
      PersistenceBar[Double](0, 0.0).show must beEqualTo("0: [0.0, ∞)")
      PersistenceBar[Double](2).show must beEqualTo("2: (-∞, ∞)")
    }
    "show a closed upper end (a class alive at a truncation value)" in {
      new PersistenceBar[Double, Nothing](1, ClosedEndpoint(0.5), ClosedEndpoint(0.7)).show must beEqualTo(
        "1: [0.5, 0.7]"
      )
    }
    "show the representative after the interval, and nothing when there is none" in {
      given Ordering[Simplex[Int]] = Simplex.ordering[Int]
      val rep = Chain(∆(0, 1) -> 1.0, ∆(1, 2) -> 1.0)
      val bar = new PersistenceBar(1, ClosedEndpoint(0.5), OpenEndpoint(1.5), Some(rep))
      bar.show must beEqualTo("1: [0.5, 1.5)  1.0 ⊠ ∆(0,1) + 1.0 ⊠ ∆(1,2)")
      bar.copy(annotation = None).show must beEqualTo("1: [0.5, 1.5)")
    }
    "use the filtration type's own Show" in {
      given Show[Double] = Show.show(x => f"$x%.1f")
      PersistenceBar[Double](1, 0.25, 1.0 / 3).show must beEqualTo("1: [0.3, 0.3)")
    }
  }

  "PersistenceDiagram" should {
    // A unit square: one H1 class [1, sqrt 2), born when the last side enters.
    val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
    val d = Persistence(square, maxDimension = 1)
    import d.given

    "list every bar with its representative, degree by degree" in {
      val lines = d.show.linesIterator.toList
      lines.head must beEqualTo(s"PersistenceDiagram(${d.size} bars, degrees 0..1)")
      lines.tail.map(_.trim.takeWhile(_ != ':').toInt) must beEqualTo(d.bars.map(_.dim).sorted)
      val h1 = d.bars.filter(_.dim == 1)
      h1.size must beEqualTo(1)
      lines.last must beEqualTo(s"  1: [1.0, ${math.sqrt(2.0)})  ${h1.head.representative.show}")
    }
    "be found for the refined diagram types dim, at and significant return" in {
      d.dim(1).show must beEqualTo(
        s"PersistenceDiagram(1 bar, degrees 0..1)\n  1: [1.0, ${math.sqrt(2.0)})  " +
          d.bars.filter(_.dim == 1).head.representative.show
      )
      d.at(1.2).show must contain("1: [1.0, 1.2]")
      d.significant().show must startWith("PersistenceDiagram(")
    }
    "show a cubical diagram, whose cells are cubes" in {
      val img = Image(IndexedSeq(0.0, 1.0, 0.0, 1.0, 2.0, 1.0, 0.0, 1.0, 0.0), IndexedSeq(3, 3))
      val c = Persistence(img)
      c.show must contain("⊠ Cube(")
    }
  }

  "Involution.Pair" should {
    "show its cells by their own Show" in {
      Involution.Pair(1, ∆(1, 2), Some(∆(0, 1, 2))).show must beEqualTo(
        "Pair(dim = 1, birth = ∆(1,2), death = Some(∆(0,1,2)))"
      )
    }
  }

  "simplicial-set generators" should {
    "show a degenerate simplex in normal form as its degeneracies and generator" in {
      SSetElement(List(1, 0), ∆(0)).show must beEqualTo("s1 s0 ∆(0)")
      SSetElement(Nil, TorusGenerator.values.head).show must beEqualTo(TorusGenerator.values.head.toString)
    }
    "show product, cone and join generators by their parts" in {
      ProductGenerator(SSetElement(List(0), 7), SSetElement(Nil, 8)).show must beEqualTo("(s0 7, 8)")
      (ConeGenerator.Cone(∆(0, 1)): ConeGenerator[Simplex[Int]]).show must beEqualTo("Cone(g = ∆(0,1))")
      (ConeGenerator.Apex: ConeGenerator[Simplex[Int]]).show must beEqualTo("Apex")
      (JoinGenerator.Both(1, "y"): JoinGenerator[Int, String]).show must beEqualTo("Both(x = 1, y = y)")
    }
    "show a nerve simplex in bar notation" in {
      NerveSimplex(Vector(1, 2)).show must beEqualTo("[1|2]")
      NerveSimplex(Vector()).show must beEqualTo("[]")
    }
  }
