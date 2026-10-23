package org.appliedtopology.tda4j

/** What the specs that run on both platforms do differently on each; this is the Scala.js copy (the other is in
  * `src/test/scalajvm`).
  */
object TestPlatform:
  /** How many cases a property that hunts rare inputs runs: a tenth of `n` on Scala.js. The code under test is the same
    * on both platforms and the JVM run searches with all `n`; what the Scala.js run has to catch is a difference of the
    * platform (number formatting, emulated `Long`, the last digits of `Math.pow`), which any input shows.
    */
  def propertyCases(n: Int): Int = math.max(1, n / 10)

  /** A value that differs by platform: how many seeds or configurations of a loop to run, or which cases of a table. */
  def perPlatform[A](jvm: => A, js: => A): A = js
