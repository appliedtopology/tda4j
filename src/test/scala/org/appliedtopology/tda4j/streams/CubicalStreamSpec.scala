package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.execute.{AsResult, Result}
import org.specs2.ScalaCheck
import org.scalacheck.*

/** `CubicalGridStream` + `CubicalHomologyContext` correctness. Validation order deliberately follows the advisor's
  * corrected priority (see `.claude/WORKLOG-cubical.md`): dd=0/canonical ordering already covered by `CubicalSpec`;
  * here, monotonicity, the structural (bars-account-for-cells) invariant, hand-derived fixtures, and an independent
  * H0-via-union-find cross-check -- NOT a cubical-to-simplicial triangulation cross-check, which would need its own
  * from-scratch correctness argument and risks masking a real cubical bug as a triangulation bug or vice versa.
  */
class CubicalStreamSpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-9)
  given chc: CubicalHomologyContext[Double, Double] = CubicalHomologyContext()
  import chc.{*, given}

  // ---------------------------------------------------------------------------------------------------------
  // Monotonicity: fv(face) <= fv(coface) for every boundary relationship, exhaustively on small random images.
  // This is FORCED by CellularHomologyContext.processingOrder's ascending sort (see Homology.scala), not a style
  // choice -- getting it backwards produces the exact "reduction pivot ... was not a recorded open class" crash
  // this codebase has already hit three times for other streams (see WORKLOG-cubical.md's advisor-consult
  // section).
  // ---------------------------------------------------------------------------------------------------------

  case class TestImage(shape: IndexedSeq[Int], values: IndexedSeq[Int])

  // A single combined generator (rather than one Gen per dimension) -- picks ambient dimension 2 or 3 itself,
  // with axis/value ranges small enough that a 3D draw stays cheap. Wired in via `Arbitrary`/`prop {}`, NOT
  // `org.scalacheck.Prop.forAll(gen) { ... }` directly -- the latter's explicit-Gen overload was found (by
  // compiling it) to collide with ScalaCheck's OTHER `forAll` overloads (the all-implicit-Arbitrary, up-to-8-
  // type-param one) badly enough that the compiler mis-inferred a nested pattern-match variable's type several
  // lines into the lambda body (`face` came back as the opaque type's raw `Vector[Int]` representation instead
  // of `Cube`) rather than reporting a clean overload-resolution error. `prop { (img: TestImage) => ... }`,
  // matching `FiniteFieldSpec.scala`'s already-proven-working pattern, sidesteps the overload set entirely.
  def genTestImage: Gen[TestImage] =
    for
      dims <- Gen.choose(2, 3)
      maxAxis = if dims == 2 then 4 else 3
      maxValue = if dims == 2 then 3 else 2
      shape <- Gen.listOfN(dims, Gen.choose(1, maxAxis)).map(_.toIndexedSeq)
      total = shape.product
      values <- Gen.listOfN(total, Gen.choose(0, maxValue)).map(_.toIndexedSeq)
    yield TestImage(shape, values)

  given Arbitrary[TestImage] = Arbitrary(genTestImage)

  def valueFnOf(img: TestImage): IndexedSeq[Int] => Double =
    idx =>
      val flat = idx.zip(img.shape).foldLeft(0) { case (acc, (i, n)) => acc * n + i }
      img.values(flat).toDouble

  "fv(face) <= fv(coface) on every boundary relationship, exhaustively on small random 2D/3D images" >>
    AsResult {
      prop { (img: TestImage) =>
        val stream = CubicalGridStream(img.shape, valueFnOf(img))
        (0 until stream.ambientDim).forall { d =>
          stream.iterateDimension(d).forall { cell =>
            // Explicit LHS type ascription, not `cell.boundary[Double].forall { case (face, _) => ... }`
            // directly: without expected-type pressure, extension-method search here resolved `.boundary` to
            // the WRONG candidate (Chain.scala's generic `Chain[CellT, CoefficientT]` extension of the same
            // name) and silently inferred `face` as the opaque type's raw `Vector[Int]` representation instead
            // of `Cube` -- same failure class as the `underlying`/`show` collisions in Cubical.scala, but
            // triggered by call-site inference rather than a literal name clash at the definition site. See
            // WORKLOG-cubical.md.
            val faces: Seq[(Cube, Double)] = cell.boundary[Double]
            faces.forall { case (face, _) =>
              stream.filtrationValue(face) <= stream.filtrationValue(cell)
            }
          }
        }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Structural invariant (bars-account-for-cells), reusing HomologyFixtures' existing helper.
  // ---------------------------------------------------------------------------------------------------------

  "The bars-account-for-cells structural invariant holds on random small images" >>
    AsResult {
      prop { (img: TestImage) =>
        val stream = CubicalGridStream(img.shape, valueFnOf(img))
        val barcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
        HomologyFixtures.totalBarsAccountForAllCells(barcode, stream.totalCellCount.toInt)
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Independent H0 cross-check via union-find on the sublevel set of PRESENT PIXELS -- fully independent of
  // Cube/Chain/CellularHomologyContext. Connectivity here MUST be Moore/Chebyshev (8-connected in 2D,
  // 26-connected in 3D: any two present pixels whose indices differ by at most 1 in every coordinate), not
  // face/edge (4-connected in 2D) adjacency -- two pixels touching only at a shared CORNER vertex are genuinely
  // in the same path component of the cubical complex whenever that shared vertex is present (its fv is the min
  // over ALL up-to-2^d touching pixels, which includes both diagonal neighbors), so 4-connectivity would
  // overcount components and produce a WRONG independent oracle. Derived and cross-checked against the
  // hand-verified fixtures below before trusting it as an oracle for the random-image property test -- see
  // WORKLOG-cubical.md.
  // ---------------------------------------------------------------------------------------------------------

  def independentH0Count(shape: IndexedSeq[Int], value: IndexedSeq[Int] => Double, threshold: Double): Int =
    def indices(dims: IndexedSeq[Int]): Seq[IndexedSeq[Int]] =
      dims.foldLeft(Seq(IndexedSeq.empty[Int])) { (acc, n) =>
        for prefix <- acc; v <- 0 until n yield prefix :+ v
      }
    val present = indices(shape).filter(idx => value(idx) <= threshold)
    val uf = new UnionFind[IndexedSeq[Int]](present)
    import uf.UFSet
    for
      p <- present
      q <- present
      if p != q
      if p.lazyZip(q).forall { case (a, b) => math.abs(a - b) <= 1 }
    do uf.union(UFSet(p), UFSet(q))
    present.map(p => uf.find(UFSet(p))).toSet.size

  "H0 (open positive classes at a threshold) matches an independent union-find count, on random small images" >>
    AsResult {
      prop { (img: TestImage) =>
        val stream = CubicalGridStream(img.shape, valueFnOf(img))
        val state = persistentHomology(stream)
        val thresholds = (img.values.map(_.toDouble).distinct :+ -1.0).sorted
        thresholds.forall { t =>
          state.advanceTo(t)
          val reported = state.positives.count { case (sigma, _) => sigma.dim == 0 }
          val independent = independentH0Count(img.shape, valueFnOf(img), t)
          reported == independent
        }
      }
    }

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived fixtures. Every count below comes from a general fact, not a guess: for a d-cell grid's FULL
  // 1-skeleton (V vertices, E edges, connected), the elder-rule/reduction algorithm opens exactly V classes at
  // dimension 0, of which exactly (V-1) get killed by "tree" edges (one component always survives) and the
  // remaining (E-(V-1)) "extra" edges each open a NEW dimension-1 class -- a standard planar-graph fact
  // (E-V+1 = number of bounded faces = number of pixels, by Euler's formula), so the count of dimension-1
  // births always equals the pixel count exactly, and since there is no dimension-3 cell to make a pixel
  // itself essential, every pixel is guaranteed to kill exactly one dimension-1 class. Combined with "all
  // vertices/edges tie at the same value in these fixtures, so H0/H1 births are simultaneous," this pins down
  // the EXACT bar-count breakdown below, not just the presence of the topologically meaningful bars -- see
  // WORKLOG-cubical.md for the full derivation (this matters BECAUSE these fixtures are tie-heavy, exactly the
  // regime this codebase's filtrationOrdering bugs have historically hidden in).
  // ---------------------------------------------------------------------------------------------------------

  "A constant-valued 2x2 image (9 vertices, 12 edges, 4 pixels, totalCells=25) reduces correctly" >> {
    val shape = IndexedSeq(2, 2)
    val stream = CubicalGridStream(shape, _ => 5.0)
    val barcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    (stream.totalCellCount must beEqualTo(25L)) and
      (HomologyFixtures.totalBarsAccountForAllCells(barcode, 25) must beTrue) and
      (barcode.count(_._1 == 0) must beEqualTo(9)) and
      (barcode.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (barcode.count { case (0, b, d) => b == d; case _ => false } must beEqualTo(8)) and
      (barcode.count(_._1 == 1) must beEqualTo(4)) and
      (barcode.forall { case (1, b, d) => b == d; case _ => true } must beTrue) and
      (barcode.count(_._1 >= 2) must beEqualTo(0))
  }

  "A single bright center pixel in an otherwise dark 3x3 image (16 vertices, 24 edges, 9 pixels, " +
    "totalCells=49) produces exactly one persistent H1 bar (the hollow center)" >> {
      val shape = IndexedSeq(3, 3)
      val valueFn: IndexedSeq[Int] => Double = idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0
      val stream = CubicalGridStream(shape, valueFn)
      val barcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
      (stream.totalCellCount must beEqualTo(49L)) and
        (HomologyFixtures.totalBarsAccountForAllCells(barcode, 49) must beTrue) and
        (barcode.count(_._1 == 0) must beEqualTo(16)) and
        (barcode.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
        (barcode.count { case (0, b, d) => b == d; case _ => false } must beEqualTo(15)) and
        (barcode.count(_._1 == 1) must beEqualTo(9)) and
        (barcode.count { case (1, b, d) => b == d; case _ => false } must beEqualTo(8)) and
        (barcode.exists(_ == (1, 0.0, 1.0)) must beTrue) and
        (barcode.count(_._1 >= 2) must beEqualTo(0))
    }

  "Two separated 1D blobs (values [0,2,0], 4 vertices, 3 pixels, totalCells=7) merge into one component" >> {
    val shape = IndexedSeq(3)
    val valueFn: IndexedSeq[Int] => Double = idx => if idx(0) == 1 then 2.0 else 0.0
    val stream = CubicalGridStream(shape, valueFn)
    val barcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
    (stream.totalCellCount must beEqualTo(7L)) and
      (HomologyFixtures.totalBarsAccountForAllCells(barcode, 7) must beTrue) and
      (barcode.count(_._1 == 0) must beEqualTo(4)) and
      (barcode.count { case (0, _, d) => d.isInfinite; case _ => false } must beEqualTo(1)) and
      (barcode.count { case (0, b, d) => b == d; case _ => false } must beEqualTo(2)) and
      (barcode.exists(_ == (0, 0.0, 2.0)) must beTrue) and
      (barcode.count(_._1 >= 1) must beEqualTo(0))
  }

  "The same two-blobs fixture's independent union-find H0 count matches the reduction engine's, at both thresholds" >> {
    val shape = IndexedSeq(3)
    val valueFn: IndexedSeq[Int] => Double = idx => if idx(0) == 1 then 2.0 else 0.0
    val stream = CubicalGridStream(shape, valueFn)
    val state = persistentHomology(stream)
    state.advanceTo(0.0)
    val atZero = state.positives.count { case (sigma, _) => sigma.dim == 0 }
    state.advanceTo(2.0)
    val atTwo = state.positives.count { case (sigma, _) => sigma.dim == 0 }
    (atZero must beEqualTo(independentH0Count(shape, valueFn, 0.0))) and
      (atZero must beEqualTo(2)) and
      (atTwo must beEqualTo(independentH0Count(shape, valueFn, 2.0))) and
      (atTwo must beEqualTo(1))
  }

  // ---------------------------------------------------------------------------------------------------------
  // ExplicitCubicalStream, otherwise entirely unexercised by every test above (all of which go through
  // CubicalGridStream) -- flagged by the advisor as a real gap, not a nice-to-have: ExplicitCubicalStream takes
  // filtration values from a caller-supplied Map with NOTHING enforcing monotonicity (CubicalGridStream gets it
  // for free, by construction, from min-over-cofaces), and it has its OWN independently-written
  // filtrationOrdering block, textually duplicated from CubicalGridStream's rather than shared -- exactly the
  // kind of duplicated comparator this codebase's history (CLAUDE.md's "Bug found while cross-validating"
  // section) shows drifting out of sync in a way that only shows up as a corrupted reduction, not a compile
  // error. Reusing the already-hand-derived, already-independently-cross-checked two-blobs fixture here (rather
  // than a fresh one) means this test verifies TWO things at once: that ExplicitCubicalStream's own reduction is
  // correct, AND that its filtrationOrdering agrees with CubicalGridStream's on the same cells/values (not just
  // each individually self-consistent).
  // ---------------------------------------------------------------------------------------------------------

  "ExplicitCubicalStream reproduces the same two-blobs barcode as CubicalGridStream on the identical cells/values" >> {
    val gridStream = CubicalGridStream(IndexedSeq(3), idx => if idx(0) == 1 then 2.0 else 0.0)
    val gridBarcode = persistentHomology(gridStream).diagramAt(Double.PositiveInfinity)

    val explicitStream = ExplicitCubicalStream(
      0.0 -> Cube.vertex(0),
      0.0 -> Cube.vertex(1),
      0.0 -> Cube.vertex(2),
      0.0 -> Cube.vertex(3),
      0.0 -> Cube.unitCube(0),
      2.0 -> Cube.unitCube(1),
      0.0 -> Cube.unitCube(2)
    )
    val explicitBarcode = persistentHomology(explicitStream).diagramAt(Double.PositiveInfinity)

    (explicitBarcode must containTheSameElementsAs(gridBarcode)) and
      (HomologyFixtures.totalBarsAccountForAllCells(explicitBarcode, 7) must beTrue) and
      (explicitBarcode.exists(_ == (0, 0.0, 2.0)) must beTrue)
  }

  // ---------------------------------------------------------------------------------------------------------
  // CellularPersistenceInChunksContext[Cube, ...] cross-validation. This exact combination -- the generic chunks
  // engine plugged into Cube -- had never been exercised anywhere in this codebase before (grep confirmed zero
  // hits for `CellularPersistenceInChunksContext[Cube`), a genuine gap given the chunks engine's own history
  // (CLAUDE.md's "Cross-engine benchmark" section: a real, previously-unknown bug at multiple tied essential
  // classes under a bounded maxDim, caught only once a case exercising that combination was tried).
  //
  // Naive and chunks run on the SAME stream, so they share CubicalGridStream.filtrationOrdering -- a bug in that
  // ordering would make both sides wrong the SAME way and naive-vs-chunks agreement alone couldn't catch it.
  // That's why HomologyFixtures.totalBarsAccountForAllCells is also run on chunks' OWN output: an independent
  // structural invariant that doesn't depend on naive being right, catching a chunks-specific defect even if the
  // shared ordering were somehow broken. Reuses these same tie-heavy fixtures (not the random-image generator
  // above) on purpose -- see the hand-derived-fixtures header comment above for why tie-heavy is where
  // filtrationOrdering bugs in this codebase have historically hidden.
  //
  // maxDim is pinned explicitly to each fixture's own ambient dimension: chunks' maxDim means "top homological
  // degree reported" (it internally walks maxDim+1), while naive has no maxDim parameter at all and simply
  // computes through the stream's actual top cube dimension -- for a `dims`-dimensional grid that's `dims`
  // itself, so passing maxDim=dims makes the two calls ask the same question rather than risking a semantics
  // mismatch masquerading as a correctness bug.
  // ---------------------------------------------------------------------------------------------------------

  case class ChunksCase(stream: CubicalGridStream, cellCount: Int, maxDim: Int)

  "CellularPersistenceInChunksContext[Cube,...] matches the naive engine and its own structural invariant on the tie-heavy fixtures" >> {
    val cases = Seq(
      ChunksCase(CubicalGridStream(IndexedSeq(2, 2), _ => 5.0), 25, 2),
      ChunksCase(
        CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0),
        49,
        2
      ),
      ChunksCase(CubicalGridStream(IndexedSeq(3), idx => if idx(0) == 1 then 2.0 else 0.0), 7, 1)
    )
    cases
      .map { c =>
        val naiveBarcode = persistentHomology(c.stream).diagramAt(Double.PositiveInfinity)
        val chunksBarcode = CellularPersistenceInChunksContext[Cube, Double](c.maxDim)
          .persistentHomology(c.stream)
          .diagramAt(Double.PositiveInfinity)
        (HomologyFixtures.totalBarsAccountForAllCells(chunksBarcode, c.cellCount) must beTrue) and
          (chunksBarcode must containTheSameElementsAs(naiveBarcode))
      }
      .reduce(_ and _)
  }

  // Generic-over-CellT coverage for barcodeAt's incremental representative tracking
  // (.claude/WORKLOG-chunks-representatives-incremental.md) -- Cube is exactly the kind of non-Simplex
  // OrderedCell instance that motivated genericizing CellularPersistenceInChunksContext in the first place,
  // so it's real coverage, not a formality: vcolOf's own reduction and fold logic both need to work for
  // Cube specifically, not just Simplex[Int] (which every fixture elsewhere in this file, and every fixture
  // barcodeAt was originally developed against, happens to use).
  "CellularPersistenceInChunksContext[Cube,...]'s barcodeAt gives every bar a genuine-cycle representative matching the naive engine's exactly" >> {
    val cases = Seq(
      ChunksCase(CubicalGridStream(IndexedSeq(2, 2), _ => 5.0), 25, 2),
      ChunksCase(
        CubicalGridStream(IndexedSeq(3, 3), idx => if idx == IndexedSeq(1, 1) then 1.0 else 0.0),
        49,
        2
      ),
      ChunksCase(CubicalGridStream(IndexedSeq(3), idx => if idx(0) == 1 then 2.0 else 0.0), 7, 1)
    )
    cases
      .map { c =>
        val naiveBars = persistentHomology(c.stream).barcodeAt(Double.PositiveInfinity)
        val chunksBars = CellularPersistenceInChunksContext[Cube, Double](c.maxDim)
          .persistentHomology(c.stream)
          .barcodeAt(Double.PositiveInfinity)

        val noneMissing = chunksBars.forall(_.annotation.isDefined)
        val allCycles = chunksBars.forall(b => Chain.from(b.annotation.get.boundary).isZero())
        // Multiset match via Chain's own overridden `equals`, not `.toSet` (Chain has no matching `hashCode`
        // override -- see HomologySpec's own analogous check for the full reasoning).
        val remaining =
          scala.collection.mutable.ArrayBuffer.from(naiveBars.map(b => (b.dim, b.lower, b.upper, b.annotation.get)))
        val matchesNaive = chunksBars.forall { b =>
          val idx = remaining.indexWhere { case (d, l, u, rep) =>
            d == b.dim && l == b.lower && u == b.upper && rep == b.annotation.get
          }
          idx >= 0 && { remaining.remove(idx); true }
        } && remaining.isEmpty

        (noneMissing must beTrue) and (allCycles must beTrue) and (matchesNaive must beTrue)
      }
      .reduce(_ and _)
  }

  // Broader fuzz for the dimension-0/1 raw-union-find fast path added to CellularPersistenceInChunksContext
  // (.claude/WORKLOG-unionfind-in-chunks.md) -- reuses genTestImage above (small integer values, so
  // low-effort-tied by construction) rather than the fixed tie-heavy fixtures alone, since that's exactly the
  // hazard an order-dependent union-find bug would show up in first, per this codebase's own established
  // cubical validation discipline (CLAUDE.md: "hand-derived fixtures... deliberately chosen TIE-HEAVY").
  "CellularPersistenceInChunksContext[Cube,...]'s union-find fast path agrees with the naive engine on random tie-heavy images" >>
    AsResult {
      prop { (img: TestImage) =>
        val stream = CubicalGridStream(img.shape, valueFnOf(img))
        val cellCount = stream.totalCellCount
        val naiveBarcode = persistentHomology(stream).diagramAt(Double.PositiveInfinity)
        val chunksBarcode = CellularPersistenceInChunksContext[Cube, Double](img.shape.size)
          .persistentHomology(stream)
          .diagramAt(Double.PositiveInfinity)
        (HomologyFixtures.totalBarsAccountForAllCells(chunksBarcode, cellCount.toInt) must beTrue) and
          (chunksBarcode must containTheSameElementsAs(naiveBarcode))
      }
    }
