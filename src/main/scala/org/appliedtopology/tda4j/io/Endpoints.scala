package org.appliedtopology.tda4j

/** Shared textual infinite-endpoint parsing/formatting (`inf`/`-inf`/`infinity`, any case), used by every text-based
  * persistence-diagram format in this package (`CSV`, `Gudhi`). `Dipha`'s binary persistence-diagram format uses its
  * own numeric essential-class convention instead (a negative dimension field) and doesn't need this.
  */
private[tda4j] object Endpoints:
  private def parseValue(s: String): Either[BarcodeEndpoint[Double], Double] =
    val t = s.trim.toLowerCase
    if t == "inf" || t == "+inf" || t == "infinity" || t == "+infinity" then Left(PositiveInfinity[Double]())
    else if t == "-inf" || t == "-infinity" then Left(NegativeInfinity[Double]())
    else Right(s.trim.toDouble)

  /** The bar with these birth and death tokens, built as `PersistenceBar.apply` builds it (`[birth, death)`, or
    * essential for an infinite death), so a written bar reads back equal.
    */
  def toBar(dim: Int, birthToken: String, deathToken: String): PersistenceBar[Double, Nothing] =
    (parseValue(birthToken), parseValue(deathToken)) match
      case (Right(b), Right(d))                 => PersistenceBar[Double](dim, b, d)
      case (Right(b), Left(PositiveInfinity())) => PersistenceBar[Double](dim, b)
      case (Right(b), Left(deathEndpoint))      =>
        new PersistenceBar[Double, Nothing](dim, ClosedEndpoint(b), deathEndpoint)
      case (Left(birthEndpoint), Right(d)) =>
        new PersistenceBar[Double, Nothing](dim, birthEndpoint, ClosedEndpoint(d))
      case (Left(birthEndpoint), Left(deathEndpoint)) =>
        new PersistenceBar[Double, Nothing](dim, birthEndpoint, deathEndpoint)

  def formatEndpoint(e: BarcodeEndpoint[Double]): String = e match
    case PositiveInfinity() => "inf"
    case NegativeInfinity() => "-inf"
    case ClosedEndpoint(v)  => v.toString
    case OpenEndpoint(v)    => v.toString
