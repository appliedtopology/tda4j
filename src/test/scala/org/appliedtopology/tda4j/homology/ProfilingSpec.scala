package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.alpha.{given, *}

import org.specs2.mutable
import org.specs2.execute.Result
import org.specs2.main.Arguments

import java.lang.Runtime
import java.lang.System
import collection.immutable.BitSet

class ProfilingSpec(args: Arguments) extends mutable.Specification:
  // Skipped by default so plain `sbt test` never pays for this -- pass -DrunBenchmarks=true to actually run it
  // (see CLAUDE.md's "Commands" section). One shared flag gates every *BenchmarkSpec/ProfilingSpec in this package.
  if !args.commandLine.boolOr("runBenchmarks", false) then skipAll
  """This is a profiling script to measure performance of different implementations.""" >> {
    val bitlength: Int = args.commandLine.intOr("bitlength", 3)
    val maxFVal: Double = args.commandLine.doubleOr("maxFVal", 4.0)
    val maxDim: Int = args.commandLine.intOr("maxDim", 7)

    val symmetry: HyperCubeSymmetry = HyperCubeSymmetry(bitlength)

  }
