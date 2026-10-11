package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import org.specs2.mutable.Specification

/** Nerves of finite categories, against values derived by hand before running (the comment on each). Each check is one
  * the plausible wrong implementation fails: a composition order flipped (B(M^op) ≃ BM, so Betti numbers alone would
  * not see it: compared cell for cell and face for face instead), a free category confused with a poset, a free action
  * with a non-free one (equivariant homology over two primes).
  */
class CategoryNerveSpec extends Specification:

  /** The left cosets of `h` in `g`, and `g` acting on them by left multiplication (a left action: `(g h) c = g (h c)`).
    */
  private def cosetAction(g: FiniteGroup, h: Set[Int]): (Int, (Int, Int) => Int) =
    val cosets = (0 until g.order).map(x => h.map(g.multiply(x, _))).distinct
    (cosets.size, (x, i) => cosets.indexOf(cosets(i).map(g.multiply(x, _))))

  private val s3 = FiniteGroup.symmetric(3)
  private val transposition = (1 until s3.order).find(x => s3.multiply(x, x) == s3.identity).get
  private val z2InS3 = Set(s3.identity, transposition)

  // The boundary of a square, vertices 0..3, and three actions of Z/2 on it.
  private val square = Seq(Simplex(0, 1), Simplex(1, 2), Simplex(2, 3), Simplex(0, 3))
  private val z2 = FiniteGroup.cyclic(2)
  private val rotation: (Int, Int) => Int = (g, v) => if g == 0 then v else (v + 2) % 4
  private val reflection: (Int, Int) => Int = (g, v) => if g == 0 then v else (4 - v) % 4 // fixes 0 and 2
  private val trivial: (Int, Int) => Int = (_, v) => v

  "the category laws" should {
    "hold for every constructor" in {
      val categories = Seq(
        FiniteCategory.fromMonoid(s3),
        FiniteCategory.fromMonoid(FiniteMonoid.rectangularBand(2, 3)),
        FiniteCategory.fromPoset(Seq(Set(0), Set(1), Set(0, 1)), (a: Set[Int], b: Set[Int]) => a.subsetOf(b)),
        FiniteCategory.freeOnAcyclicQuiver(3, Seq(0 -> 1, 1 -> 2, 0 -> 2)),
        FiniteCategory.actionGroupoid(s3, cosetAction(s3, z2InS3)._1, cosetAction(s3, z2InS3)._2),
        FiniteCategory.homotopyOrbits(z2, square, rotation),
        FiniteCategory.homotopyOrbits(z2, square, reflection),
        FiniteCategory.homotopyOrbits(z2, square, trivial)
      )
      categories.flatMap(_.validate()) must beEmpty
    }
    "be checked: a non-associative composition is reported" in {
      // One object, morphisms 0 (identity), 1, 2 with 1;1 = 2, 1;2 = 1, 2;1 = 2, 2;2 = 2: (1;2);1 = 2 but 1;(2;1) = 1.
      val table = Array(Array(0, 1, 2), Array(1, 2, 1), Array(2, 2, 2))
      val broken = FiniteCategory(
        1,
        IndexedSeq(0, 0, 0),
        IndexedSeq(0, 0, 0),
        IndexedSeq(0),
        table(_)(_),
        IndexedSeq("e", "a", "b")
      )
      broken.validate() must contain(startWith("composition is not associative"))
    }
    "reject a right action passed as a left one" in {
      val (n, _) = cosetAction(s3, z2InS3)
      val cosets = (0 until s3.order).map(x => z2InS3.map(s3.multiply(x, _))).distinct
      val right: (Int, Int) => Int = (x, i) => cosets.indexOf(cosets(i).map(s3.multiply(s3.inverse(x), _)))
      FiniteCategory.actionGroupoid(s3, n, right) must throwAn[IllegalArgumentException](message = "left action")
    }
  }

  "the nerve of a monoid as a one-object category" should {
    "be Nerve(monoid) cell for cell and face for face (S_3 and a band: composition order matters)" in {
      Seq[FiniteMonoid](s3, FiniteMonoid.rectangularBand(2, 3)).forall { m =>
        val viaCategory = CategoryNerve(FiniteCategory.fromMonoid(m))
        val direct = Nerve(m)
        (0 to 3).forall { n =>
          val cells = viaCategory.generators(n).toSeq
          cells.map(_.morphisms).toSet == direct.generators(n).map(_.entries).toSet &&
          cells.forall { c =>
            viaCategory.faces(c).map(e => (e.word, e.generator.morphisms)) ==
              direct.faces(NerveSimplex(c.morphisms)).map(e => (e.word, e.generator.entries))
          }
        }
      } must beTrue
    }
    "be a valid simplicial set" in {
      CategoryNerve(FiniteCategory.fromMonoid(s3)).skeleton(3).validate() must beEmpty
    }
  }

  "the nerve of a poset" should {
    // Face poset of ∂Δ³: 14 faces; the nerve is the barycentric subdivision of S², f = (14, 36, 24).
    val faces = (1 to 3).flatMap(k => (0 to 3).toList.combinations(k).map(_.toSet))
    val poset = FiniteCategory.fromPoset(faces, (a: Set[Int], b: Set[Int]) => a.subsetOf(b))
    val nerve = CategoryNerve(poset).skeleton(3)

    "be the order complex: f-vector (14, 36, 24) exactly, as fromSimplicialComplex builds it" in {
      val chains = faces.indices.toList.flatMap { i =>
        def up(c: List[Int]): List[List[Int]] =
          c :: faces.indices.toList
            .filter(j => j != c.head && faces(c.head).subsetOf(faces(j)))
            .flatMap(j => up(j :: c))
        up(List(i))
      }
      val orderComplex = SimplicialSet.fromSimplicialComplex(chains.map(c => Simplex.from(c.sorted)))
      (nerve.fVector must beEqualTo(Vector(14, 36, 24, 0)))
        .and(orderComplex.fVector must beEqualTo(Vector(14, 36, 24)))
        .and(nerve.validate() must beEmpty)
    }
    "have the homology of S² over F_2 and F_3" in {
      Seq(2, 3).map(nerve.bettiNumbers) must beEqualTo(Seq.fill(2)(Vector(1, 0, 1, 0)))
    }
  }

  "the nerve of a free category" should {
    "make two parallel arrows a circle: two vertices, two edges, nothing above" in {
      val pair = CategoryNerve(FiniteCategory.freeOnAcyclicQuiver(2, Seq(0 -> 1, 0 -> 1))).skeleton(2)
      (pair.fVector must beEqualTo(Vector(2, 2, 0))).and(pair.bettiNumbers(3) must beEqualTo(Vector(1, 1, 0)))
    }
    "keep the edge 0 -> 2 apart from the composite 0 -> 1 -> 2, which the poset 0 < 1 < 2 identifies" in {
      // free: morphisms a0, a1, a2, a0;a1 -- one 2-simplex fills (a0, a1, a0;a1) and the loop through a2 survives.
      val free = CategoryNerve(FiniteCategory.freeOnAcyclicQuiver(3, Seq(0 -> 1, 1 -> 2, 0 -> 2))).skeleton(3)
      val chain = CategoryNerve(FiniteCategory.fromPoset(Seq(0, 1, 2), (a: Int, b: Int) => a <= b)).skeleton(3)
      (free.fVector must beEqualTo(Vector(3, 4, 1, 0)))
        .and(free.bettiNumbers(2) must beEqualTo(Vector(1, 1, 0, 0)))
        .and(chain.fVector must beEqualTo(Vector(3, 3, 1, 0)))
        .and(chain.bettiNumbers(2) must beEqualTo(Vector(1, 0, 0, 0)))
    }
    "refuse a quiver with a directed cycle" in {
      FiniteCategory.freeOnAcyclicQuiver(2, Seq(0 -> 1, 1 -> 0)) must throwAn[IllegalArgumentException](
        message = "directed cycle"
      )
    }
  }

  "the nerve of an action groupoid" should {
    "be B(stabilizer): S_3 on its three cosets of Z/2 has B(Z/2)'s homology, not B(S_3)'s" in {
      // H_n(Z/2; F_3) = 0 in positive degrees, H_3(S_3; F_3) = F_3: degree 3 over F_3 tells them apart.
      val (n, act) = cosetAction(s3, z2InS3)
      val nerve = SimplicialSet.nerve(FiniteCategory.actionGroupoid(s3, n, act))
      (BettiNumbers(nerve, 3, 2) must beEqualTo(Vector(1, 1, 1, 1)))
        .and(BettiNumbers(nerve, 3, 3) must beEqualTo(Vector(1, 0, 0, 0)))
        .and(ClassifyingSpace.bettiNumbers(s3, 3, 3) must beEqualTo(Vector(1, 0, 0, 1)))
    }
    "be contractible for a free transitive action, though it has generators in every dimension" in {
      val (n, act) = cosetAction(s3, Set(s3.identity))
      val nerve = CategoryNerve(FiniteCategory.actionGroupoid(s3, n, act))
      (Seq(2, 3).map(p => BettiNumbers(nerve, 3, p)) must beEqualTo(Seq.fill(2)(Vector(1, 0, 0, 0))))
        .and(nerve.generators(3).size must beGreaterThan(0))
    }
  }

  "homotopy orbits (the Borel construction) of Z/2 acting on a circle" should {
    // The boundary of a square. Rotation by a half turn is free: X_hG ≃ X/G = S¹. The reflection fixes two points and
    // swaps the two arcs: X_hG ≃ BG ∨ BG = RP^∞ ∨ RP^∞. The trivial action: X_hG = X × BG = S¹ × RP^∞.
    "have Betti numbers that separate the three actions, each pair by one prime" in {
      val expected = Seq(
        "rotation" -> (Vector(1, 1, 0, 0), Vector(1, 1, 0, 0)),
        "reflection" -> (Vector(1, 2, 2, 2), Vector(1, 0, 0, 0)),
        "trivial" -> (Vector(1, 2, 2, 2), Vector(1, 1, 0, 0))
      )
      Seq("rotation" -> rotation, "reflection" -> reflection, "trivial" -> trivial).map { (name, action) =>
        val nerve = CategoryNerve(FiniteCategory.homotopyOrbits(z2, square, action))
        name -> (BettiNumbers(nerve, 3, 2), BettiNumbers(nerve, 3, 3))
      } must beEqualTo(expected)
    }
    "give the persistence of X -> X_hG along the subcategory of the trivial group, with cycle representatives" in {
      // Level 0 is the face poset (nerve: the subdivided circle), level 1 everything. Degree 1, rotation: the double
      // cover is multiplication by 2 on H_1 -- zero over F_2 (the class dies, a new one is born), iso over F_3.
      // Reflection: the loop goes to (1,1) in H_1(RP^∞ ∨ RP^∞; F_2) and survives; over F_3 the target is acyclic.
      // Trivial: X is a retract of X × BG, so nothing dies.
      def bars(action: (Int, Int) => Int, p: Int) =
        val category = FiniteCategory.homotopyOrbits(z2, square, action)
        val nerve = CategoryNerve(category)
        val chain: Seq[Set[Int]] = Seq(category.morphismsOver(Set(0)), (0 until category.morphismCount).toSet)
        val diagram: PersistenceDiagram[CategorySimplex] = nerve.persistentHomology(nerve.filtrationBy(chain), 2, p)
        val x = nerve.skeleton(3)
        import diagram.given
        given (CategorySimplex is OrderedCell) = x.cellInstance
        val repsAreCycles = diagram.bars.forall(_.annotation.exists(rep => Chain.from(rep.boundary).terms.isEmpty))
        (diagram.triples.filter(_._1 >= 1).sorted, repsAreCycles)
      val inf = Double.PositiveInfinity
      Seq(
        bars(rotation, 2),
        bars(rotation, 3),
        bars(reflection, 2),
        bars(reflection, 3),
        bars(trivial, 2),
        bars(trivial, 3)
      ) must beEqualTo(
        Seq(
          List((1, 0.0, 1.0), (1, 1.0, inf)),
          List((1, 0.0, inf)),
          List((1, 0.0, inf), (1, 1.0, inf), (2, 1.0, inf), (2, 1.0, inf)),
          List((1, 0.0, 1.0)),
          List((1, 0.0, inf), (1, 1.0, inf), (2, 1.0, inf), (2, 1.0, inf)),
          List((1, 0.0, inf))
        ).map(_ -> true)
      )
    }
    "refuse an action that does not map simplices to simplices" in {
      val shift: (Int, Int) => Int =
        (g, v) => if g == 0 then v else Seq(1, 0, 2, 3)(v) // swaps 0, 1: edge {1,2} -> {0,2}
      FiniteCategory.homotopyOrbits(z2, square, shift) must throwAn[IllegalArgumentException](message =
        "out of the complex"
      )
    }
  }
