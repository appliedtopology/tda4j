package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.execute.AsResult

/** `DtmRipsSimplexStream` (Anai, Chazal, Glisse, Ike, Lecci, Rouvreau, Saulnier & Wasserman, "DTM-based filtrations",
  * arXiv:1811.04757). See `.claude/WORKLOG-dtm-filtrations.md` for the full derivation of every fixture below.
  */
class DtmRipsStreamSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)

  "DtmRipsSimplexStream at p=1" should {
    // gudhi.dtm_rips_complex.DTMRipsComplex(points=[[2,2],[0,1],[3,4]], k=2).create_simplex_tree(max_dimension=2)
    // .persistence() gives H0 = [(3.16227766, 5.39834564), (3.16227766, 5.39834564), (3.16227766, inf)] -- copied
    // verbatim from GUDHI's own test_dtm_rips_complex.py (fetched live during this session), not re-derived.
    "reproduce GUDHI's own DTMRipsComplex worked example exactly" >> {
      val ambient = EuclideanMetricSpace(Array(Array(2.0, 2.0), Array(0.0, 1.0), Array(3.0, 4.0)))
      val f = DistanceToMeasure(ambient, 2)
      val stream = DtmRipsSimplexStream(ambient, f, p = 1.0)
      val barcode = SimplicialHomologyContext[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
      val tol = 1e-6
      (barcode.count(_._1 == 0) must beEqualTo(3)) and
        (barcode.count {
          case (0, b, d) => math.abs(b - 3.16227766) < tol && math.abs(d - 5.39834564) < tol; case _ => false
        }
          must beEqualTo(2)) and
        (barcode.count { case (0, b, d) => math.abs(b - 3.16227766) < tol && d.isPosInfinity; case _ => false }
          must beEqualTo(1))
    }

    // Hand-derived (.claude/WORKLOG-dtm-filtrations.md): collinear points 0, 1, 3, 7, k=2. f = (sqrt(0.5),
    // sqrt(0.5), sqrt(2), sqrt(8)) (each point's own nearest OTHER point: 1, 0, 1, 3 respectively). Doubled
    // vertex births (1.41421, 1.41421, 2.82843, 5.65685) tie points 0 and 1 at birth; the elder-rule merge at
    // edge(0,1) = 2.41421 kills the younger of the tied pair, point 3 merges in at edge(1,3) = 4.12132, point 7
    // at edge(3,7) = 8.24264 -- which also realizes R = minimumEnclosingRadius (the untruncated default), so
    // this fixture also exercises the "does R exclude the edge that realizes it" boundary case.
    "match a hand-derived collinear fixture with distinct, tied, and boundary-realizing vertex births" >> {
      val ambient = ExplicitMetricSpace(
        IndexedSeq(
          IndexedSeq(0.0, 1.0, 3.0, 7.0),
          IndexedSeq(1.0, 0.0, 2.0, 6.0),
          IndexedSeq(3.0, 2.0, 0.0, 4.0),
          IndexedSeq(7.0, 6.0, 4.0, 0.0)
        )
      )
      val f = DistanceToMeasure(ambient, 2)
      val stream = DtmRipsSimplexStream(ambient, f, p = 1.0)
      val barcode = SimplicialHomologyContext[Int, Double, Double]()
        .persistentHomology(stream)
        .diagramAt(Double.PositiveInfinity)
      val tol = 1e-6
      def has(b: Double, d: Double): Boolean =
        barcode.exists { case (0, bb, dd) => math.abs(bb - b) < tol && math.abs(dd - d) < tol; case _ => false }
      (barcode.count(_._1 == 0) must beEqualTo(4)) and
        (has(1.41421356, 2.41421356) must beTrue) and
        (has(2.82842712, 4.12132034) must beTrue) and
        (has(5.65685425, 8.24264069) must beTrue) and
        (barcode.count { case (0, b, d) => math.abs(b - 1.41421356) < tol && d.isPosInfinity; case _ => false }
          must beEqualTo(1))
    }

    // Compares SETS and filtration VALUES, not raw iteration order: at k=1, f=0 for every vertex, so every
    // vertex ties at filtration 0 -- and `RipserCofaceSimplexStream`'s own inherited (unsorted) case 0 and
    // this class's own (explicitly sorted) case 0 override are both individually valid resolutions of that tie
    // (CLAUDE.md's ordering-contract rule 2 only requires SOME order consistent with filtrationOrdering.reverse,
    // and a fully-tied bucket is consistent with EVERY order), just not necessarily the SAME one -- confirmed
    // empirically while writing this test. The barcode itself, which is what "reduces to plain VR" actually
    // means, is checked below and is insensitive to which tied order either stream picked.
    "reduce to plain Vietoris-Rips at k=1 (f=0 everywhere, including the threshold): same cells, same values" >>
      AsResult {
        org.scalacheck.Prop.forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(5, 10))) { pts =>
          val ambient = EuclideanMetricSpace(pts)
          val f = DistanceToMeasure(ambient, 1)
          val dtmStream = DtmRipsSimplexStream(ambient, f, p = 1.0)
          val vrStream = RipserCofaceSimplexStream(ambient)
          val cellsMatch = (0 to 2).forall { d =>
            val dtmCells = dtmStream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
            val vrCells = vrStream.iterateDimension.applyOrElse(d, (_: Int) => Iterator.empty).toSet
            dtmCells == vrCells && dtmCells.forall(c => dtmStream.filtrationValue(c) == vrStream.filtrationValue(c))
          }
          val dtmBarcode =
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(dtmStream)
              .diagramAt(Double.PositiveInfinity)
          val vrBarcode =
            SimplicialHomologyContext[Int, Double, Double]()
              .persistentHomology(vrStream)
              .diagramAt(Double.PositiveInfinity)
          cellsMatch && dtmBarcode.toSet == vrBarcode.toSet
        }
      }
  }

  "DtmRipsSimplexStream" should {
    "reject an unsupported p" >> {
      val ambient = EuclideanMetricSpace(Array(Array(0.0), Array(1.0)))
      val f = DistanceToMeasure(ambient, 1)
      DtmRipsSimplexStream(ambient, f, p = 1.5) must throwAn[IllegalArgumentException]
    }

    "reject an f of the wrong length" >> {
      val ambient = EuclideanMetricSpace(Array(Array(0.0), Array(1.0)))
      DtmRipsSimplexStream(ambient, IndexedSeq(0.0)) must throwAn[IllegalArgumentException]
    }
  }

  "DtmRipsSimplexStream at p=1 and p=2" should
    "keep vertex filtration value = 2*f(x) and every edge >= both its endpoints' vertex values (monotonicity)" >>
    AsResult {
      org.scalacheck.Prop
        .forAll(matrixGen(Gen.double, Gen.chooseNum(2, 4), Gen.chooseNum(5, 12)), Gen.oneOf(1.0, 2.0)) { (pts, p) =>
          val ambient = EuclideanMetricSpace(pts)
          val f = DistanceToMeasure(ambient, math.min(3, ambient.size))
          val stream = DtmRipsSimplexStream(ambient, f, p, maxFiltrationValue = Some(Double.PositiveInfinity))
          val vertexOk = stream.iterateDimension(0).forall(v => stream.filtrationValue(v) == 2.0 * f(v.min))
          val edgeOk = stream.iterateDimension(1).forall { e =>
            val vs = e.toSeq
            stream.filtrationValue(e) >= stream.filtrationValue(Simplex(vs(0))) - 1e-9 &&
            stream.filtrationValue(e) >= stream.filtrationValue(Simplex(vs(1))) - 1e-9
          }
          vertexOk && edgeOk
        }
    }
