package org.appliedtopology.tda4j
package tutorial

import org.specs2.mutable.Specification

/** Runs the code of `_docs/tutorials/telling-spaces-apart.md` and asserts every number the page quotes. */
class TellingSpacesApartSpec extends Specification:
  sequential

  private case class Result(
    betti: Map[String, (Vector[Int], Vector[Int])],
    h1Classes: Map[String, Int],
    h1ProductNonzero: Map[String, Boolean],
    rp2Sq1Nonzero: Boolean,
    sq2Nonzero: Map[String, Boolean],
    hopfConeSquareNonzero: Boolean
  )

  private def page(): Result =
    val lab = TDAlab(2)
    import lab.{*, given}
    import cells.{CupProduct, FiniteSimplicialSet, SSetMap, SimplicialSets, Steenrod}

    def betti[G](x: FiniteSimplicialSet[G]) = (homology.BettiNumbers(x, 2), homology.BettiNumbers(x, 3))
    def basis[G](x: FiniteSimplicialSet[G], degree: Int) = CupProduct.cohomologyBasis[G, CoefficientT](x, degree)
    def cup[G](x: FiniteSimplicialSet[G], p: Int, q: Int, a: Map[G, CoefficientT], b: Map[G, CoefficientT]) =
      CupProduct.cup(x, p, q, a, b)
    def isNonzero[G](x: FiniteSimplicialSet[G], degree: Int, cochain: Map[G, CoefficientT]) =
      !CupProduct.isCoboundary(x, degree, cochain)

    // The torus is the presentation <a, b | a b a^-1 b^-1>; a letter is (generator, +1 or -1)
    val torus = SimplicialSets.presentationComplex(2, Seq(List((0, 1), (1, 1), (0, -1), (1, -1))))
    val projectivePlane = SimplicialSets.presentationComplex(1, Seq(List((0, 1), (0, 1)))) // <a | a^2>
    val kleinBottle = SimplicialSets.kleinBottle

    // Two circles and a sphere glued at a point: the same Betti numbers as the torus, but a different space
    val circle = SimplicialSets.sphere(1)
    val twoCircles = SimplicialSets.wedge(circle, circle.generatorsAt(0).head, circle, circle.generatorsAt(0).head)
    val sphere = SimplicialSets.sphere(2)
    val notATorus =
      SimplicialSets.wedge(twoCircles, twoCircles.generatorsAt(0).head, sphere, sphere.generatorsAt(0).head)

    def someProductOfOneClassesIsNonzero[G](x: FiniteSimplicialSet[G]): Boolean =
      val classes = basis(x, 1)
      classes.exists(a => classes.exists(b => isNonzero(x, 2, cup(x, 1, 1, a, b))))

    // Complex projective plane, and a sphere of dimension 2 glued to one of dimension 4
    val cp2 = SimplicialSets.complexProjectivePlaneKuhnel
    val sphere4 = SimplicialSets.sphere(4)
    val s2s4 = SimplicialSets.wedge(sphere, sphere.generatorsAt(0).head, sphere4, sphere4.generatorsAt(0).head)
    def squareOfTheTwoClassIsSq2[G](x: FiniteSimplicialSet[G]): Boolean =
      isNonzero(x, 4, Steenrod.sq(x, 2, 2, basis(x, 2).head))

    val x = basis(projectivePlane, 1).head
    val sq1IsNonzero = isNonzero(projectivePlane, 2, Steenrod.sq(projectivePlane, 1, 1, x))

    val hopfCone = SSetMap.mappingCone(
      SimplicialSets.hopfMap
    ) // the Hopf map S^3 -> S^2, and the space you get by attaching a 4-cell along it
    val hopfClass = basis(hopfCone, 2).head

    Result(
      Map(
        "torus" -> betti(torus),
        "wedge" -> betti(notATorus),
        "rp2" -> betti(projectivePlane),
        "klein" -> betti(kleinBottle),
        "cp2" -> betti(cp2),
        "s2s4" -> betti(s2s4)
      ),
      Map("torus" -> basis(torus, 1).size, "wedge" -> basis(notATorus, 1).size),
      Map("torus" -> someProductOfOneClassesIsNonzero(torus), "wedge" -> someProductOfOneClassesIsNonzero(notATorus)),
      sq1IsNonzero,
      Map("cp2" -> squareOfTheTwoClassIsSq2(cp2), "s2s4" -> squareOfTheTwoClassIsSq2(s2s4)),
      isNonzero(hopfCone, 4, cup(hopfCone, 2, 2, hopfClass, hopfClass))
    )

  "telling-spaces-apart.md" should {
    lazy val r = page()
    "report the Betti numbers it quotes" in {
      println(r)
      (r.betti("torus") must beEqualTo((Vector(1, 2, 1), Vector(1, 2, 1))))
        .and(r.betti("wedge") must beEqualTo((Vector(1, 2, 1), Vector(1, 2, 1))))
        .and(r.betti("rp2") must beEqualTo((Vector(1, 1, 1), Vector(1, 0, 0))))
        .and(r.betti("klein") must beEqualTo((Vector(1, 2, 1), Vector(1, 1, 0))))
        .and(r.betti("cp2") must beEqualTo((Vector(1, 0, 1, 0, 1), Vector(1, 0, 1, 0, 1))))
        .and(r.betti("s2s4") must beEqualTo(r.betti("cp2")))
    }
    "separate the torus from the wedge by cup products, not Betti numbers" in
      (r.h1Classes must beEqualTo(Map("torus" -> 2, "wedge" -> 2)))
        .and(r.h1ProductNonzero must beEqualTo(Map("torus" -> true, "wedge" -> false)))
    "separate CP^2 from S^2 v S^4 by Steenrod's Sq^2, and see Sq^1 nonzero on the projective plane" in
      (r.sq2Nonzero must beEqualTo(Map("cp2" -> true, "s2s4" -> false))).and(r.rp2Sq1Nonzero must beTrue)
    "build CP^2's cohomology ring from the Hopf map" in { r.hopfConeSquareNonzero must beTrue }
  }
