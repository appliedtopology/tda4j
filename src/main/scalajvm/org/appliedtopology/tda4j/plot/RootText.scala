package org.appliedtopology.tda4j.plot

import java.util.Locale

/** The text operations of the plots that must not depend on the reader's locale: lower-casing a keyword, formatting a
  * number with a `%.2f` pattern, and the decimal separator and minus sign of a named locale. The JVM's own, with
  * `Locale.ROOT`; the Scala.js build has the same object (`src/main/scalajs`), because `java.util.Locale` does not
  * exist there.
  */
// Restricted: the platform half of the plot code, not an API.
private[plot] object RootText:
  extension (s: String)
    /** `s` in lower case, whatever the default locale (`Locale.ROOT`). */
    def lowerRoot: String = s.toLowerCase(Locale.ROOT)

  /** `String.format` with `Locale.ROOT`: a point for a decimal separator, whatever the default locale. */
  def format(pattern: String, args: AnyRef*): String = String.format(Locale.ROOT, pattern, args*)

  /** The decimal separator and the minus sign of the locale `tag` (`de`, `fr`, `sv-SE`), for the digits 0 to 9 a plot
    * writes (so `ar` has a point, not the Arabic decimal separator of its own digits); the root locale's for a tag that
    * names none.
    */
  def decimalAndMinus(tag: String): (String, String) =
    val latin =
      Locale.Builder().setLocale(Locale.forLanguageTag(tag.trim)).setUnicodeLocaleKeyword("nu", "latn").build()
    val symbols = java.text.DecimalFormatSymbols.getInstance(latin)
    (symbols.getDecimalSeparator.toString, symbols.getMinusSign.toString)
