package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.AsResult
import org.specs2.ScalaCheck
import org.scalacheck.*
import org.appliedtopology.tda4j.SpecThrows.throwWith

/** `DowkerGeometry.lift` and `Dowker.lift`: the chain map behind Dowker duality, which moves a bar's representative to
  * the other side of the relation. What it must preserve, checked over F_17 so that a wrong sign shows: the image of a
  * representative is a cycle, lies in the other side's complex at the bar's birth, is not a boundary there at any value
  * before the bar's death, and (the cohomology engine's cycles die with their bars) is a boundary at its death. The
  * bars themselves are unchanged. A lifted cocycle must be a cocycle of the other side's complex below the bar's death,
  * a nonzero class at its birth and a coboundary before it; and circular coordinates read from it on the points must be
  * those computed on the points' own complex. `WORKLOG-dual-witness-complex.md`.
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

  // ---- cocycles --------------------------------------------------------------------------------------------------

  /** The value on `rho` of the coboundary of the cochain `values`. */
  private def coboundaryAt[C: Field as field](values: Map[Simplex[Int], C], rho: Seq[Int]): C =
    rho.indices.foldLeft(field.zero) { (acc, i) =>
      val c = values.getOrElse(Simplex(rho.patch(i, Nil, 1)*), field.zero)
      field.plus(acc, if i % 2 == 0 then c else field.negate(c))
    }

  /** Whether the `k`-cochain `values`, restricted to the Dowker complex of `geometry` at `t`, is a coboundary there: in
    * the span of the coboundaries of its `(k-1)`-simplices, restricted to its `k`-simplices.
    */
  private def isCoboundaryAt[C: Field as field](
    geometry: DowkerGeometry,
    values: Map[Simplex[Int], C],
    k: Int,
    t: Double
  ): Boolean =
    val cells = (0 until geometry.numLeft).combinations(k + 1).filter(geometry.filtrationValue(_) <= t).toIndexedSeq
    val restricted = cells.map(s => values.getOrElse(Simplex(s*), field.zero))
    if k == 0 then restricted.forall(field.isEqual(_, field.zero))
    else
      val faces = (0 until geometry.numLeft).combinations(k).filter(geometry.filtrationValue(_) <= t).toIndexedSeq
      val rows = faces.map(f => cells.map(s => coboundaryAt(Map(Simplex(f*) -> field.one), s)))
      LinearAlgebra.rank(rows :+ restricted) == LinearAlgebra.rank(rows)

  /** The cocycle of a bar, on the side of `geometry`: a cocycle of the complex below `before`, a nonzero class at the
    * bar's birth, a coboundary at every value before it.
    */
  private def cocycleProblem[C: Field as field](
    geometry: DowkerGeometry,
    values: Map[Simplex[Int], C],
    k: Int,
    birth: Double,
    before: Double
  ): Option[String] =
    val n = geometry.numLeft
    def value(s: Seq[Int]) = geometry.filtrationValue(s.toIndexedSeq)
    val wrongCells = values.keys.filterNot(s =>
      s.underlying.size == k + 1 && s.underlying.max < n && value(s.underlying.toSeq) < before
    )
    val notClosed = (0 until n)
      .combinations(k + 2)
      .filter(value(_) < before)
      .find(rho => !field.isEqual(coboundaryAt(values, rho), field.zero))
    val earlier = (0 to k + 1).iterator.flatMap(j => (0 until n).combinations(j + 1).map(value(_))).filter(_ < birth)
    if values.isEmpty then Some("the cocycle is zero")
    else if wrongCells.nonEmpty then Some(s"$wrongCells are not k-simplices below $before")
    else if notClosed.nonEmpty then Some(s"not a cocycle on ${notClosed.get}")
    else if isCoboundaryAt(geometry, values, k, birth) then Some(s"a coboundary at its birth $birth")
    else if earlier.hasNext && isCoboundaryAt(geometry, values, k, earlier.max) == false then
      Some(s"not a coboundary before its birth $birth")
    else None

  /** Every bar of a diagram of cocycles on one side, lifted to the other: the bars unchanged, and each cocycle (and, as
    * a control on what the engine gives, the cocycle it came from, below the bar's death) a cocycle of its bar.
    */
  private def cocycleLiftIsFaithful(relation: Array[Array[Double]], dual: Boolean): Option[String] =
    val built = if dual then DowkerGeometry(relation).dual else DowkerGeometry(relation)
    val diagram =
      Persistence(Dowker(relation, maxDimension = 2, dual = dual), representatives = Representatives.Cocycles)
    val lifted = Dowker.lift(diagram, relation, dual = dual)
    import lifted.given
    if lifted.bars.map(_.toTriple) != diagram.bars.map(_.toTriple) then Some("the bars changed")
    else
      lifted.bars.iterator
        .zip(diagram.bars.iterator)
        .flatMap { (bar, original) =>
          val before = if bar.death.isPosInfinity then Math.nextUp(lifted.lastFiltrationValue) else bar.death
          val k = bar.dim
          def cocycle(b: PersistenceBar[Double, Chain[Simplex[Int], lifted.Coefficient]]) = b.representative.terms.toMap
          // The engine's cocycle may carry cells from the bar's death on, outside the complex it is a cocycle of.
          val belowDeath = cocycle(original).filter((s, _) => built.filtrationValue(s.underlying.toIndexedSeq) < before)
          cocycleProblem(built, belowDeath, k, bar.birth, before)
            .map(p => s"$bar, before the lift: $p")
            .orElse(cocycleProblem(built.dual, cocycle(bar), k, bar.birth, before).map(p => s"$bar: $p"))
        }
        .nextOption()

  "a lifted cocycle is a cocycle of the other side's complex below the bar's death, a nonzero class at its birth and " +
    "a coboundary before it, and the bars are unchanged, over F_17, on random relations" >> AsResult {
      Prop.forAll(relations)(relation => cocycleLiftIsFaithful(relation, dual = false) must beNone)
    }

  "the same for cocycles from the columns back to the rows" >> AsResult {
    Prop.forAll(relations)(relation => cocycleLiftIsFaithful(relation, dual = true) must beNone)
  }

  // ---------------------------------------------------------------------------------------------------------
  // Circular coordinates on the points from a computation on the landmarks: 48 points on a circle, every sixth a
  // landmark. The landmark cocycle lifted to the points and smoothed there must give the coordinate computed from the
  // points' own complex (the harmonic representative of one class is unique: the same up to sign and rotation), and
  // that coordinate goes once around the circle.
  // ---------------------------------------------------------------------------------------------------------

  private val circle = EuclideanMetricSpace(
    Array.tabulate(48)(i => Array(math.cos(2 * math.Pi * i / 48), math.sin(2 * math.Pi * i / 48)))
  )

  "circular coordinates on every point from the landmarks' cocycle, lifted, are those of the points' own complex" >> {
    // Landmarks numbered out of the circle's order, so that lifted edges come with both orientations.
    val geometry = WitnessGeometry(circle, IndexedSeq(0, 18, 36, 6, 24, 42, 12, 30))
    val onPoints = Dowker(geometry.D, maxDimension = 1, dual = true)
    val landmarks = Persistence(Dowker(geometry.D, maxDimension = 1), representatives = Representatives.Cocycles)
    import landmarks.given
    val lifted = CircularCoordinates(Dowker.lift(landmarks, geometry.D), onPoints)
    val direct = CircularCoordinates(
      Persistence(onPoints, representatives = Representatives.Cocycles),
      Dowker(geometry.D, maxDimension = 1, dual = true),
      r = lifted.r
    )
    // The engine's cocycle of a circle is usually one cut edge, which no orientation error would show; the same class
    // plus the coboundary of a 0-cochain is nonzero on every edge, with both signs.
    val field = landmarks.coefficientField
    val loop = landmarks.dim(1).longest.get
    val alpha = loop.representative.terms.toMap
    def beta(l: Int) = if l % 2 == 0 then field.zero else field.one
    val landmarkGeometry = DowkerGeometry(geometry.D)
    val twistedCocycle = (0 until 8)
      .combinations(2)
      .filter(e => landmarkGeometry.filtrationValue(e) < loop.death)
      .map(e =>
        Simplex(e*) -> field.plus(alpha.getOrElse(Simplex(e*), field.zero), field.minus(beta(e(1)), beta(e(0))))
      )
      .filterNot((_, c) => field.isEqual(c, field.zero))
      .toSeq
    val twisted = PersistenceDiagram[Simplex[Int], landmarks.Coefficient](
      List(loop.copy(annotation = Some(Chain.from(twistedCocycle)))),
      1,
      landmarks.lastFiltrationValue,
      landmarks.scale,
      Some(Representatives.Cocycles)
    )
    val twistedLifted = CircularCoordinates(Dowker.lift(twisted, geometry.D), onPoints, r = lifted.r)
    def wrapped(x: Double): Double = x - math.rint(x)
    def sameUpToRotationOrReflection(a: Map[Int, Double], b: Map[Int, Double]): Boolean =
      Seq(1.0, -1.0).exists(sign =>
        (0 until 48).forall(i => math.abs(wrapped(a(i) - sign * b(i) - (a(0) - sign * b(0)))) < 1e-6)
      )
    val winding = (0 until 48).map(i => wrapped(lifted.theta((i + 1) % 48) - lifted.theta(i))).sum
    (lifted.theta.keySet must beEqualTo((0 until 48).toSet)) and
      (sameUpToRotationOrReflection(lifted.theta, direct.theta) must beTrue) and
      (sameUpToRotationOrReflection(twistedLifted.theta, direct.theta) must beTrue) and
      (twistedCocycle.size must beGreaterThan(alpha.size)) and
      (math.abs(math.abs(winding) - 1.0) must beLessThan(1e-6))
  }

  "lift and liftCocycle refuse a chain on vertices that are not rows" >> {
    val relation = Array(Array(0.0, 1.0, 2.0), Array(1.0, 0.0, 1.0), Array(2.0, 1.0, 0.0), Array(1.0, 2.0, 0.5))
    given Double is Field = Field.DoubleApproximated(1e-9)
    val offTheRows = Chain[Simplex[Int], Double](Simplex(0, 7) -> 1.0)
    (DowkerGeometry(relation).lift(offTheRows) must throwWith[IllegalArgumentException]("dual = true")) and
      (DowkerGeometry(relation).liftCocycle(offTheRows, 3.0) must throwWith[IllegalArgumentException]("dual = true"))
  }
