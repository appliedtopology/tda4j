package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.barcode.{given, *}

/** Shared textual infinite-endpoint parsing/formatting (`inf`/`-inf`/`infinity`, any case), used by every text-based
  * persistence-diagram format in this package (`CSV`, `Gudhi`). `Dipha`'s binary persistence-diagram format uses its
  * own numeric essential-class convention instead (a negative dimension field) and doesn't need this.
  */
private[io] object Endpoints:
  private def parseValue(s: String): Either[BarcodeEndpoint[Double], Double] =
    val t = s.trim.toLowerCase
    if t == "inf" || t == "+inf" || t == "infinity" || t == "+infinity" then Left(PositiveInfinity[Double]())
    else if t == "-inf" || t == "-infinity" then Left(NegativeInfinity[Double]())
    else Right(s.trim.toDouble)

  /** Builds a bar from raw birth/death tokens using this codebase's own half-open `[birth, death)` convention when both
    * are finite -- matching `PersistenceBar.apply(dim, lower, upper)` exactly, rather than wrapping both in
    * `ClosedEndpoint` -- so that a bar built the ordinary way (via that same factory) round-trips through a text format
    * unchanged: format a bar, re-parse it, and get back an `==` bar, not one whose `upper` silently flipped from
    * `OpenEndpoint` to `ClosedEndpoint`. `death == +infinity` matches `PersistenceBar.apply(dim, lower)` (essential)
    * the same way. An infinite `birth` (rare, but some formats allow it) falls back to the raw `PersistenceBar`
    * constructor, since neither companion factory covers that case.
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
