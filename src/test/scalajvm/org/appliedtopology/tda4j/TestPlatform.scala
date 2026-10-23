package org.appliedtopology.tda4j

/** What the specs that run on both platforms do differently on each; this is the JVM's copy (the other is in
  * `src/test/scalajs`).
  */
object TestPlatform:
  /** How many cases a property that hunts rare inputs runs: all `n` on the JVM. */
  def propertyCases(n: Int): Int = n

  /** A value that differs by platform: how many seeds or configurations of a loop to run, or which cases of a table. */
  def perPlatform[A](jvm: => A, js: => A): A = jvm
