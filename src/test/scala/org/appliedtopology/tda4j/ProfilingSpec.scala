package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result

import java.lang.Runtime
import java.lang.System

import collection.immutable.BitSet

class ProfilingSpec extends mutable.Specification {
  sequential
  """This is a profiling script to measure performance of different implementations."""

  val bitlength: Int = 3
  val symmetry: HyperCubeSymmetry = HyperCubeSymmetry(bitlength)

  class HyperCubeProfiling(vr : CliqueFinder[BitSet],
                           symmetry : HyperCubeSymmetry,
                           bitlength : Int) {
    pp(s"Measuring ${vr.className}")
    var now: Long = System.currentTimeMillis()
    val sstream: Seq[AbstractSimplex[BitSet]] = vr(symmetry.hypercube, 10, 10)
    var duration: Long = System.currentTimeMillis() - now
    pp(s"Initialization: $duration ms")
    pp(s"Simplex Stream Size: ${sstream.size}")

    now = System.currentTimeMillis()
    (1 until sstream.size).foreach(k => sstream(k))
    duration = System.currentTimeMillis() - now
    pp(s"Traversal: $duration ms")

    now = System.currentTimeMillis()
    (1 until sstream.size by 100).foreach(k => sstream(k))
    duration = System.currentTimeMillis() - now
    pp(s"Lookups every 100: $duration ms")

  }

  var bk: HyperCubeProfiling = HyperCubeProfiling(BronKerbosch[BitSet](), symmetry, bitlength)
  val zi: HyperCubeProfiling = HyperCubeProfiling(ZomorodianIncremental[BitSet](), symmetry, bitlength)
  val szi: HyperCubeProfiling = HyperCubeProfiling(SymmetricZomorodianIncremental[BitSet, Int](symmetry), symmetry, bitlength)

  "Correct sizes" >> {
    zi.sstream.size === szi.sstream.size
  }
}
