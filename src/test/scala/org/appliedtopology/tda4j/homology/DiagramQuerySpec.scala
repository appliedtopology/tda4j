package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import scala.concurrent.duration.*

/** The cursor contract: `diagramAt(f)` is the diagram of the filtration truncated at `f` -- bars born at or before `f`,
  * deaths capped at `f`, a class alive at `f` reported as dying at `f` unless no cell enters after `f` (then it is
  * essential) -- NO MATTER WHERE THE CURSOR IS. The cursor exists so a long run can be inspected (and survive a crash
  * with output) before it finishes; reading a lower parameter from a cursor that has moved past it must give the same
  * answer a fresh cursor would. `.claude/WORKLOG-cursor-and-verb.md`.
  */
class DiagramQuerySpec extends Specification:
  given Double is Field = Field.DoubleApproximated(1e-9)

  private def cloud(seed: Int, n: Int): Array[Array[Double]] =
    val rnd = new scala.util.Random(seed)
    Array.fill(n)(Array(rnd.nextDouble(), rnd.nextDouble()))

  private def stream(pts: Array[Array[Double]]) = VietorisRips(EuclideanMetricSpace(pts), 1, Some(0.8))

  private def fresh(pts: Array[Array[Double]]) =
    SimplicialHomologyEngine[Int, Double, Double]().persistentHomology(stream(pts))

  /** Bars of degree <= 1 (degree 2 is truncation scaffolding), zero-length tie bars dropped, as a sorted multiset. */
  private def norm(bars: List[(Int, Double, Double)]): List[(Int, Double, Double)] =
    bars.filter((d, b, e) => d <= 1 && e > b).map((d, b, e) => (d, round(b), round(e))).sorted
  private def round(x: Double) = if x.isInfinite then x else math.rint(x * 1e9) / 1e9

  private val seeds = 1 to 12

  "the naive engine's cursor" should {
    "answer diagramAt(f) the same as a fresh cursor, for any order of queries" in {
      seeds.forall { seed =>
        val pts = cloud(seed, 8)
        val queries = new scala.util.Random(seed).shuffle(Seq(0.0, 0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.8, 5.0))
        val longLived = fresh(pts)
        queries.forall(f => norm(longLived.diagramAt(f)) == norm(fresh(pts).diagramAt(f)))
      } must beTrue
    }
    "report the lead's own example correctly: diagramAt(0.5) after diagramAt(3.0)" in {
      val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
      def s = SimplicialHomologyEngine[Int, Double, Double]()
        .persistentHomology(VietorisRips(EuclideanMetricSpace(square), 1, Some(3.0)))
      val cursor = s
      cursor.diagramAt(3.0)
      cursor.diagramAt(0.5).sorted must beEqualTo(s.diagramAt(0.5).sorted)
    }
  }

  "the chunks engine" should {
    "agree with the naive engine at every query value (it used to report classes born after f)" in {
      seeds.forall { seed =>
        val pts = cloud(seed, 8)
        val chunks = CellularPersistenceInChunksEngine[Simplex[Int], Double](1).persistentHomology(stream(pts))
        Seq(0.0, 0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.8, 5.0).forall { f =>
          norm(chunks.diagramAt(f)) == norm(fresh(pts).diagramAt(f))
        }
      } must beTrue
    }
  }

  "time-boxed advancing" should {
    "stop at the budget and report progress, then resume to the same final diagram" in {
      val pts = cloud(99, 9)
      val boxed = fresh(pts)
      val finishedAtOnce = boxed.advanceFor(Duration.Zero)
      val partial = boxed.processedCells
      while !boxed.advanceFor(10.millis) do ()
      (finishedAtOnce must beFalse)
        .and(partial must beLessThan(boxed.totalCells))
        .and(boxed.processedCells must beEqualTo(boxed.totalCells))
        .and(
          norm(boxed.diagramAt(Double.PositiveInfinity)) must beEqualTo(
            norm(fresh(pts).diagramAt(Double.PositiveInfinity))
          )
        )
    }
    "return true straight away when the budget covers the whole stream" in {
      fresh(cloud(7, 6)).advanceFor(1.minute) must beTrue
    }
  }
