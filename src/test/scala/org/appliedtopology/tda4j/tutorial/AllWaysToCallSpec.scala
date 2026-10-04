package org.appliedtopology.tda4j
package tutorial

import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.matlab.TDA4j
import org.specs2.mutable.Specification

/** Pins the three tasks of `_docs/tutorials/all-ways-to-call.md`: the octahedron, the circle's barcode, and its
  * circular coordinate. The Scala snippets in the tutorial are compiled by the docs build; this spec RUNS the same code
  * (the MATLAB snippet drives exactly the Java calls below, which is the closest thing to running it that this
  * repository's CI can do).
  */
class AllWaysToCallSpec extends Specification:
  sequential

  private val csv = "_docs/tutorials/examplepoints.csv"

  // Task 1, as the MATLAB section does it: the octahedron is the flag complex of the cross-polytope's graph.
  private val octahedronDistances: Array[Array[Double]] =
    val antipode = Map(0 -> 1, 1 -> 0, 2 -> 3, 3 -> 2, 4 -> 5, 5 -> 4)
    Array.tabulate(6, 6)((i, j) => if i == j then 0.0 else if antipode(i) == j then 2.0 else 1.0)

  "task 1 (octahedron)" should {
    "have Betti numbers 1, 0, 1 via TDA4j.computeFromDistanceMatrix" in {
      val r =
        TDA4j.computeFromDistanceMatrix(octahedronDistances, Array("maxDimension", "2", "maxFiltrationValue", "1.5"))
      val bars = r.toArray().toList.map(_.toList)
      bars.count(b => b(0) == 0.0 && b(2).isInfinity) must beEqualTo(1)
      bars.count(b => b(0) == 1.0 && b(2).isInfinity) must beEqualTo(0)
      bars.count(b => b(0) == 2.0 && b(2).isInfinity) must beEqualTo(1)
    }
    "agree via the Scala route (Persistence on an explicit stream)" in {
      val (nTriangles, betti) = octahedronViaScala()
      (nTriangles must beEqualTo(8)).and(betti must beEqualTo(Vector(1, 0, 1)))
    }
  }

  "tasks 2 and 3 (circle)" should {
    "find one persistent H1 bar and a circular coordinate through the facade (the MATLAB route)" in {
      val points = CSV.readPointCloud(csv)
      val r = TDA4j.computeFromPoints(points, Array("maxDimension", "1"))
      val bars = r.toArray().toList.map(_.toList)
      val h1 = TDA4j.h1Bars(points)
      val mid = h1(0)(0) + (h1(0)(1) - h1(0)(0)) * 0.5
      val cc = TDA4j.circularCoordinates(points, mid)
      bars.count(_(0) == 1.0) must beEqualTo(1)
      cc.theta().length must beEqualTo(points.length)
    }
    "run through Persistence (the Scala route)" in {
      val (significant, h1, thetaSize) = circleViaScala()
      (significant must beEqualTo(List((0, 0.0, Double.PositiveInfinity), (1, 0.261, Double.PositiveInfinity))))
        .and(h1.map((b, d) => (r3(b), r3(d))) must beEqualTo(List((0.261, Double.PositiveInfinity))))
        .and(thetaSize must beEqualTo(25))
    }
    "give the loop alive at 1.0 through an engine by hand" in {
      engineByHand() must beEqualTo(List((1, 0.261, 1.0)))
    }
  }

  private def r3(x: Double) = if x.isInfinite then x else math.round(x * 1000) / 1000.0

  // The "Scala REPL" snippet of the page.
  private def octahedronViaScala(): (Int, Vector[Int]) =
    val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
    val octahedron = Persistence(ExplicitStreamBuilder.fromFacets(triangles), maxDimension = 2)
    (triangles.size, octahedron.bettiNumbers)

  private def circleViaScala(): (List[(Int, Double, Double)], IndexedSeq[(Double, Double)], Int) =
    val points = CSV.readPointCloud(csv)
    val circle = Persistence(points, maxFiltrationValue = 1.5)
    val metricSpace = EuclideanMetricSpace(points)
    val h1 = CircularCoordinates.h1Bars(metricSpace, 1.5)
    val coordinate = CircularCoordinates.compute(metricSpace, 1.0, 0)
    (circle.longerThan(0.3).triples.map((k, b, d) => (k, r3(b), r3(d))).sorted, h1, coordinate.theta.size)

  // The "An engine by hand" snippet of the page.
  private def engineByHand(): List[(Int, Double, Double)] =
    val field = FiniteField(17)
    import field.given
    val metricSpace = CSV.readEuclideanMetricSpace(csv)
    val state =
      SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(VietorisRips(metricSpace, maxDimension = 1))
    state.advanceTo(1.0)
    state.barcodeAt(1.0).filter(_.dim == 1).map(_.toTriple).map((k, b, d) => (k, r3(b), r3(d)))

  "ExplicitStreamBuilder.fromFacets" should {
    "close a facet list under faces" in {
      val stream = ExplicitStreamBuilder.fromFacets(List(Simplex(1, 2, 3), Simplex(3, 4)))
      stream.iterator.toList.size must beEqualTo(9) // the triangle's 7 cells, plus vertex 4 and edge {3,4}
    }
    "give an unlisted face the earliest value of a cell containing it" in {
      val stream = ExplicitStreamBuilder.fromFilteredFacets(List((2.0, Simplex(1, 2)), (1.0, Simplex(2, 3))))
      stream.filtrationValue(Simplex(2)) must beEqualTo(1.0)
      stream.filtrationValue(Simplex(1)) must beEqualTo(2.0)
      stream.filtrationValue(Simplex(1, 2)) must beEqualTo(2.0)
    }
    "reject a listed value that contradicts a listed coface" in {
      ExplicitStreamBuilder.fromFilteredFacets(List((2.0, Simplex(1)), (1.0, Simplex(1, 2)))) must throwAn[
        IllegalArgumentException
      ]
    }
    "keep different listed values on overlapping facets and give the overlap the minimum" in {
      val stream =
        ExplicitStreamBuilder.fromFilteredFacets(List((3.0, Simplex(1, 2, 3)), (1.0, Simplex(2, 3, 4))))
      stream.filtrationValue(Simplex(2, 3)) must beEqualTo(1.0)
      stream.filtrationValue(Simplex(1, 2)) must beEqualTo(3.0)
      stream.filtrationValue(Simplex(1, 2, 3)) must beEqualTo(3.0)
    }
    "offer a constant value for the unlisted cells, rejected if not monotone" in {
      import ExplicitStreamBuilder.UnlistedValues
      val ok =
        ExplicitStreamBuilder.fromFilteredFacets(List((2.0, Simplex(1, 2))), UnlistedValues.Constant(0.0))
      ok.filtrationValue(Simplex(1)) must beEqualTo(0.0)
      ExplicitStreamBuilder.fromFilteredFacets(
        List((2.0, Simplex(1, 2))),
        UnlistedValues.Constant(5.0)
      ) must throwAn[
        IllegalArgumentException
      ]
    }
  }
