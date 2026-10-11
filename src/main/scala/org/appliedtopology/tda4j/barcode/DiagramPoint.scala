package org.appliedtopology.tda4j

/** A diagram point extracted from a [[PersistenceBar]]'s `(lower, upper)` endpoints as plain `Double`s, shared by
  * [[BarcodeDistance]] and [[Vectorization]] so both apply the same finite-birth requirement and essential-bar test
  * rather than two independently-drifting copies.
  */
case class DiagramPoint(birth: Double, death: Double):
  def persistence: Double = death - birth

object DiagramPoint:
  def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v

  /** The point of `bar`, which must have a finite birth (a bar born at `-Infinity` has no place in a diagram). */
  def of[A](bar: PersistenceBar[Double, A]): DiagramPoint =
    val b = endpointValue(bar.lower)
    val d = endpointValue(bar.upper)
    require(b.isFinite, s"assumes finite birth values, got $b (bar: $bar)")
    DiagramPoint(b, d)

  def isEssential(p: DiagramPoint): Boolean = p.death.isPosInfinity
