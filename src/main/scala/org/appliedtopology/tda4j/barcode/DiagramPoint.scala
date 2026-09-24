package org.appliedtopology.tda4j
package barcode

/** A diagram point extracted from a [[PersistenceBar]]'s `(lower, upper)` endpoints as plain `Double`s, shared by
  * [[BarcodeDistance]] and [[Vectorization]] so both apply the same finite-birth requirement and essential-bar
  * test rather than two independently-drifting copies.
  */
private[barcode] case class DiagramPoint(birth: Double, death: Double):
  def persistence: Double = death - birth

private[barcode] object DiagramPoint:
  def endpointValue(e: BarcodeEndpoint[Double]): Double = e match
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity
    case OpenEndpoint(v)    => v
    case ClosedEndpoint(v)  => v

  /** Requires a finite birth value: every real engine in this codebase only ever produces those, and the
    * alternative (silently propagating a `-Infinity` birth, e.g. from a hand-built `BarcodeContext.infcl` bar
    * meant for [[Barcode]]'s kernel/cokernel algebra, not for a "real" diagram) is a `NaN` landmine in the
    * distance/vectorization arithmetic downstream rather than a meaningful answer.
    */
  def of[A](bar: PersistenceBar[Double, A]): DiagramPoint =
    val b = endpointValue(bar.lower)
    val d = endpointValue(bar.upper)
    require(b.isFinite, s"assumes finite birth values, got $b (bar: $bar)")
    DiagramPoint(b, d)

  def isEssential(p: DiagramPoint): Boolean = p.death.isPosInfinity
