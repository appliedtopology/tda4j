package org.appliedtopology.tda4j.plot

/** How wide a text is, estimated. The renderer writes SVG for whatever font the reader's browser or tool picks and has
  * no font metrics, so a width is the advance widths of the stock face of a generic family: Helvetica (the faces
  * `sans-serif`, `system-ui`, Arial and Liberation Sans stand for), Times (`serif`) or a fixed width (`monospace`),
  * letter by letter, so that a row of `W`s is wider than a row of `i`s. Exact for those faces and the faces with their
  * metrics (Arial, Liberation Sans, Liberation Serif); a font with other proportions is further off (Inter, which
  * `system-ui` is on some systems, is about 6% wider).
  *
  * Used to cut a long text at a `limit` ([[TextFace]]) and to size a legend.
  */
object TextWidth:
  /** The generic family a width is taken from. */
  enum Family:
    case Sans, Serif, Mono

  object Family:
    private val sans = Set(
      "sans-serif",
      "system-ui",
      "-apple-system",
      "blinkmacsystemfont",
      "ui-sans-serif",
      "helvetica",
      "helvetica neue",
      "arial",
      "liberation sans",
      "segoe ui",
      "roboto",
      "verdana",
      "tahoma",
      "calibri",
      "dejavu sans",
      "open sans"
    )
    private val serif = Set(
      "serif",
      "ui-serif",
      "times",
      "times new roman",
      "liberation serif",
      "georgia",
      "garamond",
      "palatino",
      "cambria",
      "dejavu serif"
    )
    private val mono = Set(
      "monospace",
      "ui-monospace",
      "courier",
      "courier new",
      "consolas",
      "menlo",
      "monaco",
      "liberation mono",
      "dejavu sans mono",
      "sf mono"
    )

    /** The family of the first face of a CSS font list that is one of the known ones, `Sans` when none is. */
    def of(font: String): Family =
      font
        .split(',')
        .iterator
        .map(_.trim.stripPrefix("'").stripPrefix("\"").stripSuffix("'").stripSuffix("\"").trim.toLowerCase)
        .collectFirst {
          case name if sans(name)  => Sans
          case name if serif(name) => Serif
          case name if mono(name)  => Mono
        }
        .getOrElse(Sans)

  // Advance widths in thousandths of an em for the characters from space (32) to tilde (126): Helvetica and Times-Roman.
  private val helvetica: Array[Int] = Array(
    // space ! " # $ % & ' ( ) * + , - . /
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
    // 0-9 : ; < = > ? @
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015,
    // A-Z
    667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944,
    667, 667, 611,
    // [ \ ] ^ _ `
    278, 278, 278, 469, 556, 333,
    // a-z
    556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722,
    500, 500, 500,
    // { | } ~
    334, 260, 334, 584
  )
  private val times: Array[Int] = Array(
    250, 333, 408, 500, 500, 833, 778, 180, 333, 333, 500, 564, 250, 333, 250, 278, 500, 500, 500, 500, 500, 500, 500,
    500, 500, 500, 278, 278, 564, 564, 564, 444, 921, 722, 667, 667, 722, 611, 556, 722, 722, 333, 389, 722, 611, 889,
    722, 722, 556, 722, 667, 556, 611, 722, 722, 944, 722, 722, 611, 333, 278, 333, 469, 500, 333, 444, 500, 444, 500,
    444, 333, 500, 500, 278, 278, 500, 278, 778, 500, 500, 500, 500, 333, 389, 278, 500, 500, 722, 500, 500, 444, 480,
    200, 480, 541
  )

  // The bold faces, Helvetica-Bold and Times-Bold: the widths of Liberation Sans Bold and Liberation Serif Bold, which
  // have those metrics, as Chromium measures them.
  private val helveticaBold: Array[Int] = Array(
    // space ! " # $ % & ' ( ) * + , - . /
    278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
    // 0-9 : ; < = > ? @
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975,
    // A-Z
    722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944,
    667, 667, 611,
    // [ \ ] ^ _ `
    333, 278, 333, 584, 556, 333,
    // a-z
    556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778,
    556, 556, 500,
    // { | } ~
    389, 280, 389, 584
  )
  private val timesBold: Array[Int] = Array(
    // space ! " # $ % & ' ( ) * + , - . /
    250, 333, 555, 500, 500, 1000, 833, 278, 333, 333, 500, 570, 250, 333, 250, 278,
    // 0-9 : ; < = > ? @
    500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 333, 333, 570, 570, 570, 500, 930,
    // A-Z
    722, 667, 722, 722, 667, 611, 778, 778, 389, 500, 778, 667, 944, 722, 778, 611, 778, 722, 556, 667, 722, 722, 1000,
    722, 722, 667,
    // [ \ ] ^ _ `
    333, 278, 333, 581, 500, 333,
    // a-z
    500, 556, 444, 556, 444, 333, 500, 556, 278, 333, 556, 278, 833, 556, 500, 556, 556, 444, 389, 333, 556, 500, 722,
    500, 500, 444,
    // { | } ~
    394, 220, 394, 520
  )

  // thousandths of an em of the characters outside the table: the ellipsis and the infinity sign a plot writes, the minus
  // sign, accented letters as the letter they are on (a little narrower in Times), wide scripts a full em, anything else
  // the width of an `n`
  private def other(c: Char, family: Family, bold: Boolean): Int =
    if c == '…' then 1000
    else if c == '∞' then 713
    else if c == '−' then if family != Family.Serif then 584 else if bold then 570 else 564
    else if c >= 'À' && c <= 'ɏ' then if family == Family.Serif then 444 else 556
    else if c >= '⺀' then 1000
    else if family == Family.Serif then 500
    else 556

  /** The width in pixels of `text` set at `size` pixels: a letter takes its width in the regular or the bold face of
    * Helvetica (`Sans`) or Times (`Serif`), or 0.6 em in a monospace face, bold or not.
    */
  def of(text: String, size: Double, family: Family = Family.Sans, bold: Boolean = false): Double =
    val thousandths = family match
      case Family.Mono => text.length * 600.0
      case _           =>
        val table = (family, bold) match
          case (Family.Serif, true)  => timesBold
          case (Family.Serif, false) => times
          case (_, true)             => helveticaBold
          case (_, false)            => helvetica
        text.iterator.map(c => if c >= ' ' && c <= '~' then table(c - ' ') else other(c, family, bold)).sum.toDouble
    thousandths * size / 1000.0
