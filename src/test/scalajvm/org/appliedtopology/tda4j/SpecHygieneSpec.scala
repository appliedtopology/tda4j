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
    val hits = Vector.newBuilder[(Int, Int)]
    for (line, i) <- lines.zipWithIndex do
      depthAt(i) = depth
      for m <- token.findAllIn(line) do
        if m == ".and(" then
          hits += ((i, depth))
          depth += 1
        else if "([{".contains(m) then depth += 1
        else depth -= 1
    def blankLine(i: Int) = lines(i).isBlank
    def continues(i: Int) = continuation.exists(lines(i).stripLeading().startsWith)
    val seen = scala.collection.mutable.Set.empty[(Int, Int)]
    val reported = Vector.newBuilder[Int]
    for (at, here) <- hits.result() do
      var start = at
      while start > 0 && (depthAt(start) > here || continues(start) || blankLine(start)) do start -= 1
      var next = at + 1
      while next < lines.length && (blankLine(next) || depthAt(next) > here || continues(next)) do next += 1
      if seen.add((start, next)) && next < lines.length then
        val following = lines(next).strip
        val sameBlock = depthAt(next) >= here && indentOf(lines(next)) >= indentOf(lines(start))
        val closes = following.nonEmpty && ")]}".contains(following.head)
        if sameBlock && !closes && exampleHeader.findFirstIn(following).isEmpty then reported += (at + 1)
    reported.result()
