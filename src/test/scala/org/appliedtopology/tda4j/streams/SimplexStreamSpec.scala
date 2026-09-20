package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.{mutable, Specification}
import org.specs2.execute.Result
import org.specs2.specification.core.Fragment
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.collection.mutable as cmutable

class SimplexStreamSpec extends mutable.Specification with org.specs2.ScalaCheck:
  "Test suite for the abstraction and implementations of `SimplexStream`".txt

  val esb: ExplicitStreamBuilder[Int, Double] =
    ExplicitStreamBuilder[Int, Double]

  val simplexSeq: Seq[(Double, Simplex[Int])] = Seq(
    (0.0, Simplex(1)),
    (0.0, Simplex(2)),
    (0.0, Simplex(3)),
    (5.0, Simplex(2, 4)),
    (0.0, Simplex(4)),
    (1.0, Simplex(1, 2)),
    (3.0, Simplex(2, 3)),
    (2.0, Simplex(1, 3)),
    (4.0, Simplex(1, 4)),
    (6.0, Simplex(1, 2, 3))
  )

  esb ++= simplexSeq

  val simplexStream: ExplicitStream[Int, Double] = esb.result()

  "An ExplicitStream should" >> {
    "have simplices" >> {
      simplexStream.iterator.size must beGreaterThan(0)
    }
    "have simplices appear in filtration order" >> {
      given Ordering[Int] = Ordering.Int
      simplexStream.iterator
        .map(simplexStream.filtrationValue)
        .to(Seq) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[Simplex[Int]] =
        cmutable.Set(Simplex())
      simplexStream.iterator.foreach { spx =>
        seen += spx
        spx.toSet
          .subsets()
          .foreach(face => seen must contain(face))
      }
    }
    "have all simplices, in order" >> {
      simplexStream.iterator.to(Seq) must contain[Simplex[Int]](
        exactly(
          ===(∆(1)),
          ===(∆(2)),
          ===(∆(3)),
          ===(∆(4)),
          ===(∆(1, 2)),
          ===(∆(1, 3)),
          ===(∆(2, 3)),
          ===(∆(1, 4)),
          ===(∆(2, 4)),
          ===(∆(1, 2, 3))
        ).inOrder
      )
    }
    "be able to compute filtration values" >> {
      simplexStream.filtrationValue(∆(1, 2)) must be_==(1.0)
      simplexStream.filtrationValue(∆(1, 2, 3)) must be_==(6.0)
    }
  }

  "A SimplexStream induced FilteredSimplexOrdering should" >> {
    given filteredSimplexOrdering: Ordering[Simplex[Int]] =
      new FilteredSimplexOrdering[Int, Double](simplexStream)
    val sortedSimplexSeq = simplexSeq.map((_, s) => s).sorted
    "have filtration values in ascending order" >> {
      sortedSimplexSeq.map(simplexStream.filtrationValue) must beSorted
    }
    "have subsimplices appear before supersimplices" >> {
      val seen: cmutable.Set[Simplex[Int]] =
        cmutable.Set(Simplex())
      sortedSimplexSeq.foreach { spx =>
        seen += spx
        spx.toSet
          .subsets()
          .foreach(face => seen must contain(face))
      }
    }
  }

