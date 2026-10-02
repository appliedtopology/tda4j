package org.appliedtopology.tda4j
package tutorial

import org.appliedtopology.tda4j.matlab.TDA4j
import org.specs2.mutable.Specification

/** The MATLAB tabs of the tutorial pages call `matlab.TDA4j` from MATLAB; nothing here runs MATLAB. This spec makes the SAME Java calls
  * (same arguments, same option strings) and asserts the numbers the tabs quote, so the only unchecked part of a MATLAB tab is MATLAB's
  * own syntax and its marshalling of Java arrays. Calls `TDA4j` directly, not the `FullBarcode` shim, because that is what MATLAB calls.
  */
class MatlabTabsSpec extends Specification:
  sequential

  private def r3(x: Double): Double = if x.isInfinite then x else math.round(x * 1000) / 1000.0
  private def rows(bars: Array[Array[Double]]): List[List[Double]] = bars.toList.map(_.toList.map(r3))
  private def dim(bars: Array[Array[Double]], d: Int) = bars.filter(_(0) == d.toDouble)
  private val points = io.CSV.readPointCloud("_docs/tutorials/data/noisy-circle.csv")
  private def cleaned(r: matlab.PersistenceResult) =
    r.toArray().toList.map(b => (b(0).toInt, math.round(b(1) * 1e6), if b(2).isInfinite then Long.MaxValue else math.round(b(2) * 1e6))).sorted

  "find-a-loop.md (MATLAB tab)" should {
    lazy val result = TDA4j.computeFromPoints(points, Array("maxDimension", "1", "engine", "naive"))
    "report 58 bars, hide 1486, and find the loop" in {
      val bars = result.toArray()
      (bars.length must beEqualTo(58))
        .and(result.hiddenCount() must beEqualTo(1486))
        .and(rows(dim(bars, 1)) must beEqualTo(List(List(1.0, 0.595, 1.707))))
        .and(dim(bars, 0).length must beEqualTo(57))
    }
    "see the full barcode through toArrayUnfiltered: 1544 bars, 1483 of zero length" in {
      val all = result.toArrayUnfiltered()
      (all.length must beEqualTo(1544)).and(all.count(b => b(2) - b(1) <= 1e-12) must beEqualTo(1483))
    }
    "find the longest finite gap in dimension 0 (0.484) among the reported bars" in {
      val h0 = dim(result.toArray(), 0).filter(_(2).isFinite)
      r3(h0.map(b => b(2) - b(1)).max) must beEqualTo(0.484)
    }
    "give a 52-edge representative of the loop (0-based CSV row numbers) using the 0-based index of the bar" in {
      val k = result.toArray().indexWhere(_(0) == 1.0)
      val edges = result.cycleVertices(k)
      (edges.length must beEqualTo(52)).and(edges.forall(_.length == 2) must beTrue).and(result.cycleCoefficients(k).length must beEqualTo(52))
    }
    "cut off at 1.0: 3629 cells, and the loop is born at 0.595 and never dies" in {
      val short = TDA4j.computeFromPoints(points, Array("maxDimension", "1", "engine", "naive", "maxFiltrationValue", "1.0"))
      (short.numCells() must beEqualTo(3629)).and(rows(dim(short.toArray(), 1)) must beEqualTo(List(List(1.0, 0.595, Double.PositiveInfinity))))
    }
  }

  "choosing-a-complex.md (MATLAB tab)" should {
    "give the same five cell counts and loops as the Scala tab" in {
      val settings = List(
        ("vr", Array[String](), 24711, (0.595, 1.707)),
        ("cech", Array[String](), 36050, (0.297, 0.935)),
        ("alpha", Array[String](), 325, (0.297, 0.935)),
        ("sparse-rips", Array("sparseEpsilon", "0.5"), 1730, (0.708, 1.789)),
        ("witness", Array("numLandmarks", "15"), 441, (0.278, 0.742))
      )
      val got = settings.map { (complex, extra, _, _) =>
        val r = TDA4j.computeFromPoints(points, Array("maxDimension", "1", "complex", complex) ++ extra)
        val loop = dim(r.toArray(), 1).maxBy(b => b(2) - b(1))
        (r.numCells(), (r3(loop(1)), r3(loop(2))))
      }
      got must beEqualTo(settings.map((_, _, cells, loop) => (cells, loop)))
    }
  }

  "noise-and-outliers.md (MATLAB tab)" should {
    val outliers = io.CSV.readPointCloud("_docs/tutorials/data/circle-with-outliers.csv")
    def twoLongest(r: matlab.PersistenceResult) = dim(r.toArrayUnfiltered(), 1).map(b => r3(b(2) - b(1))).sorted.reverse.take(2).toList
    "show the two longest loops for VR and DTM, and the cycles of the VR ones" in {
      val vr = TDA4j.computeFromPoints(outliers, Array("maxDimension", "1", "engine", "naive"))
      val dtm = TDA4j.computeFromPoints(outliers, Array("maxDimension", "1", "complex", "dtm-rips", "dtmK", "8", "dtmP", "1.0"))
      val top = vr.toArray().zipWithIndex.filter(_._1(0) == 1.0).sortBy(x => -(x._1(2) - x._1(1))).take(2).map(_._2)
      val cycles = top.map { k => val vs = vr.cycleVertices(k).flatten.distinct; (vs.length, vs.count(_ >= 70)) }.toList
      (twoLongest(vr) must beEqualTo(List(0.901, 0.127)))
        .and(twoLongest(dtm) must beEqualTo(List(0.815, 0.005)))
        .and(cycles must beEqualTo(List((66, 2), (8, 5))))
    }
  }

  "images.md (MATLAB tab)" should {
    val random = new java.util.Random(5L)
    val pixels = Array.tabulate(28, 28) { (row, column) =>
      val ring = math.hypot(row - 12.0, column - 12.0)
      val blob = math.hypot(row - 23.0, column - 23.0)
      val signal = if ring >= 6.0 && ring <= 8.0 then 1.0 else if blob <= 2.5 then 0.8 else 0.0
      signal + 0.1 * random.nextDouble()
    }
    def significant(sublevel: String, engine: String) =
      rows(TDA4j.computeFromImage(pixels, Array("sublevel", sublevel, "engine", engine, "minPersistence", "0.3")).toArray().sortBy(b => (b(0), b(1))))
    "find the dark features, and the bright ones with NEGATED values, and agree across engines" in {
      val dark = List(List(0.0, 0.0, Double.PositiveInfinity), List(0.0, 0.001, 1.002), List(1.0, 0.062, 1.099), List(1.0, 0.064, 0.9))
      val bright = List(List(0.0, -1.099, Double.PositiveInfinity), List(0.0, -0.9, -0.063), List(1.0, -1.004, -0.001))
      (significant("true", "naive") must beEqualTo(dark))
        .and(significant("true", "fast-cubical") must beEqualTo(dark))
        .and(significant("false", "naive") must beEqualTo(bright))
    }
    "have 1625 bars in the full barcode" in {
      TDA4j.computeFromImage(pixels, Array("sublevel", "true")).toArrayUnfiltered().length must beEqualTo(1625)
    }
  }

  "circular-and-toroidal-coordinates.md (MATLAB tab)" should {
    def circularDistance(a: Double, b: Double) = { val d = math.abs(a - b) % 1.0; math.min(d, 1.0 - d) }
    def alignmentError(theta: Array[Double], truth: Int => Double): Double =
      (for sign <- Seq(1.0, -1.0); offset <- (0 until 200).map(_ / 200.0) yield
        theta.zipWithIndex.map((t, i) => circularDistance(((sign * t + offset) % 1.0 + 1.0) % 1.0, truth(i))).sum / theta.length).min
    def turn(y: Double, x: Double) = (math.atan2(y, x) / (2 * math.Pi) + 1.0) % 1.0
    "recover the circle's angle to 0.064 of a turn" in {
      val h1 = TDA4j.h1Bars(points)
      val c = TDA4j.circularCoordinates(points, (h1(0)(0) + h1(0)(1)) / 2)
      (rows(h1.take(2)) must beEqualTo(List(List(0.595, 1.707), List(1.95, 1.95))))
        .and(c.theta().length must beEqualTo(60))
        .and(r3(alignmentError(c.theta(), i => turn(points(i)(1), points(i)(0)))) must beEqualTo(0.064))
    }
    "recover a torus (two coordinates, errors near 0.13 and 0.15)" in {
      val torus = io.CSV.readPointCloud("_docs/tutorials/data/flat-torus.csv")
      val h1 = TDA4j.h1Bars(torus)
      val r = (h1.take(2).map(_(0)).max + h1.take(2).map(_(1)).min) / 2
      val c = TDA4j.toroidalCoordinates(torus, r, Array(0, 1))
      val errors = (0 until 2).map { k =>
        val theta = c.theta(k)
        math.min(alignmentError(theta, i => turn(torus(i)(1), torus(i)(0))), alignmentError(theta, i => turn(torus(i)(3), torus(i)(2))))
      }
      (rows(h1.take(4)) must beEqualTo(List(List(0.68, 1.751), List(0.699, 1.755), List(0.771, 1.419), List(0.771, 1.407))))
        .and(errors.map(r3).toList must beEqualTo(List(0.131, 0.148)))
    }
  }

  "comparing-barcodes.md (MATLAB tab)" should {
    def loops(file: String) =
      TDA4j.computeFromPoints(io.CSV.readPointCloud(s"_docs/tutorials/data/$file"), Array("maxDimension", "1"))
    def l2(x: Array[Array[Double]], y: Array[Array[Double]]) = math.sqrt(x.flatten.zip(y.flatten).map((p, q) => (p - q) * (p - q)).sum)
    lazy val (a, b, e) = (loops("noisy-circle.csv"), loops("noisy-circle-b.csv"), loops("figure-eight.csv"))
    "give distances on the COMPLETE barcode" in {
      def d(x: matlab.PersistenceResult, y: matlab.PersistenceResult) = (r3(x.bottleneckDistance(y, 1)), r3(x.wassersteinDistance(y, 1)))
      (d(a, b) must beEqualTo((0.04, 0.045))).and(d(a, e) must beEqualTo((0.556, 0.864))).and(d(b, e) must beEqualTo((0.518, 0.83)))
    }
    "give landscapes and images" in {
      def land(x: matlab.PersistenceResult) = x.landscape(1, 3, 0.0, 2.0, 100)
      def img(x: matlab.PersistenceResult) = x.persistenceImage(1, 0.1, 0.0, 2.0, 0.0, 2.0, 40, 40, 1.0)
      (r3(l2(land(a), land(b))) must beEqualTo(0.279))
        .and(r3(l2(land(a), land(e))) must beEqualTo(2.43))
        .and(r3(l2(img(a), img(e))) must beEqualTo(0.163))
        .and(r3(l2(img(a), img(b))) must beEqualTo(0.08))
    }
  }

  "networks-and-relations.md (MATLAB tab)" should {
    val never = Double.PositiveInfinity
    val relation = Array.tabulate(6, 7) { (person, club) =>
      if club == 6 then 5.0 else if club == person then 1.0 else if club == (person + 1) % 6 then 2.0 else never
    }
    "see the same loop [2,5) from both sides, 16 and 13 bars in the raw barcodes" in {
      val people = TDA4j.computeFromRelation(relation, Array("maxDimension", "1"))
      val clubs = TDA4j.computeFromRelation(relation, Array("maxDimension", "1", "dual", "true"))
      (rows(dim(people.toArray(), 1)) must beEqualTo(List(List(1.0, 2.0, 5.0))))
        .and(rows(dim(clubs.toArray(), 1)) must beEqualTo(List(List(1.0, 2.0, 5.0))))
        .and(people.toArrayUnfiltered().length must beEqualTo(16))
        .and(clubs.toArrayUnfiltered().length must beEqualTo(13))
        .and(rows(people.toArray()) must beEqualTo(rows(clubs.toArray())))
    }
  }

  "scaling-up.md (MATLAB tab)" should {
    def run(extra: String*) = TDA4j.computeFromPoints(points, Array("maxDimension", "1", "minPersistence", "1e-9") ++ extra)
    "give 61 bars for every engine, and the same bars" in {
      val all = List("naive", "chunks", "cohomology", "ripser").map(e => cleaned(run("engine", e)))
      (all.map(_.size) must beEqualTo(List(61, 61, 61, 61))).and(all.forall(_ == all.head) must beTrue)
    }
    "collapse the complex to 1547 cells with the same barcode" in {
      val plain = run("engine", "naive")
      val collapsed = run("engine", "naive", "edgeCollapse", "true")
      (collapsed.numCells() must beEqualTo(1547)).and(cleaned(collapsed) must beEqualTo(cleaned(plain)))
    }
  }
