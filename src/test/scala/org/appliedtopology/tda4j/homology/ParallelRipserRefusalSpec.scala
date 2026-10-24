package org.appliedtopology.tda4j

import Persistence.Engine
import SpecThrows.throwWith
import org.specs2.mutable.Specification

/** `Engine.ParallelRipser` on the platform that runs this spec: the JVM answers (the bars are checked against `Ripser`
  * in `ParallelRipserEngineSpec`), JavaScript has no threads and says to use `Engine.Ripser`.
  */
class ParallelRipserRefusalSpec extends Specification:
  private val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))

  "the parallel Ripser engine" should
    "answer on the JVM and refuse, naming Engine.Ripser, on JavaScript" >> {
      def run = Persistence(square, maxDimension = 1, engine = Engine.ParallelRipser)
      TestPlatform.perPlatform(
        // The square's four edges close a loop at 1; the diagonals and triangles fill it at the square root of 2.
        jvm = run.dim(1).triples === List((1, 1.0, math.sqrt(2.0))),
        js = run must throwWith[UnsupportedOperationException]("Engine.Ripser")
      )
    }