class CofaceSimplexStreamSpec extends mutable.Specification with org.specs2.ScalaCheck:
  "Different coface simplex streams should agree" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(15, 15))) { (pts: Array[Array[Double]]) =>
      val metricSpace = EuclideanMetricSpace(pts)
      val enumerating = EnumeratingCofaceSimplexStream(metricSpace)
      val inorder = InorderCofaceSimplexStream(metricSpace)
      // Driven dimension-by-dimension, strictly increasing -- required for RipserCofaceSimplexStream's own
      // incremental cache (currentDimension/lastDimensionCache) to stay valid across calls.
      val ripser = RipserCofaceSimplexStream(metricSpace)

      Result.foreach(0 to 5) { dim =>
        val enumerated = enumerating.iterateDimension(dim).toSeq
        val inordered = inorder.iterateDimension(dim).toSeq
        val ripsered = ripser.iterateDimension(dim).toSeq

        (enumerated.map(spx => enumerating.filtrationValue(spx)) must beSorted) and
          (inordered.map(spx => inorder.filtrationValue(spx)) must beSorted) and
          (ripsered.map(spx => ripser.filtrationValue(spx)) must beSorted) and
          // using size as proxy for equality for CI testing; change to `enumerated === inordered` if debugging
          (enumerated.size === inordered.size) and
          (enumerated.size === ripsered.size)
      }
    }

  // Direct tests of StratifiedCellStream's own contract (see its doc comment): `.iterator` must actually
  // terminate, and the bound it terminates at must be exactly metricSpace.size, not one off in either
  // direction -- an off-by-one here would silently drop (or spuriously include) the top-dimensional simplex,
  // and nothing else in this suite would notice since every other test drives iterateDimension(d) directly
  // for d values it already knows are in range.
  "EnumeratingCofaceSimplexStream.iterator terminates and covers every simplex up to metricSpace.size - 1" >> {
    val metricSpace = EuclideanMetricSpace(
      Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0), Array(1.0, 1.0), Array(0.5, 0.5))
    )
    // Explicit +Infinity: this test wants the complete complex (every combinatorially-possible subset), the
    // premise the binomial-sum expectedTotal below assumes -- EnumeratingCofaceSimplexStream now defaults to
    // metricSpace.minimumEnclosingRadius, which would exclude some subsets (see CLAUDE.md/WORKLOG-mst-and-perf.md).
    val stream = EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity))
    val expectedTotal = (0 until metricSpace.size).map(d => binomial(metricSpace.size, d + 1)).sum
    stream.iterator.size === expectedTotal
  }

  "EnumeratingCofaceSimplexStream.iterateDimension is defined exactly on [0, metricSpace.size)" >> {
    val metricSpace = EuclideanMetricSpace(
      Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0), Array(1.0, 1.0), Array(0.5, 0.5))
    )
    val stream = EnumeratingCofaceSimplexStream(metricSpace)
    (stream.iterateDimension.isDefinedAt(metricSpace.size - 1) must beTrue) and
      (stream.iterateDimension.isDefinedAt(metricSpace.size) must beFalse)
  }

  // Pins the memoization fix from .claude/WORKLOG-autonomous-session-2026-09-19.md (task #2): the default
  // MaximumDistanceFiltrationValue fallback is now cached per-instance, exactly the same fix (and same
  // justification) as CubicalGridStream's own filtrationValue cache. Mirrors RipserCohomologySpec's own
  // "memoizing changes nothing about the computed barcode" property, but for SimplicialHomologyContext
  // (the naive engine actually consuming this stream's default filtrationValue), and comparing the FULL
  // bar list (dim, birth, death) rather than just counts.
  // maxDim capped at 2 (via LimitedCofaceSimplexStream) and point counts kept modest (6-12, matching
  // RipserCohomologySpec's own memoization-toggle property): an EARLIER version of this test built the
  // COMPLETE, untruncated complex (maxFiltrationValue = +Infinity, no dimension cap) for up to 15 points --
  // up to 2^15-1 simplices through the naive engine, TWICE per trial, across ~100 ScalaCheck trials -- and
  // was the direct cause of a several-minute `sbt test` slowdown plus OOM-driven failures cascading into
  // unrelated specs (the same failure shape CLAUDE.md's "Cross-engine benchmark" section already documents
  // from a prior incident). Fixed by capping scope the same way every other property test in this file does.
  "Memoizing EnumeratingCofaceSimplexStream's default filtrationValue changes nothing about the computed barcode" >>
    forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 3), Gen.chooseNum(6, 12))) { points =>
      given Double is Field = Field.DoubleApproximated(1e-9)
      given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()

      val metricSpace = EuclideanMetricSpace(points)
      val maxDim = 2

      def barcodeWith(forceUncached: Boolean): List[(Int, Double, Double)] =
        val fvOverride =
          if forceUncached then Some(FiniteMetricSpace.MaximumDistanceFiltrationValue[Int](metricSpace))
          else None
        val stream = LimitedCofaceSimplexStream(
          EnumeratingCofaceSimplexStream(
            metricSpace,
            maxFiltrationValue = Some(Double.PositiveInfinity),
            filtrationValueOverride = fvOverride
          ),
          maxDim
        )
        shc.persistentHomology(stream).diagramAt(Double.PositiveInfinity)

      barcodeWith(forceUncached = false) must containTheSameElementsAs(barcodeWith(forceUncached = true))
    }
