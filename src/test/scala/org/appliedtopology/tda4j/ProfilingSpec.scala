package org.appliedtopology.tda4j

import org.specs2.mutable
import org.specs2.execute.Result
import org.specs2.main.Arguments

import java.lang.Runtime
import java.lang.System
import collection.immutable.BitSet

class ProfilingSpec(args: Arguments) extends mutable.Specification {
  """This is a profiling script to measure performance of different implementations.""" >> {
    val bitlength: Int = args.commandLine.intOr("bitlength", 3)
    val maxFVal: Double = args.commandLine.doubleOr("maxFVal", 7.0)
    val maxDim: Int = args.commandLine.intOr("maxDim", 7)

    val symmetry: HyperCubeSymmetry = HyperCubeSymmetry(bitlength)

    class HyperCubeProfiling(
      val vr: CliqueFinder[Int],
      val symmetry: HyperCubeSymmetry,
      val bitlength: Int,
      val tag: String
    ) {
      pp(s"Measuring ${vr.className}")
      var now: Long = System.currentTimeMillis()
      val sstream: Seq[AbstractSimplex[Int]] =
        vr(symmetry.hypercube, maxFVal, maxDim)
      var duration: Long = System.currentTimeMillis() - now
      pp(s"${tag}\tInitialization: $duration ms")
      pp(s"${tag}\tSimplex Stream Size: ${sstream.size}")

      now = System.currentTimeMillis()
      (1 until sstream.size).foreach(k => sstream(k))
      duration = System.currentTimeMillis() - now
      pp(s"${tag}\tTraversal: $duration ms")

      now = System.currentTimeMillis()
      (1 until sstream.size by 100).foreach(k => sstream(k))
      duration = System.currentTimeMillis() - now
      pp(s"${tag}\tLookups every 100: $duration ms")

    }

    section("bron-kerbosch")
    ("Bron-Kerbosch" >> {
      var bk: HyperCubeProfiling =
        HyperCubeProfiling(BronKerbosch[Int](), symmetry, bitlength, "BK")
    })
    section("bron-kerbosch")

    section("zomorodian-incremental")
    ("Zomorodian Incremental" >> {
      val zi: HyperCubeProfiling =
        HyperCubeProfiling(
          ZomorodianIncremental[Int](),
          symmetry,
          bitlength,
          "ZI"
        )
    })
    section("zomorodian-incremental")

    section("symmetric")
    ("Zomorodian Incremental with symmetry" >> {
      val szi: HyperCubeProfiling = HyperCubeProfiling(
        SymmetricZomorodianIncremental[Int, Int](symmetry),
        symmetry,
        bitlength,
        "SZI"
      )
    })
    section("symmetric")

    section("generators")
    "Zomorodian Incremental with symmetry group generators" >> {
      val symmetry_gen: HyperCubeSymmetryGenerators =
        HyperCubeSymmetryGenerators(bitlength)
      val szig: HyperCubeProfiling =
        HyperCubeProfiling(
          SymmetricZomorodianIncremental[Int, Int](symmetry_gen),
          symmetry_gen,
          bitlength,
          "SZIG"
        )
      pp("Counting pseudo-minimal elements")
      pp(s"SZIG ${symmetry_gen.representatives.size}")
      pp(s"SZIG ${symmetry_gen.representatives.count((k, v) => k != v)}")

      symmetry_gen.representatives.size === symmetry_gen.representatives.size
    }
    section("generators")

    section("ripser-gens")
    "Ripser Stream with symmetry group generators" >> {
      val symmetry_gen: HyperCubeSymmetryGenerators =
        HyperCubeSymmetryGenerators(bitlength)
      val rssg: HyperCubeProfiling =
        HyperCubeProfiling(
          MaskedSymmetricRipserVR[Int](symmetry_gen),
          symmetry_gen,
          bitlength,
          "RSSG"
        )
      pp("Counting pseudo-minimal elements")
      pp(s"RSSG Pseudo-minimal: ${symmetry_gen.representatives.size}")
      pp(s"RSSG Non-minimal: ${symmetry_gen.representatives
        .count((k, v) => k != v)}")

      symmetry_gen.representatives.size === symmetry_gen.representatives.size
    }
    section("ripser-gens")
  }
}
