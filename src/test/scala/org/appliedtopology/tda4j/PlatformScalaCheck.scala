package org.appliedtopology.tda4j

import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

/** `ScalaCheck` whose default case count is the platform's: 100 on the JVM, a tenth of that on Scala.js, whose run is
  * there to catch what differs by platform (see `TestPlatform`), which any input shows. A spec that sets its own
  * `given Parameters` keeps it.
  */
trait PlatformScalaCheck extends ScalaCheck:
  given platformParameters: Parameters =
    Parameters(minTestsOk = TestPlatform.propertyCases(100))
