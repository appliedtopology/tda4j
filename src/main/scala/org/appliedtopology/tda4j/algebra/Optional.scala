package org.appliedtopology.tda4j

// format: off
/** An optional parameter that a call site can give as a plain value: `maxFiltrationValue = 2.0` (or `2`), as well as
  * `Some(2.0)`, `None` or any `Option[Double]`. The conversions apply only at parameters of type `Optional[...]`, with
  * no import at the call site.
  */
into final case class Optional[+A](toOption: Option[A]):
  def getOrElse[B >: A](default: => B): B = toOption.getOrElse(default)
  def isEmpty: Boolean = toOption.isEmpty
// format: on

object Optional:
  val empty: Optional[Nothing] = Optional(None)
  given fromValue: [A] => Conversion[A, Optional[A]] = a => Optional(Some(a))
  given fromOption: [A] => Conversion[Option[A], Optional[A]] = o => Optional(o)
  given fromInt: Conversion[Int, Optional[Double]] = i => Optional(Some(i.toDouble))
