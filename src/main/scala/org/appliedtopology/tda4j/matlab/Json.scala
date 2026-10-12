package org.appliedtopology.tda4j.matlab

import java.io.Writer

// The JSON of a saved result (ResultFile): a writer and a pull reader driven by the reader's caller, which knows the
// schema -- numeric arrays go straight into Array[Double]/Array[Int], with no tree of boxed values in between (a saved
// image holds one number per pixel). No JSON library: the build has none, and we write the files ourselves.
// `private[matlab]` (reason 4): the facade's contract is `PersistenceResult.save`/`TDA4j.loadResult`; the format
// itself is documented for other languages to read with their own JSON parser.

/** Writes JSON text. A non-finite double is written as the string `"Infinity"`, `"-Infinity"` or `"NaN"`, which keeps
  * the file standard JSON; [[JsonReader.readDouble]] reads both forms.
  */
private[matlab] final class JsonWriter(out: Writer):
  def raw(s: String): Unit = out.write(s)

  def string(s: String): Unit =
    out.write('"')
    s.foreach {
      case '"'          => out.write("\\\"")
      case '\\'         => out.write("\\\\")
      case '\n'         => out.write("\\n")
      case '\r'         => out.write("\\r")
      case '\t'         => out.write("\\t")
      case c if c < ' ' => out.write(f"\\u${c.toInt}%04x")
      case c            => out.write(c)
    }
    out.write('"')

  def double(d: Double): Unit =
    if d.isNaN then string("NaN")
    else if d.isPosInfinity then string("Infinity")
    else if d.isNegInfinity then string("-Infinity")
    else out.write(java.lang.Double.toString(d)) // the shortest decimal that reads back as exactly `d`

  def int(i: Int): Unit = out.write(Integer.toString(i))

  def doubles(ds: Array[Double]): Unit = sequence(ds.length)(i => double(ds(i)))
  def ints(is: Array[Int]): Unit = sequence(is.length)(i => int(is(i)))
  def strings(ss: Array[String]): Unit = sequence(ss.length)(i => string(ss(i)))

  /** An array of `n` items, item `i` written by `item(i)`. */
  def sequence(n: Int)(item: Int => Unit): Unit =
    out.write('[')
    var i = 0
    while i < n do
      if i > 0 then out.write(',')
      item(i)
      i += 1
    out.write(']')

/** Reads JSON text by pulling values in the order the caller expects them. Every error names `source` and the character
  * position: a hand-edited or truncated file says where it went wrong.
  */
private[matlab] final class JsonReader(text: String, source: String):
  private var pos = if text.startsWith("\uFEFF") then 1 else 0 // a byte-order mark, as some Windows editors write

  def fail(message: String): Nothing =
    throw new IllegalArgumentException(s"$source: not a valid tda4j result file at character $pos: $message")

  private def skipWhitespace(): Unit =
    while pos < text.length && Character.isWhitespace(text.charAt(pos)) do pos += 1

  private def peek: Char =
    skipWhitespace()
    if pos >= text.length then fail("the file ends too early") else text.charAt(pos)

  private def expect(c: Char): Unit =
    if peek != c then fail(s"expected '$c', found '${text.charAt(pos)}'")
    pos += 1

  /** Nothing but whitespace is left. */
  def end(): Unit =
    skipWhitespace()
    if pos < text.length then fail("unexpected text after the end of the result")

  /** An object: `field(key)` is called with the reader at each key's value, and must read that value. */
  def obj(field: String => Unit): Unit =
    expect('{')
    if peek == '}' then pos += 1
    else
      var more = true
      while more do
        val key = string()
        expect(':')
        field(key)
        peek match
          case ',' => pos += 1
          case '}' => pos += 1; more = false
          case c   => fail(s"expected ',' or '}' in an object, found '$c'")

  /** An array: `item()` is called with the reader at each item, and must read it. */
  def arr(item: () => Unit): Unit =
    expect('[')
    if peek == ']' then pos += 1
    else
      var more = true
      while more do
        item()
        peek match
          case ',' => pos += 1
          case ']' => pos += 1; more = false
          case c   => fail(s"expected ',' or ']' in an array, found '$c'")

  def isNull: Boolean =
    if text.startsWith("null", { skipWhitespace(); pos }) then
      pos += 4
      true
    else false

  def string(): String =
    expect('"')
    val sb = new StringBuilder
    var done = false
    while !done do
      if pos >= text.length then fail("a string is not closed")
      text.charAt(pos) match
        case '"'  => pos += 1; done = true
        case '\\' =>
          if pos + 1 >= text.length then fail("a string is not closed")
          text.charAt(pos + 1) match
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case '/'  => sb += '/'
            case 'b'  => sb += '\b'
            case 'f'  => sb += '\f'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  =>
              val hex = text.slice(pos + 2, pos + 6)
              if hex.length < 4 || !hex.forall(Character.digit(_, 16) >= 0) then fail(s"bad escape '\\u$hex'")
              sb += Integer.parseInt(hex, 16).toChar
              pos += 4
            case c => fail(s"bad escape '\\$c'")
          pos += 2
        case c => sb += c; pos += 1
    sb.result()

  private def numberToken(): String =
    skipWhitespace()
    val start = pos
    while pos < text.length && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0 do pos += 1
    if pos == start then fail(s"expected a number, found '${peek}'")
    text.substring(start, pos)

  /** A number, or `Infinity`, `-Infinity`, `NaN` as strings (as written here) or bare (as Python writes them). */
  def readDouble(): Double =
    val bare = Seq("Infinity" -> Double.PositiveInfinity, "-Infinity" -> Double.NegativeInfinity, "NaN" -> Double.NaN)
    if peek == '"' then
      string() match
        case "Infinity"  => Double.PositiveInfinity
        case "-Infinity" => Double.NegativeInfinity
        case "NaN"       => Double.NaN
        case other       => fail(s"expected a number, found the string \"$other\"")
    else
      bare.find((word, _) => text.startsWith(word, pos)) match
        case Some((word, value)) =>
          pos += word.length
          value
        case None =>
          val token = numberToken()
          token.toDoubleOption.getOrElse(fail(s"'$token' is not a number"))

  def readInt(): Int =
    val token = numberToken()
    token.toIntOption.getOrElse {
      val d = token.toDoubleOption.getOrElse(fail(s"'$token' is not a number"))
      if d.isWhole && d.abs <= Int.MaxValue then d.toInt else fail(s"expected an integer, found '$token'")
    }

  def readBoolean(): Boolean =
    skipWhitespace()
    if text.startsWith("true", pos) then
      pos += 4; true
    else if text.startsWith("false", pos) then
      pos += 5; false
    else fail(s"expected true or false, found '${peek}'")

  def doubles(): Array[Double] =
    val b = Array.newBuilder[Double]
    arr(() => b += readDouble())
    b.result()

  def ints(): Array[Int] =
    val b = Array.newBuilder[Int]
    arr(() => b += readInt())
    b.result()

  def strings(): Array[String] =
    val b = Array.newBuilder[String]
    arr(() => b += string())
    b.result()

  /** Any value, read and dropped: a key this version does not know. */
  def skip(): Unit = peek match
    case '{'                                  => obj(_ => skip())
    case '['                                  => arr(() => skip())
    case '"'                                  => string()
    case 't' | 'f'                            => readBoolean()
    case 'n' if isNull                        => ()
    case c if "-0123456789IN".indexOf(c) >= 0 => readDouble()
    case c                                    => fail(s"unexpected '$c'")
