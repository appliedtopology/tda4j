package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** `CircularCoordinates(diagram, input, ...)` and `CircularCoordinates.toroidal(diagram, ...)` (issue #236): the
  * coordinates of a loop of ANY persistence diagram, not only of the Vietoris-Rips complex of a metric space.
  *
  * The oracle is the geometry: on a noisy circle a coordinate of the one big loop must follow the true angle (up to a
  * global phase and an orientation, which a cohomology class cannot fix), which fails for a cocycle of the wrong
  * winding number, of another class, or of another complex. Every engine that gives cocycles, and both kinds of
  * representative, must pass it, since the cocycle is taken off the diagram in one case and recovered in the other.
  */
class CircularCoordinatesFromDiagramSpec extends Specification:
  private val prime = 47

  private val n = 23
  private val noisyCircle: Array[Array[Double]] =
    val rng = new scala.util.Random(2026)
    Array.tabulate(n) { i =>
      val angle = 2 * math.Pi * i / n
      val radius = 1.0 + 0.05 * (rng.nextDouble() - 0.5)
      Array(radius * math.cos(angle), radius * math.sin(angle))
    }

  private def trueAngle(points: Array[Array[Double]])(i: Int): Double =
    val a = math.atan2(points(i)(1), points(i)(0)) / (2 * math.Pi)
    a - math.floor(a)

  /** Mean resultant length of `xs` mod 1: `1` when they are all equal on the circle, near `0` when they spread. */
  private def resultantLength(xs: Iterable[Double]): Double =
    val cos = xs.map(x => math.cos(2 * math.Pi * x)).sum
    val sin = xs.map(x => math.sin(2 * math.Pi * x)).sum
    math.sqrt(cos * cos + sin * sin) / xs.size

  /** How well `theta` follows `reference` on `indices`, in the better of the two orientations. */
  private def agreement(theta: Map[Int, Double], reference: Int => Double, indices: Seq[Int]): Double =
    val same = indices.map(i => theta(i) - reference(i))
    val reversed = indices.map(i => theta(i) + reference(i))
    math.max(resultantLength(same), resultantLength(reversed))

  private def followsCircle(theta: Map[Int, Double]): Boolean =
    theta.size == n && agreement(theta, trueAngle(noisyCircle), 0 until n) > 0.99

  private val engines = Seq(
    Persistence.Engine.Ripser,
    Persistence.Engine.ParallelRipser,
    Persistence.Engine.Cohomology,
    Persistence.Engine.Chunks,
    Persistence.Engine.Naive
  )
  private val kinds = Seq(Representatives.Cycles, Representatives.Cocycles)

  "circular coordinates of a Vietoris-Rips diagram" should {
    for engine <- engines; kind <- kinds do
      s"follow the true angle for engine $engine with $kind" >> {
        val diagram = Persistence(
          noisyCircle,
          maxDimension = 1,
          characteristic = prime,
          engine = engine,
          representatives = kind
        )
        diagram.representatives must beSome(kind)
        val coordinate = CircularCoordinates(diagram, noisyCircle)
        coordinate.prime must beEqualTo(prime)
        followsCircle(coordinate.theta) must beTrue
        coordinate.r must beBetween(coordinate.birth, coordinate.death).excludingEnd

        // The metric-space entry point, at the same scale, is the same coordinate up to phase and orientation.
        val direct = CircularCoordinates.compute(EuclideanMetricSpace(noisyCircle), coordinate.r, 0, prime)
        agreement(coordinate.theta, direct.theta, 0 until n) must beGreaterThan(0.99)
      }

    "list the diagram's H^1 bars like the metric-space entry point" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 1, characteristic = prime)
      val fromDiagram = CircularCoordinates.h1Bars(diagram)
      val fromPoints = CircularCoordinates.h1Bars(EuclideanMetricSpace(noisyCircle))
      fromDiagram.head._1 must beCloseTo(fromPoints.head._1, 1e-9)
      fromDiagram.head._2 must beCloseTo(fromPoints.head._2, 1e-9)
    }

    "take the bar and the scale as chosen" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 1, characteristic = prime)
      val (birth, death) = CircularCoordinates.h1Bars(diagram).head
      val r = birth + 0.25 * (death - birth)
      val coordinate = CircularCoordinates(diagram, noisyCircle, bar = 0, r = r)
      coordinate.r must beEqualTo(r)
      (coordinate.birth, coordinate.death) must beEqualTo((birth, death))
      followsCircle(coordinate.theta) must beTrue
    }

    "work on a diagram cut down with dim(1), whose bars keep their kind" >> {
      val diagram =
        Persistence(noisyCircle, maxDimension = 1, characteristic = prime, representatives = Representatives.Cocycles)
      val loops = diagram.dim(1)
      loops.representatives must beSome(Representatives.Cocycles)
      followsCircle(CircularCoordinates(loops, noisyCircle).theta) must beTrue
    }

    "agree for every odd characteristic that can lift the cocycle" >> {
      Seq(5, 17, 47, 101).map { p =>
        val diagram =
          Persistence(noisyCircle, maxDimension = 1, characteristic = p, representatives = Representatives.Cocycles)
        followsCircle(CircularCoordinates(diagram, noisyCircle).theta)
      } must contain(beTrue).forall
    }
  }

  "circular coordinates of another complex" should {
    for kind <- kinds do
      s"follow the true angle on a Cech diagram of $kind" >> {
        val diagram = Persistence(
          noisyCircle,
          complex = Cech,
          maxDimension = 1,
          characteristic = prime,
          representatives = kind
        )
        followsCircle(CircularCoordinates(diagram, noisyCircle, complex = Cech).theta) must beTrue
      }

    for kind <- kinds do
      s"follow the true angle on a diagram of a stream you built ($kind)" >> {
        val stream = VietorisRips(EuclideanMetricSpace(noisyCircle), maxDimension = 1)
        val diagram = Persistence(stream, characteristic = prime, representatives = kind)
        followsCircle(CircularCoordinates(diagram, stream).theta) must beTrue
      }

    "follow the true angle on a diagram of a metric space" >> {
      val metricSpace = EuclideanMetricSpace(noisyCircle)
      val diagram =
        Persistence(metricSpace, maxDimension = 1, characteristic = prime, representatives = Representatives.Cocycles)
      followsCircle(CircularCoordinates(diagram, metricSpace).theta) must beTrue
    }
  }

  "circular coordinates from the wrong input" should {
    // The same circle with its points renumbered: the same geometry, a different complex on the vertex numbers.
    val renumbered = Array.tabulate(n)(i => noisyCircle((7 * i) % n))
    // Another circle, whose classes are born at other scales.
    val larger = Array.tabulate(31)(i => Array(2 * math.cos(2 * math.Pi * i / 31), 2 * math.sin(2 * math.Pi * i / 31)))

    "say the cocycle does not belong to the complex, for a diagram of cocycles" >> {
      val diagram =
        Persistence(noisyCircle, maxDimension = 1, characteristic = prime, representatives = Representatives.Cocycles)
      CircularCoordinates(diagram, renumbered) must throwAn[IllegalArgumentException](message = ".*not a cocycle.*")
    }

    "say that no class is born there, for a diagram of cycles" >> {
      val diagram =
        Persistence(noisyCircle, maxDimension = 1, characteristic = prime, representatives = Representatives.Cycles)
      CircularCoordinates(diagram, larger) must throwAn[IllegalArgumentException](message = ".*born at.*")
    }
  }

  "circular coordinates of a diagram that cannot give them" should {
    "refuse the characteristic 2, saying what to do" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 1, characteristic = 2)
      CircularCoordinates(diagram, noisyCircle) must throwAn[IllegalArgumentException](message = ".*odd prime.*")
    }

    "refuse real coefficients, saying what to do" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 1, characteristic = 0)
      CircularCoordinates(diagram, noisyCircle) must throwAn[IllegalArgumentException](message = ".*Z/p.*")
    }

    "refuse a diagram without degree 1" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 0, characteristic = prime)
      CircularCoordinates(diagram, noisyCircle) must throwAn[IllegalArgumentException](message = ".*maxDimension = 1.*")
    }

    "refuse a bar that is not there and a scale outside the bar" >> {
      val diagram = Persistence(noisyCircle, maxDimension = 1, characteristic = prime)
      val (birth, death) = CircularCoordinates.h1Bars(diagram).head
      (CircularCoordinates(diagram, noisyCircle, bar = 99) must throwAn[IllegalArgumentException](message =
        ".*H\\^1 bar.*"
      ))
        .and(CircularCoordinates(diagram, noisyCircle, r = death + 1.0) must throwAn[IllegalArgumentException])
        .and(CircularCoordinates(diagram, noisyCircle, r = birth / 2) must throwAn[IllegalArgumentException])
    }
  }

  "toroidal coordinates of a diagram" should {
    // Two circles of unequal radii that touch at one point: two independent loops, so the Gram matrix is diagonal and
    // the right answer is known.
    val m = 14
    val circleA = Array.tabulate(m) { i =>
      val a = 2 * math.Pi * i / m
      Array(math.cos(a), math.sin(a))
    }
    val circleB = Array.tabulate(m) { i =>
      val a = math.Pi + 2 * math.Pi * i / m
      Array(1.55 + 0.6 * math.cos(a), 0.6 * math.sin(a))
    }
    val wedge = circleA ++ circleB
    for kind <- kinds do
      s"agree with the metric-space entry point and follow both circles ($kind)" >> {
        val diagram = Persistence(wedge, maxDimension = 1, characteristic = prime, representatives = kind)
        val result = CircularCoordinates.toroidal(diagram, wedge, bars = Seq(0, 1))
        val direct = CircularCoordinates.computeToroidal(
          EuclideanMetricSpace(wedge),
          result.r,
          Seq(0, 1),
          prime
        )
        result.theta.length must beEqualTo(2)
        // Each coordinate is one of the metric-space entry point's, up to a phase and an orientation.
        result.theta.map { theta =>
          direct.theta.map(other => agreement(theta, other, 0 until 2 * m)).max
        } must contain(beGreaterThan(0.99)).forall
        // The lattice reduction is the same computation: the same Gram matrix up to the order of the two loops.
        result.reducedGram.flatten.sorted.zip(direct.reducedGram.flatten.sorted).map((a, b) => math.abs(a - b)).max must
          beLessThan(1e-6)
      }

    "keep the chosen classes unchanged without the reduction" >> {
      val diagram =
        Persistence(wedge, maxDimension = 1, characteristic = prime, representatives = Representatives.Cocycles)
      val result = CircularCoordinates.toroidal(diagram, wedge, bars = Seq(0, 1), reduce = false)
      result.basisChange.map(_.toSeq).toSeq must beEqualTo(Seq(Seq(1, 0), Seq(0, 1)))
    }
  }

  "toroidal coordinates of a diagram of the torus" should {
    // A bar's class is only defined modulo the classes of the shorter bars nested inside it, and engines pick different
    // representatives. At a scale where just the two long loops are alive they span all of H^1, whichever
    // representatives they are, so the lattice and its harmonic norm are intrinsic: the reduced Gram matrix is what
    // both entry points must agree on.
    val random = new scala.util.Random(1)
    val torus = Array.fill(120) {
      val (a, b) = (random.nextDouble() * 2 * math.Pi, random.nextDouble() * 2 * math.Pi)
      Array(math.cos(a), math.sin(a), math.cos(b), math.sin(b))
    }

    "find the same reduced Gram matrix as the metric-space entry point, from cycles and from cocycles" >> {
      kinds.map { kind =>
        val diagram = Persistence(
          torus,
          maxDimension = 1,
          maxFiltrationValue = 1.8,
          characteristic = prime,
          representatives = kind
        )
        val loops = CircularCoordinates.h1Bars(diagram)
        val r = ((loops(1)._1 +: loops.drop(2).map(_._2)).max + loops(1)._2) / 2
        loops.count(b => b._1 <= r && r < b._2) must beEqualTo(2)
        val result = CircularCoordinates.toroidal(diagram, torus, bars = Seq(0, 1), r = r)
        val direct = CircularCoordinates.computeToroidal(
          EuclideanMetricSpace(torus),
          r,
          Seq(0, 1),
          prime,
          maxFiltrationValue = 1.8
        )
        val ours = result.reducedGram.flatten.map(math.abs).sorted
        val theirs = direct.reducedGram.flatten.map(math.abs).sorted
        ours.zip(theirs).map((a, b) => math.abs(a - b)).max
      } must contain(beLessThan(1e-6)).forall
    }
  }
