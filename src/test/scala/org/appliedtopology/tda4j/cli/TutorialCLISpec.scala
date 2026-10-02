package org.appliedtopology.tda4j
package cli

import org.specs2.mutable.Specification

/** The exact command line shown in `_docs/tutorials/all-ways-to-call.md` (task 2), run in-process. */
class TutorialCLISpec extends Specification:
  "the tutorial's command line" should {
    "report the circle's one loop, with representatives" in {
      val buffer = new java.io.ByteArrayOutputStream()
      val out = new java.io.PrintStream(buffer)
      val code = TDA4jCLI.run(Seq("-r", "--max-dimension", "1", "_docs/tutorials/examplepoints.csv"), out)
      out.flush()
      val text = buffer.toString
      code must beEqualTo(0)
      text.linesIterator.count(_.startsWith("1: ")) must beEqualTo(1)
      text must contain("rep:")
    }
  }
