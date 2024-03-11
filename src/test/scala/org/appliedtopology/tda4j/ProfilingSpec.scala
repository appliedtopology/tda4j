package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result
import org.specs2.main.Arguments

import java.lang.Runtime
import java.lang.System
import collection.immutable.BitSet

class ProfilingSpec(args: Arguments) extends mutable.Specification {
  """This is a profiling script to measure performance of different implementations.""" >> {
    sequential

    val bitlength: Int = args.commandLine.intOr("bitlength", 3)
    val symmetry: HyperCubeSymmetry = HyperCubeSymmetry(bitlength)

    class HyperCubeProfiling(
                              val vr: CliqueFinder[BitSet],
                              val symmetry: HyperCubeSymmetry,
                              val bitlength: Int
                            ) {
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

    "Bron-Kerbosch" >> {
      var bk: HyperCubeProfiling =
        HyperCubeProfiling(BronKerbosch[BitSet](), symmetry, bitlength)
    } section("bron-kerbosch")
    "Zomorodian Incremental" >> {
      val zi: HyperCubeProfiling =
        HyperCubeProfiling(ZomorodianIncremental[BitSet](), symmetry, bitlength)
    } section("zomorodian-incremental")

    "Zomorodian Incremental with symmetry" >> {
      val szi: HyperCubeProfiling = HyperCubeProfiling(
        SymmetricZomorodianIncremental[BitSet, Int](symmetry),
        symmetry,
        bitlength
      )
    } section("symmetric")

    section("generators")
    "Zomorodian Incremental with symmetry group generators" >> {
      val symmetry_gen: HyperCubeSymmetryGenerators = HyperCubeSymmetryGenerators(bitlength)
      val szig: HyperCubeProfiling =
        HyperCubeProfiling(
          SymmetricZomorodianIncremental[BitSet, Int](symmetry_gen),
          symmetry_gen,
          bitlength
        )
      pp("Counting pseudo-minimal elements")
      pp(symmetry_gen.representatives.size)
      pp(symmetry_gen.representatives.count { (k, v) => k != v })

      eg(symmetry_gen.representatives.size === symmetry_gen.representatives.size)
    }
    section("generators")

//    "Correct sizes" >> {
//      zi.sstream.size === szi.sstream.size
//    }
//
//    "With generators and pseudo-minimal elements" >> {
//      zi.sstream.size === szig.sstream.size
//    }
  }
}
