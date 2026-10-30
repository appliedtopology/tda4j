package org.appliedtopology.tda4j

import Persistence.Engine

import org.specs2.mutable.Specification

/** The cells that open and close a bar: `bar.birthCell` is the simplex whose entry creates the class (its dimension is
  * the bar's, its filtration value the bar's birth) and `bar.deathCell` the simplex that kills it (one dimension up,
  * valued at the bar's death; none for an essential bar). The oracles are the values and dimensions of the cells,
  * computed here from the points, and the pairing itself: in general position the pairing is determined by the total
  * order of the cells, so every engine must find the same one, whichever kind of representative it was asked for.
  */
class BarCellsSpec extends Specification:

  private def randomPoints(n: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()))

  private val points = randomPoints(14, 3L)
  private val space = EuclideanMetricSpace(points)

  private def diameter(cell: Simplex[Int]): Double =
    val vs = cell.toList
    if vs.size < 2 then 0.0 else vs.combinations(2).map(pair => space.distance(pair(0), pair(1))).max

  private val engines = List(Engine.Chunks, Engine.Naive, Engine.Cohomology, Engine.Ripser, Engine.ParallelRipser)
  private val kinds = List(Representatives.Cycles, Representatives.Cocycles)

  private def diagram(engine: Engine, kind: Representatives, p: Int = 17) =
    Persistence(points, maxDimension = 2, engine = engine, representatives = kind, characteristic = p)

  private def pairing(d: PersistenceDiagram[Simplex[Int]]): Set[(Int, Simplex[Int], Option[Simplex[Int]])] =
    d.bars.map(b => (b.dim, b.birthCell.get, b.deathCell)).toSet

  "a bar's cells" should {
    "have the dimension and the filtration value of the bar's endpoints, for every engine and kind of representative" in {
      val failures = for
        engine <- engines
        kind <- kinds
        bar <- diagram(engine, kind).bars
        problem <- List(
          Option.when(bar.cells.isEmpty)("no cells recorded"),
          Option.when(bar.birthCell.exists(_.size != bar.dim + 1))("birth cell of the wrong dimension"),
          Option.when(bar.birthCell.exists(c => math.abs(diameter(c) - bar.birth) > 1e-12))(
            "birth cell valued differently from the bar"
          ),
          Option.when(bar.deathCell.exists(_.size != bar.dim + 2))("death cell of the wrong dimension"),
          Option.when(bar.deathCell.exists(c => math.abs(diameter(c) - bar.death) > 1e-12))(
            "death cell valued differently from the bar"
          ),
          Option.when(bar.deathCell.isEmpty != bar.death.isPosInfinity)("an essential bar iff there is no death cell")
        ).flatten
      yield s"$engine/$kind $bar: $problem"
      failures.take(3) must beEmpty
    }

    "pair every cell at most once" in {
      val d = diagram(Engine.Cohomology, Representatives.Cycles)
      val cells = d.bars.flatMap(b => b.birthCell.toList ++ b.deathCell.toList)
      cells.distinct.size must beEqualTo(cells.size)
    }

    "be the pairing of the textbook reduction of the boundary matrix over F_2, under each engine's own order of tied cells" in {
      // Independent of the engines: columns reduced oldest first, pivots the youngest faces. Cells of equal value are
      // ordered by dimension, then by the stream's tie-break (ascending for the generic cohomology engine, which
      // numbers its cells that way), its reverse (the homology engines) or Ripser's: the combinatorial number system,
      // descending.
      val stream = VietorisRips(space, maxDimension = 2)
      val all =
        Iterator.from(0).takeWhile(stream.iterateDimension.isDefinedAt).flatMap(stream.iterateDimension(_)).toVector
      val value = stream.filtrationValue
      def textbook(tieBreak: Ordering[Simplex[Int]]): Set[(Int, Simplex[Int], Option[Simplex[Int]])] =
        val processing = all.sorted(using
          Ordering.by[Simplex[Int], Double](value).orElse(Ordering.by[Simplex[Int], Int](_.dim)).orElse(tieBreak)
        )
        val index = processing.zipWithIndex.toMap
        def faces(c: Simplex[Int]): Set[Int] =
          if c.dim == 0 then Set.empty else c.toList.map(v => index(Simplex.from(c.toList.filter(_ != v)))).toSet
        val owner = scala.collection.mutable.Map.empty[Int, Set[Int]]
        val paired = scala.collection.mutable.ListBuffer.empty[(Int, Int)]
        val positive = scala.collection.mutable.Set.empty[Int]
        for j <- processing.indices do
          var column = faces(processing(j))
          while column.nonEmpty && owner.contains(column.max) do
            column = column.union(owner(column.max)) -- column.intersect(owner(column.max))
          if column.nonEmpty then
            owner(column.max) = column
            paired += ((column.max, j))
          else positive += j
        val finite = paired.toList.map((b, d) => (processing(b), processing(d))).filter((b, d) => value(b) != value(d))
        val essential = (positive.toSet -- paired.map(_._1)).toList.map(processing(_))
        (finite.map((b, d) => (b.dim, b, Option(d))) ++ essential.map(b => (b.dim, b, Option.empty[Simplex[Int]])))
          .filter(_._1 <= 2)
          .toSet
      val generic = textbook(stream.filtrationOrdering)
      val homological = textbook(stream.filtrationOrdering.reverse)
      val ripser = textbook(SimplexIndexing.colexOrdering.reverse)
      val failures = for
        (engine, kind, expected) <- List(
          (Engine.Cohomology, Representatives.Cycles, generic),
          (Engine.Cohomology, Representatives.Cocycles, generic),
          (Engine.Ripser, Representatives.Cycles, ripser),
          (Engine.Ripser, Representatives.Cocycles, ripser),
          (Engine.Naive, Representatives.Cycles, homological),
          (Engine.Naive, Representatives.Cocycles, homological),
          (Engine.Chunks, Representatives.Cycles, homological),
          (Engine.Chunks, Representatives.Cocycles, homological)
        )
        found = pairing(diagram(engine, kind, p = 2))
        if found != expected
      yield s"$engine/$kind: ${(found -- expected).take(2)} against ${(expected -- found).take(2)}"
      (failures must beEmpty) and (generic.size must beGreaterThan(10))
    }

    "be the same pairing from Ripser and its parallel form, for either kind of representative" in {
      val reference = pairing(diagram(Engine.Ripser, Representatives.Cocycles))
      val disagreeing = for
        (engine, kind) <- List(
          (Engine.Ripser, Representatives.Cycles),
          (Engine.ParallelRipser, Representatives.Cocycles),
          (Engine.ParallelRipser, Representatives.Cycles)
        )
        if pairing(diagram(engine, kind)) != reference
      yield s"$engine/$kind"
      disagreeing must beEmpty
    }

    "be the same pairing over F_2 and F_3 as over F_17 for the loops of a circle, where the field cannot matter" in {
      val circle = Array.tabulate(12)(i => Array(math.cos(2 * math.Pi * i / 12), math.sin(2 * math.Pi * i / 12)))
      val essential =
        (p: Int) => Persistence(circle, maxDimension = 1, characteristic = p).dim(1).bars.map(_.birthCell.get.toList)
      (essential(2) must beEqualTo(essential(17))) and (essential(3) must beEqualTo(essential(17)))
    }

    "name a birth cell that lies in the bar's own representative" in {
      val failures = for
        engine <- engines
        kind <- kinds
        bar <- diagram(engine, kind).bars
        if !bar.representative.cells.contains(bar.birthCell.get)
      yield s"$engine/$kind $bar"
      failures.take(3) must beEmpty
    }

    "survive the filters: dim, longerThan and at keep the cells of the bars they keep" in {
      val d = diagram(Engine.Cohomology, Representatives.Cycles)
      val kept = d.dim(1).longerThan(0.05).at(0.8).bars
      (kept must not(beEmpty)) and (kept.forall(_.cells.isDefined) must beTrue)
    }

    "not change what a bar equals: bars compare by interval and representative, not by cells" in {
      val bar = diagram(Engine.Cohomology, Representatives.Cycles).bars.head
      val bare = new PersistenceBar(bar.dim, bar.lower, bar.upper, bar.annotation)
      (bar must beEqualTo(bare)) and (bar.hashCode must beEqualTo(bare.hashCode)) and (bare.cells must beNone)
    }
  }
