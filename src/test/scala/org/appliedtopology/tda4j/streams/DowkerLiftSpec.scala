package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.AsResult
import org.specs2.ScalaCheck
import org.scalacheck.*

/** `DowkerGeometry.lift` and `Dowker.lift`: the chain map behind Dowker duality, which moves a bar's representative to
  * the other side of the relation. What it must preserve, checked over F_17 so that a wrong sign shows: the image of a
  * representative is a cycle, lies in the other side's complex at the bar's birth, is not a boundary there at any value
  * before the bar's death, and (the cohomology engine's cycles die with their bars) is a boundary at its death. The
  * bars themselves are unchanged. `WORKLOG-dual-witness-complex.md`.
  */
class DowkerLiftSpec extends mutable.Specification with ScalaCheck:

  private val relations: Gen[Array[Array[Double]]] =
    matrixGen[Double](Gen.chooseNum(0.0, 4.0), Gen.chooseNum(3, 6), Gen.chooseNum(3, 5))

  /** The boundary of a chain given as terms, each face once with its coefficients summed, zeros dropped. */
  private def boundary[C: Field as field](terms: Seq[(Simplex[Int], C)]): Map[Simplex[Int], C] =
    val acc = scala.collection.mutable.Map.empty[Simplex[Int], C]
    for
      (sigma, c) <- terms if sigma.underlying.size > 1
      (v, i) <- sigma.underlying.iterator.zipWithIndex
    do
      val face = (sigma.underlying - v).asSimplex
      val term = if i % 2 == 0 then c else field.negate(c)
      acc(face) = acc.get(face).fold(term)(field.plus(_, term))
    acc.filterNot((_, c) => field.isEqual(c, field.zero)).toMap

  /** Whether the degree-`k` chain `cycle` is a boundary in the Dowker complex of `geometry` at `t`: in the span of the
    * boundaries of its `(k+1)`-simplices of value at most `t`.
    */
  private def isBoundaryAt[C: Field as field](
    geometry: DowkerGeometry,
    cycle: Seq[(Simplex[Int], C)],
    k: Int,
    t: Double
  ): Boolean =
    val cofaces = (0 until geometry.numLeft).combinations(k + 2).filter(s => geometry.filtrationValue(s) <= t).toSeq
    val boundaries = cofaces.map(s => boundary(Seq(Simplex(s*) -> field.one)))
    val basis = (boundaries.flatMap(_.keys) ++ cycle.map(_._1)).distinct.toIndexedSeq
    def row(m: Map[Simplex[Int], C]): Seq[C] = basis.map(m.getOrElse(_, field.zero))
    val rows = boundaries.map(row)
    LinearAlgebra.rank(rows :+ row(cycle.toMap)) == LinearAlgebra.rank(rows)

  /** Every bar of the Dowker diagram on one side, lifted to the other, against the four properties above. */
  private def liftIsFaithful(relation: Array[Array[Double]], dual: Boolean): Option[String] =
    val built = if dual then DowkerGeometry(relation).dual else DowkerGeometry(relation)
    val other = built.dual
    val diagram = Persistence(Dowker(relation, maxDimension = 2, dual = dual))
    val lifted = Dowker.lift(diagram, relation, dual = dual)
    import lifted.given
    if lifted.bars.map(_.toTriple) != diagram.bars.map(_.toTriple) then Some("the bars changed")
    else if !lifted.representativesDieWithTheirBars then Some("the cycles no longer die with their bars")
    else
      lifted.bars.iterator
        .flatMap { bar =>
          val terms = bar.representative.terms
          val k = bar.dim
          val wrongCells = terms.filterNot((tau, _) =>
            tau.underlying.size == k + 1 && tau.underlying.max < other.numLeft &&
              other.filtrationValue(tau.underlying.toIndexedSeq) <= bar.birth
          )
          val valuesBeforeDeath =
            (0 until other.numLeft).combinations(k + 2).map(other.filtrationValue(_)).filter(_ < bar.death)
          val lastBeforeDeath = (valuesBeforeDeath ++ Iterator(bar.birth)).max
          if terms.isEmpty then Some(s"$bar: the lifted representative is zero")
          else if boundary(terms).nonEmpty then Some(s"$bar: the lifted representative is not a cycle")
          else if wrongCells.nonEmpty then Some(s"$bar: ${wrongCells.map(_._1)} are not in the other complex at birth")
          else if isBoundaryAt(other, terms, k, lastBeforeDeath) then Some(s"$bar: a boundary at $lastBeforeDeath")
          else if !bar.death.isPosInfinity && !isBoundaryAt(other, terms, k, bar.death) then
            Some(s"$bar: not a boundary at its death")
          else None
        }
        .nextOption()

  "a lifted representative is a cycle of the other side's complex at the bar's birth that is a boundary exactly " +
    "from the bar's death on, and the bars are unchanged, over F_17, on random relations" >> AsResult {
      Prop.forAll(relations)(relation => liftIsFaithful(relation, dual = false) must beNone)
    }

  "the same from the columns back to the rows, for a diagram computed with dual = true" >> AsResult {
    Prop.forAll(relations)(relation => liftIsFaithful(relation, dual = true) must beNone)
  }

  // ---------------------------------------------------------------------------------------------------------
  // Hand-derived: the vertices of an equilateral triangle of side 1 as landmarks (points 0, 1, 2), its edge
  // midpoints (points 3, 4, 5, midpoints of 01, 12, 20) as the other witnesses. The Dowker complex on the
  // landmarks has the loop [1/2, sqrt(3)/2). A landmark is its own best witness and an edge's is its midpoint, so
  // the lifted loop is the hexagon 0, 3, 1, 4, 2, 5 through all six points, every edge with the same coefficient
  // up to sign.
  // ---------------------------------------------------------------------------------------------------------

  private val h = math.sqrt(3.0) / 2
  private val triangleWithMidpoints = EuclideanMetricSpace(
    Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, h), Array(0.5, 0.0), Array(0.75, h / 2), Array(0.25, h / 2))
  )

  "the loop of a triangle seen only from its corners and edge midpoints lifts to the hexagon through all six " +
    "points" >> {
      val geometry = WitnessGeometry(triangleWithMidpoints, IndexedSeq(0, 1, 2))
      val diagram = Persistence(Dowker(geometry.D, maxDimension = 1))
      val lifted = Dowker.lift(diagram, geometry.D)
      import lifted.given
      val loops = lifted.dim(1).bars
      val terms = loops.head.representative.terms
      val hexagon = Set(Seq(0, 3), Seq(1, 3), Seq(1, 4), Seq(2, 4), Seq(2, 5), Seq(0, 5)).map(Simplex(_*))
      val c = terms.head._2
      (loops.size must beEqualTo(1)) and
        (terms.map(_._1).toSet must beEqualTo(hexagon)) and
        (terms.forall((_, x) => x == c || x == lifted.coefficientField.negate(c)) must beTrue) and
        (geometry.D.indices.map(l => DowkerGeometry(geometry.D).bestWitness(Simplex(l))) must beEqualTo(0 to 2))
    }

  "Dowker.lift refuses a diagram of cocycles, and lift a chain on vertices that are not rows" >> {
    val relation = Array(Array(0.0, 1.0, 2.0), Array(1.0, 0.0, 1.0), Array(2.0, 1.0, 0.0), Array(1.0, 2.0, 0.5))
    val cocycles = Persistence(Dowker(relation, maxDimension = 1), representatives = Representatives.Cocycles)
    given Double is Field = Field.DoubleApproximated(1e-9)
    val offTheRows = Chain[Simplex[Int], Double](Simplex(0, 7) -> 1.0)
    (Dowker.lift(cocycles, relation) must throwAn[IllegalArgumentException]("cocycles")) and
      (DowkerGeometry(relation).lift(offTheRows) must throwAn[IllegalArgumentException]("dual = true"))
  }
