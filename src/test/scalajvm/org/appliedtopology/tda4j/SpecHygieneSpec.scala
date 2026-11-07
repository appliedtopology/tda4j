package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Guards the specs themselves against one vacuous-assertion trap (#99): in a specs2 `mutable.Specification`, a
  * statement `(a must X).and(b must Y)` that is not the last expression of its example checks only `a`; the by-name `b`
  * is never evaluated, because nothing evaluates the discarded result. Written as the last expression it is evaluated
  * and both halves count. A second assertion belongs in a statement of its own.
  *
  * The scan is lexical (strings and comments blanked, brackets counted, indentation compared): a `.and(` whose
  * statement is followed by another statement of the same block is reported. A chain followed by the header of the next
  * example (`"..." >> {`, `in`, `should`) is that example's body and is fine.
  */
class SpecHygieneSpec extends Specification:
  import SpecHygieneSpec.*

  "The detector reports a chain before another statement, with braces, in an indented block or over several lines" >> {
    val source =
      """class S extends org.specs2.mutable.Specification:
        |  "braces" >> {
        |    (1 must beEqualTo(1)).and(2 must beEqualTo(3))
        |    3 must beEqualTo(3)
        |  }
        |  "indent" >>
        |    AsResult {
        |      (1 must beEqualTo(1)).and(
        |        2 must beEqualTo(3)
        |      )
        |      4 must beEqualTo(4)
        |    }
        |  "three" in {
        |    val x = 3
        |    (x must beEqualTo(3))
        |      .and(x must beEqualTo(3))
        |      .and(x must beEqualTo(4))
        |    ok
        |  }
        |""".stripMargin
    nonFinalChains(source) must beEqualTo(Seq(3, 8, 16))
  }

  "The detector reports a chain wrapped in parentheses before another statement, on one line or over several" >> {
    val source =
      """class S extends org.specs2.mutable.Specification:
        |  "one line" in {
        |    ((1 must beEqualTo(1)).and(2 must beEqualTo(3)))
        |    ok
        |  }
        |  "several lines" in {
        |    ((1 must beEqualTo(1))
        |      .and(2 must beEqualTo(3)))
        |    ok
        |  }
        |  "wrapped twice, broken inside" in {
        |    (((1 must beEqualTo(1)).and(
        |      2 must beEqualTo(3)
        |    )))
        |    ok
        |  }
        |""".stripMargin
    nonFinalChains(source) must beEqualTo(Seq(3, 8, 12))
  }

  "The detector accepts a wrapped chain that is the last expression of its example or an argument of a call" >> {
    val source =
      """class S extends org.specs2.mutable.Specification:
        |  "last" in {
        |    ok
        |    ((1 must beEqualTo(1)).and(2 must beEqualTo(2)))
        |  }
        |  "argument" in {
        |    Seq(1).foreach(x => ((x must beEqualTo(x)).and(x must beEqualTo(x))))
        |    ok
        |  }
        |""".stripMargin
    nonFinalChains(source) must beEmpty
  }

  "The detector accepts a chain that is the last expression of its example, or the whole body of it" >> {
    val source =
      """class S extends org.specs2.mutable.Specification:
        |  "last" >> {
        |    1 must beEqualTo(1)
        |    (1 must beEqualTo(1)).and(2 must beEqualTo(2))
        |  }
        |  "whole body" >>
        |    (1 must beEqualTo(1)).and(2 must beEqualTo(2))
        |  "inside a lambda" in {
        |    Seq(1, 2).foreach(x => (x must beEqualTo(x)).and(x must beEqualTo(x)))
        |    ok
        |  }
        |  "text" in { "a.and(b)" must beEqualTo("a.and(b)") } // (a must X).and(b)
        |""".stripMargin
    nonFinalChains(source) must beEmpty
  }

  "No spec has a .and( chain before the last statement of its example" >> {
    val roots = Seq(Paths.get("src/test"))
    val found = for
      root <- roots
      file <- Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".scala")).toSeq
      line <- nonFinalChains(Files.readString(file))
    yield s"$file:$line"
    found must beEmpty
  }

object SpecHygieneSpec:
  // comments, strings and character literals, which `blank` replaces by spaces (newlines stay)
  private val literal =
    "(?s)//[^\\n]*|/\\*.*?\\*/|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])'".r

  private def blank(source: String): String =
    literal.replaceAllIn(source, m => java.util.regex.Matcher.quoteReplacement(m.matched.replaceAll("[^\\n]", " ")))

  private val token = "[()\\[\\]{}]|\\.and\\(".r
  private val continuation = Seq(".", "and ", "or ", "&&", "||")
  // the line after a chain that starts the next example: its title is blank by now
  private val exampleHeader = "^(in|>>|should|must|when)( |\\{|$)".r

  private def indentOf(line: String): Int = line.length - line.stripLeading().length

  /** The 1-based lines of the `.and(` chains whose statement is followed by another statement of the same block. */
  def nonFinalChains(source: String): Seq[Int] =
    val lines = blank(source).split("\n", -1).toVector
    // bracket depth at the start of each line, and the depth at each `.and(`
    var depth = 0
    val depthAt = Array.ofDim[Int](lines.length)
    val hits = Vector.newBuilder[(Int, Int, Int)] // line, depth and column of each `.and(`
    for (line, i) <- lines.zipWithIndex do
      depthAt(i) = depth
      for m <- token.findAllMatchIn(line) do
        if m.matched == ".and(" then
          hits += ((i, depth, m.start))
          depth += 1
        else if "([{".contains(m.matched) then depth += 1
        else depth -= 1
    def blankLine(i: Int) = lines(i).isBlank
    def continues(i: Int) = continuation.exists(lines(i).stripLeading().startsWith)
    val seen = scala.collection.mutable.Set.empty[(Int, Int)]
    val reported = Vector.newBuilder[Int]
    // How many parentheses that open at the very start of the statement are still open at the `.and(` at (`at`, `col`):
    // `((a must X).and(b))` wraps the whole statement in one, which groups the expression and is no block of its own.
    def wrappers(start: Int, at: Int, col: Int): Int =
      val first = lines(start).indexWhere(!_.isWhitespace)
      var leading = first
      while leading >= 0 && leading < lines(start).length && lines(start)(leading) == '(' do leading += 1
      val open =
        scala.collection.mutable.ArrayBuffer.empty[Boolean] // whether the bracket opened at the statement start
      for l <- start to at do
        for m <- token.findAllMatchIn(lines(l)) if l < at || m.start < col do
          if "([{".contains(m.matched) then open += (l == start && first >= 0 && m.start >= first && m.start < leading)
          else if m.matched == ".and(" then open += false
          else if open.nonEmpty then open.remove(open.length - 1)
      open.takeWhile(identity).length
    for (at, depthAtHit, col) <- hits.result() do
      var start = at
      while start > 0 && (depthAt(start) > depthAtHit || continues(start) || blankLine(start)) do start -= 1
      val here = depthAtHit - wrappers(start, at, col)
      var next = at + 1
      while next < lines.length && (blankLine(next) || depthAt(next) > here || continues(next)) do next += 1
      if seen.add((start, next)) && next < lines.length then
        val following = lines(next).strip
        val sameBlock = depthAt(next) >= here && indentOf(lines(next)) >= indentOf(lines(start))
        val closes = following.nonEmpty && ")]}".contains(following.head)
        if sameBlock && !closes && exampleHeader.findFirstIn(following).isEmpty then reported += (at + 1)
    reported.result()
