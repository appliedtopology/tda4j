package org.appliedtopology.tda4j

import org.specs2.execute.{Failure, Result, Success}
import org.specs2.matcher.Matcher

import java.util.regex.Pattern
import scala.reflect.ClassTag

/** `throwWith[E](pattern)`: the expression throws an `E` whose message contains a match of the regular expression
  * `pattern`. specs2's own `throwWith[E](message)` does the same on the JVM and reports a mismatch on Scala.js for
  * messages that do match, so the specs that run on both platforms use this one.
  */
object SpecThrows:
  def throwWith[E <: Throwable: ClassTag](pattern: String): Matcher[Any] =
    org.specs2.matcher.ExceptionMatchers.throwA[E].like { case e =>
      val message = Option(e.getMessage).getOrElse("")
      if Pattern.compile(pattern).matcher(message).find() then Success(): Result
      else Failure(s"the message '$message' of the ${e.getClass.getSimpleName} does not match /$pattern/"): Result
    }
