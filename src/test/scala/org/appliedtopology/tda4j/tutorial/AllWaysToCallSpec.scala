package org.appliedtopology.tda4j
package tutorial

import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.matlab.TDA4j
import org.specs2.mutable.Specification

/** Pins the three tasks of `_docs/tutorials/all-ways-to-call.md`: the octahedron, the circle's barcode, and its circular
  * coordinate. The Scala snippets in the tutorial are compiled by the docs build; this spec RUNS the same code (the
  * MATLAB snippet drives exactly the Java calls below, which is the closest thing to running it that this repository's
  * CI can do).
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
      val r = TDA4j.computeFromDistanceMatrix(octahedronDistances, Array("maxDimension", "2", "maxFiltrationValue", "1.5"))
      val bars = r.toArray().toList.map(_.toList)
      bars.count(b => b(0) == 0.0 && b(2).isInfinity) must beEqualTo(1)
      bars.count(b => b(0) == 1.0 && b(2).isInfinity) must beEqualTo(0)
      bars.count(b => b(0) == 2.0 && b(2).isInfinity) must beEqualTo(1)
    }
    "agree via the Scala REPL route (TDAlab, explicit stream)" in {
      val (nTriangles, essentialByDim) = octahedronViaTDAlab()
      nTriangles must beEqualTo(8)
      essentialByDim must beEqualTo(Map(0 -> 1, 2 -> 1))
    }
  }

  "tasks 2 and 3 (circle)" should {
    "find one persistent H1 bar and a circular coordinate through the facade (the MATLAB route)" in {
      val points = io.CSV.readPointCloud(csv)
      val r = TDA4j.computeFromPoints(points, Array("maxDimension", "1"))
      val bars = r.toArray().toList.map(_.toList)
      val h1 = TDA4j.h1Bars(points)
      val mid = h1(0)(0) + (h1(0)(1) - h1(0)(0)) * 0.5
      val cc = TDA4j.circularCoordinates(points, mid)
      bars.count(_(0) == 1.0) must beEqualTo(1)
      cc.theta().length must beEqualTo(points.length)
    }
    "run through TDAlab (the Scala route)" in {
      val (significantH1, h1Count, thetaSize) = circleViaTDAlab()
      significantH1 must beEqualTo(1)
      h1Count must beGreaterThanOrEqualTo(1)
      thetaSize must beEqualTo(25)
    }
  }

  private def octahedronViaTDAlab(): (Int, Map[Int, Int]) =
    val lab = new TDAlab(17)
    import lab.{*, given}
    val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield ∆(a, b, c)
    val computation =
      homology.SimplicialHomologyEngine().persistentHomology(streams.ExplicitStreamBuilder.fromFacets(triangles))
    val bars = computation.barcodeAt(4.0)
    val essential = bars.filter(b => b.upper.toString.contains("∞") || b.upper.toString.contains("nfinity")).groupBy(_.dim).view.mapValues(_.size).toMap
    (triangles.size, essential)

  private def circleViaTDAlab(): (Int, Int, Int) =
    val lab = new TDAlab(17)
    import lab.{*, given}
    val metricSpace = io.CSV.readEuclideanMetricSpace(csv)
    val computation = homology.SimplicialHomologyEngine().persistentHomology(
      streams.IncrementalVietorisRipsSimplexStream(metricSpace, maxDimension = 2)
    )
    val all = computation.barcodeAt(1.5)
    val bars = barcode.PersistenceFilter.significant(all, scale = Some(metricSpace.minimumEnclosingRadius))
    val h1 = homology.CircularCoordinates.h1Bars(metricSpace, Some(1.5))
    val coordinate = homology.CircularCoordinates.compute(metricSpace, 1.5, 0)
    (bars.count(_.dim == 1), h1.size, coordinate.theta.size)

  "ExplicitStreamBuilder.fromFacets" should {
    "close a facet list under faces" in {
      val stream = streams.ExplicitStreamBuilder.fromFacets(List(Simplex(1, 2, 3), Simplex(3, 4)))
      stream.iterator.toList.size must beEqualTo(9) // the triangle's 7 cells, plus vertex 4 and edge {3,4}
    }
    "give an unlisted face the earliest value of a cell containing it" in {
      val stream = streams.ExplicitStreamBuilder.fromFilteredFacets(List((2.0, Simplex(1, 2)), (1.0, Simplex(2, 3))))
      stream.filtrationValue(Simplex(2)) must beEqualTo(1.0)
      stream.filtrationValue(Simplex(1)) must beEqualTo(2.0)
      stream.filtrationValue(Simplex(1, 2)) must beEqualTo(2.0)
    }
  }
