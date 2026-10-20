package org.appliedtopology.tda4j.cli

/** How the `tda4j` command ends with a failure code: the JVM exits at once. The Scala.js build has the same object
  * (`src/main/scalajs`), where there is no `System.exit`.
  */
// Restricted: the platform half of the command line, not an API.
private[cli] object ProcessExit:
  def withCode(code: Int): Unit = sys.exit(code)
