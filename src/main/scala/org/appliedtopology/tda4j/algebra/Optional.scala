package org.appliedtopology.tda4j

// format: off
/** An optional parameter that a call site can give as a plain value: `maxFiltrationValue = 2.0` (or an `Int`), as well
  * as `Some(2.0)` / `None` / any `Option[Double]` -- so `Option` keeps its meaning (CLAUDE.md: options over sentinels)
  * without making every user write `Some(...)`. `into` lets the conversions below apply at exactly the parameters typed
  * `Optional[...]`, with no `implicitConversions` import at the call site (`.claude/WORKLOG-cursor-and-verb.md`).
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
