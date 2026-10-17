package org.appliedtopology.tda4j.cli

import scala.scalajs.js

/** How the `tda4j` command ends with a failure code: on Node.js it sets `process.exitCode`, and the process ends with
  * it once the output is written. The JVM build has the same object (`src/main/scalajvm`), which exits at once.
  */
// Restricted: the platform half of the command line, not an API.
private[cli] object ProcessExit:
  def withCode(code: Int): Unit = js.Dynamic.global.process.exitCode = code
