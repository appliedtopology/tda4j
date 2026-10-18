package org.appliedtopology.tda4j.plot

import scala.scalajs.js

/** The text operations of the plots that must not depend on the reader's locale: lower-casing a keyword, formatting a
  * number with a `%.2f` pattern, and the decimal separator and minus sign of a named locale. On Scala.js a string is
  * lower-cased and formatted the same way everywhere, and a locale's symbols come from `Intl.NumberFormat`. The JVM
  * build has the same object (`src/main/scalajvm`).
  */
// Restricted: the platform half of the plot code, not an API.
private[plot] object RootText:
  extension (s: String)
    /** `s` in lower case, whatever the language of the reader. */
    def lowerRoot: String = s.toLowerCase()

  /** `String.format`, whose decimal separator on Scala.js is always a point. */
  def format(pattern: String, args: AnyRef*): String = String.format(pattern, args*)

  /** The decimal separator and the minus sign of the locale `tag` (`de`, `fr`, `sv-SE`), from `Intl.NumberFormat`; `.`
    * and `-` for a tag that names none.
    */
  def decimalAndMinus(tag: String): (String, String) =
    try
      val formatter = js.Dynamic.newInstance(js.Dynamic.global.Intl.NumberFormat)(tag.trim)
      val parts = formatter.formatToParts(-1.5).asInstanceOf[js.Array[js.Dynamic]]
      def part(kind: String, default: String): String =
        parts.find(_.selectDynamic("type").toString == kind).fold(default)(_.selectDynamic("value").toString)
      (part("decimal", "."), part("minusSign", "-"))
    catch case _: js.JavaScriptException => (".", "-")
