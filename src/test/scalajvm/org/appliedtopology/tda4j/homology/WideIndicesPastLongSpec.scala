package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `PackedRipserCohomologyEngine` on runs whose combinatorial indices pass 2^63, which only its two-word indices can
  * number (`SimplexIndexing` throws on them): clouds of 300 points to degree 11, where a simplex of twelve vertices has
  * an index of 70 bits. The checks do not lean on a one-word run, which cannot exist, but on what the complex is:
  *   - clusters of 13 points, each a simplex of its own: every simplex is a birth or a death exactly once, the counts
  *     per dimension are binomial coefficients, decoding the cells of every pair gives distinct vertices of one
  *     cluster, and the pairs, the barcode, the apparent pairs and the simplex count are the clusters' together, each
  *     cluster run alone by the one-word engine (a disjoint union pairs as its parts do, and a cluster's vertices keep
  *     their order, so its colexicographic tie-breaks are the same);
  *   - the boundary of the 12-dimensional cross-polytope, which is a 11-sphere: one essential bar in degree 11, and its
  *     cocycle takes the value 1 on its cycle's cells in the sense of the pairing (the cocycle is not a coboundary).
  */
class WideIndicesPastLongSpec extends Specification:
  sequential // the clouds are large for a spec; one at a time

  private val f17 = FiniteField(17)
  import f17.given

  private def binomial(n: Int, k: Int): Long = SimplexIndexing.binomial(n, k)

  private def isEssential[A](bar: PersistenceBar[Double, A]): Boolean =
    BlockParallelReduction.endpoint(bar.upper) == Double.PositiveInfinity

  private def bars(
    engine: PackedRipserCohomologyEngine[f17.Fp]
  ): Vector[(Int, Double, Double)] =
    engine
      .persistentCohomology()
      .map(b => (b.dim, BlockParallelReduction.endpoint(b.lower), BlockParallelReduction.endpoint(b.upper)))
      .toVector
      .sorted

  // 23 clusters of 13 points, cluster c holding the vertices c, c + 23, c + 46, ...: a cluster is a ball of radius 0.5,
  // the clusters are 10 apart, and the threshold 1.5 joins every pair inside a cluster and none across.
  private val clusters = 23
  private val perCluster = 13
  private val maxDim = 11
  private val clusteredPoints: Array[Array[Double]] =
    val rng = scala.util.Random(31)
    Array.tabulate(clusters * perCluster) { v =>
      val centre = 10.0 * (v % clusters)
      Array(centre + rng.nextDouble() - 0.5, rng.nextDouble() - 0.5, rng.nextDouble() - 0.5)
    }
  private val clustered = EuclideanMetricSpace(clusteredPoints)
  private val clusteredThreshold = 1.5

  "On 300 points in 23 clusters to degree 11" >> {
    val engine = PackedRipserCohomologyEngine[f17.Fp](clustered, maxDim, maxFiltrationValue = Some(clusteredThreshold))
    val pairs = engine.pairedCohomology(cocycles = false).map(_._2)

    "the engine numbers the simplices with two words, and some index passes 2^63" >>
      (engine.usesWideIndices must beTrue).and(
        pairs.exists(p => p.birth.high != 0L || p.death.exists(_.high != 0L)) must beTrue
      )

    "every simplex is a birth or a death once: the simplices of each dimension are the births in it and the deaths from below" >> {
      val births = pairs.groupBy(_.dim).view.mapValues(_.size).toMap
      val deaths = pairs.filter(_.death.isDefined).groupBy(_.dim).view.mapValues(_.size).toMap
      val mismatches = (0 to maxDim).filter { d =>
        births.getOrElse(d, 0) + deaths.getOrElse(d - 1, 0) != clusters * binomial(perCluster, d + 1)
      }
      mismatches must beEmpty
    }

    "the cells of every pair decode to distinct vertices of one cluster" >> {
      def inOneCluster(cell: engine.DiameterIndex, size: Int): Boolean =
        val vs = engine.vertices(cell, size)
        vs.distinct.length == size && vs.map(_ % clusters).distinct.length == 1 && vs.sameElements(vs.sorted)
      val bad = pairs.filterNot { p =>
        inOneCluster(p.birth, p.dim + 1) && p.death.forall(inOneCluster(_, p.dim + 2))
      }
      bad must beEmpty
    }

    "the clusters are what is left at the end: one essential bar each, in degree 0" >> {
      val essential = engine.persistentCohomology().filter(isEssential)
      (essential.map(_.dim).distinct must beEqualTo(List(0))).and(essential.size must beEqualTo(clusters))
    }

    // A pair by the global vertices of its cells.
    def pairOf(e: PackedRipserCohomologyEngine[f17.Fp], global: Array[Int] => Array[Int])(
      p: Involution.Pair[e.DiameterIndex]
    ) =
      (
        p.dim,
        global(e.vertices(p.birth, p.dim + 1)).toList,
        p.death.map(d => global(e.vertices(d, p.dim + 2)).toList)
      )
    // Each cluster alone: 13 points, so one-word indices, and the apparent pairs on.
    lazy val alone = (0 until clusters).map { c =>
      val members = clusteredPoints.indices.filter(_ % clusters == c).toArray
      val one = PackedRipserCohomologyEngine[f17.Fp](
        EuclideanMetricSpace(members.map(clusteredPoints)),
        maxDim,
        maxFiltrationValue = Some(clusteredThreshold)
      )
      val pairs = one.pairedCohomology(cocycles = false).map(_._2).map(pairOf(one, _.map(members)))
      (one, pairs)
    }

    "the pairs, bars and counts are the clusters' together, each cluster run alone with one-word indices" >>
      (alone.exists(_._1.usesWideIndices) must beFalse)
        .and(pairs.size must beEqualTo(alone.map(_._2.size).sum))
        .and((pairs.map(pairOf(engine, identity)).toSet == alone.flatMap(_._2).toSet) must beTrue)
        .and(engine.apparentPairCount must beEqualTo(alone.map(_._1.apparentPairCount).sum))
        .and(engine.totalSimplexCount must beEqualTo(alone.map(_._1.totalSimplexCount).sum))
        .and((bars(engine) == alone.flatMap(c => bars(c._1)).toVector.sorted) must beTrue)

    // With no apparent pairs every column is reduced on the working column, whose order has to use the high word.
    "the same with every column reduced, none paired as apparent" >> {
      val reduced = PackedRipserCohomologyEngine[f17.Fp](
        clustered,
        maxDim,
        useApparentPairs = false,
        maxFiltrationValue = Some(clusteredThreshold)
      )
      val reducedPairs = reduced.pairedCohomology(cocycles = false).map(_._2)
      (reduced.apparentPairCount must beEqualTo(0))
        .and(reducedPairs.size must beEqualTo(pairs.size))
        .and((reducedPairs.map(pairOf(reduced, identity)).toSet == alone.flatMap(_._2).toSet) must beTrue)
        .and((bars(reduced) == bars(engine)) must beTrue)
    }
  }

  // The boundary of the cross-polytope in R^12: the points +-e_i, two at distance sqrt(2) unless they are opposite,
  // where the distance is 2. Its Vietoris-Rips complex at radius 1.5 is the boundary complex, a sphere of dimension 11
  // (3^12 faces), born at sqrt(2). The other 276 points are far away and come first, so the sphere's vertices are 276 to
  // 299 and every one of its top simplices has an index of 70 bits.
  private val isolated = 276
  private val crossPolytope: EuclideanMetricSpace =
    EuclideanMetricSpace(Array.tabulate(isolated + 24) { v =>
      if v < isolated then Array.tabulate(12)(i => if i == 0 then 1000.0 * (v + 1) else 0.0)
      else
        val k = v - isolated
        Array.tabulate(12)(i => if i == k / 2 then if k % 2 == 0 then 1.0 else -1.0 else 0.0)
    })

  "On the boundary of the 12-dimensional cross-polytope among 276 far points, to degree 11" >> {
    val engine = PackedRipserCohomologyEngine[f17.Fp](crossPolytope, 11, maxFiltrationValue = Some(1.5))
    val cocycles = engine.persistentCohomology()
    val cycles = engine.persistentHomology()
    val sphere = cocycles.filter(_.dim == 11)

    "it has one essential bar in degree 11, from sqrt(2), and one in degree 0 for each far point and one for the rest" >> {
      val essential = cocycles.filter(isEssential).map(_.dim).sorted
      (engine.usesWideIndices must beTrue)
        .and(essential must beEqualTo(List.fill(isolated + 1)(0) :+ 11))
        .and(sphere.map(b => BlockParallelReduction.endpoint(b.lower)) must beEqualTo(List(math.sqrt(2.0))))
        .and(cocycles.filter(_.dim == 0).count(!isEssential(_)) must beEqualTo(23))
        .and(cocycles.filter(b => b.dim != 0 && b.dim != 11) must beEmpty)
    }

    "the cocycle is not a coboundary: it pairs to a unit with the cycle, both over cells with indices past 2^63" >> {
      val cocycle = sphere.head.representative
      val cycle = cycles.find(_.dim == 11).get.representative
      val fr = summon[f17.Fp is Field]
      val values = cycle.rawEntries
        .groupMapReduce(_._1)(_._2)(fr.plus)
        .filterNot((_, k) => fr.isEqual(k, fr.zero))
      val pairing = cocycle.rawEntries
        .groupMapReduce(_._1)(_._2)(fr.plus)
        .iterator
        .map((cell, k) => values.get(cell).fold(fr.zero)(fr.times(k, _)))
        .foldLeft(fr.zero)(fr.plus)
      (values.keys.exists(_.high != 0L) must beTrue)
        .and(fr.isEqual(pairing, fr.zero) must beFalse)
    }
  }
