package tda4juser

import org.specs2.mutable.Specification

/** `.show` on what a user holds, from outside the package: one `import org.appliedtopology.tda4j.*` plus cats' own
  * syntax import finds every instance (they live in the data types' companions), whatever the static type.
  */
class ShowFromUserCodeSpec extends Specification:
  import org.appliedtopology.tda4j.*
  import cats.syntax.show.*

  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "with `import org.appliedtopology.tda4j.*` and `import cats.syntax.show.*`" should {
    "show a diagram from the verb, and the diagrams its methods return" in {
      val d = Persistence(square, maxDimension = 1)
      d.show must startWith("PersistenceDiagram(")
      d.dim(1).show must contain("1: [1.0, ")
      d.longerThan(0.1).show must startWith("PersistenceDiagram(")
    }
    "show bars, their endpoints and their representatives" in {
      val d = Persistence(square, maxDimension = 1)
      import d.given
      val bar = d.dim(1).bars.head
      bar.show must startWith("1: [1.0, ")
      bar.lower.show must beEqualTo("[1.0]")
      bar.representative.show must contain("⊠ ∆(")
      d.bars.show must startWith("List(0: [0.0, ")
    }
    "show cubes and cubical diagrams" in {
      Persistence(Image(IndexedSeq(0.0, 1.0, 0.0, 1.0), IndexedSeq(2, 2))).show must contain("Cube(")
    }
  }
