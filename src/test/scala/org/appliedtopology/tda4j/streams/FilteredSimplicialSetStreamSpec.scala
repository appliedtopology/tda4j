package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.cells.SimplicialSetFixtures
import org.appliedtopology.tda4j.homology.{given, *}

import org.specs2.mutable

/** Real (non-constant) filtration for `FiniteSimplicialSet`, and cross-validation of the newly-genericized
  * `CellularPersistenceInChunksContext` against the already-trusted `CellularHomologyContext` on it. Full derivation,
  * including the `advisor()`-driven design corrections, in `.claude/WORKLOG-simplicial-set-filtration.md`.
  */
class FilteredSimplicialSetStreamSpec extends mutable.Specification:
  import SimplicialSetFixtures.TorusGenerator
  import SimplicialSetFixtures.TorusGenerator.*

  private val f11 = new FiniteField(11)
  import f11.given

  // Deliberately NOT dimension-aligned: A/B/C (same dimension) get three DIFFERENT values, so filtration order
  // and dimension order genuinely disagree on which edge comes "first" -- the case that would catch a reversed
  // primary key in filtrationOrdering, which a dimension-aligned filtration (e.g. all edges tied) would not.
  private val torusFiltration: PartialFunction[TorusGenerator, Double] =
    case Vertex => 0.0
    case A      => 1.0
    case B      => 2.0
    case C      => 3.0
    case U      => 4.0
    case L      => 5.0

  private def diagramsFor[G](
    sset: FiniteSimplicialSet[G],
    filtrationValue: G => Double
  ): (List[(Int, Double, Double)], List[(Int, Double, Double)]) =
    given (G is OrderedCell) = sset.cellInstance
    val stream = FilteredSimplicialSetStream(sset, PartialFunction.fromFunction(filtrationValue))
    val naive = CellularHomologyContext[G, f11.Fp, Double]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    val chunks = CellularPersistenceInChunksContext[G, f11.Fp]()
      .persistentHomology(stream)
      .diagramAt(Double.PositiveInfinity)
    (naive.sorted, chunks.sorted)

  "validateMonotoneFiltration accepts the hand-picked torus filtration and rejects a deliberately broken one" >> {
    val broken: PartialFunction[TorusGenerator, Double] =
      case Vertex => 0.0
      case A      => 1.0
      case B      => 2.0
      case C      => 3.0
      case U      => 0.5 // U's own face B (2.0) has a LARGER value than U (0.5) -- not monotone.
      case L      => 5.0

    (validateMonotoneFiltration(SimplicialSetFixtures.torus, torusFiltration) must beEmpty)
      .and(validateMonotoneFiltration(SimplicialSetFixtures.torus, broken) must not(beEmpty))
  }

  "FilteredSimplicialSetStream's iterateDimension buckets are oldest-first, and every bare direct face precedes its generator in dimension-major order" >> {
    given (TorusGenerator is OrderedCell) = SimplicialSetFixtures.torus.cellInstance
    val stream = FilteredSimplicialSetStream(SimplicialSetFixtures.torus, torusFiltration)

    val byDim: IndexedSeq[Vector[TorusGenerator]] =
      (0 until SimplicialSetFixtures.torus.generatorsByDim.length).map(d => stream.iterateDimension(d).toVector)

    val bucketsAscending: Boolean = byDim.forall { (bucket: Vector[TorusGenerator]) =>
      val values: Vector[Double] = bucket.map(torusFiltration)
      values.sliding(2).forall {
        case Seq(a, b) => a <= b
        case _         => true
      }
    }

    val allCellsLike: Vector[TorusGenerator] = byDim.toVector.flatten
    val position: Map[TorusGenerator, Int] = allCellsLike.zipWithIndex.toMap
    val facesPrecedeCofaces: Boolean = allCellsLike.zipWithIndex.forall { (g: TorusGenerator, i: Int) =>
      val bareFaces: IndexedSeq[TorusGenerator] =
        SimplicialSetFixtures.torus.faces(g).collect { case SSetElement(Nil, target) => target }
      bareFaces.forall(f => position(f) < i)
    }

    (bucketsAscending must beTrue).and(facesPrecedeCofaces must beTrue)
  }

  "the torus's hand-picked non-dimension-aligned filtration: CellularHomologyContext and the chunks engine agree exactly, and the agreed answer matches the hand-derived structure" >> {
    val (naive, chunks) = diagramsFor(SimplicialSetFixtures.torus, torusFiltration)

    // Hand-derived structurally, without guessing the pivot-selection tie-break (see the WORKLOG): U (older,
    // fv=4.0) must be the one that kills a 1-cycle among {A,B,C} (its raw boundary is nonzero and nothing has
    // been paired yet when it's processed); L (younger, fv=5.0) then reduces to zero against U's own recorded
    // boundary (identical face data) and survives as the essential H_2 class.
    val essential0 = naive.collect { case (0, b, Double.PositiveInfinity) => b }
    val essential1 = naive.collect { case (1, b, Double.PositiveInfinity) => b }
    val essential2 = naive.collect { case (2, b, Double.PositiveInfinity) => b }
    val finite = naive.filter { case (_, _, d) => d != Double.PositiveInfinity }

    (naive === chunks)
      .and(essential0 === List(0.0))
      .and(essential2 === List(5.0))
      .and(finite.map(_._1) === List(1))
      .and(finite.map(_._3) === List(4.0))
      .and((essential1.toSet ++ finite.map(_._2).toSet) === Set(1.0, 2.0, 3.0))
      .and(essential1.size === 2)
  }

  // Generic-over-CellT coverage for barcodeAt's incremental representative tracking
  // (.claude/WORKLOG-chunks-representatives-incremental.md): FiniteSimplicialSet generators are the third
  // concrete OrderedCell instance in this codebase (alongside Simplex/Cube) and the only one whose boundary
  // formula involves the degeneracy machinery at all -- real coverage, not a formality. RP2 over F3
  // specifically: it's this codebase's established sign-discriminating
  // fixture (H_1=H_2=F2 over F2, both 0 over F3 -- an alternating-sum sign error is invisible over F2), so a
  // representative-formula sign bug (exactly the class of bug the SimplicialHomologyByDimensionContext audit
  // below found) has a real chance to surface here where it wouldn't over F2.
  "barcodeAt gives every bar (torus, RP2 over F3) a genuine-cycle representative with no missing annotation" >> {
    val f3 = new FiniteField(3)
    import f3.given

    def checkAllReps[G](sset: FiniteSimplicialSet[G], filtrationValue: G => Double): Boolean =
      given (G is OrderedCell) = sset.cellInstance
      val stream = FilteredSimplicialSetStream(sset, PartialFunction.fromFunction(filtrationValue))
      val bars = CellularPersistenceInChunksContext[G, f3.Fp]()
        .persistentHomology(stream)
        .barcodeAt(Double.PositiveInfinity)
      bars.nonEmpty && bars.forall { bar =>
        bar.annotation match
          case None      => false
          case Some(rep) =>
            Chain.from(rep.boundary).isZero() &&
            rep.rawEntries.size <= sset.generatorsByDim.map(_.size).sum
      }

    val torusOk = checkAllReps(SimplicialSetFixtures.torus, torusFiltration)
    val rp2 = SimplicialSetFixtures.realProjectiveSpace(2)
    val rp2Filtration: SimplicialSetFixtures.ProjectiveGenerator => Double = {
      case SimplicialSetFixtures.ProjectiveGenerator.E(n) => n.toDouble
    }
    val rp2Ok = checkAllReps(rp2, rp2Filtration)
    (torusOk must beTrue) and (rp2Ok must beTrue)
  }

  "a randomized dimension-band-plus-jitter filtration on every fixture: CellularHomologyContext and the chunks engine agree exactly, across several seeds" >> {
    def randomFiltration[G](sset: FiniteSimplicialSet[G], seed: Long): G => Double =
      val rng = new scala.util.Random(seed)
      val jitter: Map[G, Double] = sset.generatorsByDim.flatten.map(g => g -> rng.nextDouble()).toMap
      g => sset.dimOf(g).toDouble * 1000.0 + jitter(g)

    def check[G](sset: FiniteSimplicialSet[G], seed: Long): Boolean =
      val fv = randomFiltration(sset, seed)
      val monotone = validateMonotoneFiltration(sset, fv).isEmpty
      val (naive, chunks) = diagramsFor(sset, fv)
      monotone && naive == chunks

    val seeds = 1L to 5L

    val sphereChecks = (1 to 3).forall(n => seeds.forall(s => check(SimplicialSetFixtures.minimalSphere(n), s)))
    val projectiveChecks =
      (2 to 3).forall(n => seeds.forall(s => check(SimplicialSetFixtures.realProjectiveSpace(n), s)))
    val torusChecks = seeds.forall(s => check(SimplicialSetFixtures.torus, s))

    (sphereChecks must beTrue).and(projectiveChecks must beTrue).and(torusChecks must beTrue)
  }
