package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** Timing driver: `sbt "Test/runMain org.appliedtopology.tda4j.groups.GroupHomologyDemo S4 2 3"` (group, prime,
  * degree).
  */
object GroupHomologyDemo:
  def main(args: Array[String]): Unit =
    if args(0) == "S4chain" then
      val (s4, gens) =
        FiniteGroup.permutationGroup(4, Seq(Seq(1, 0, 2, 3), Seq(0, 1, 3, 2), Seq(2, 3, 0, 1), Seq(1, 2, 3, 0)))
      val chain = Seq(
        s4.subgroupGeneratedBy(Seq(gens(0))),
        s4.subgroupGeneratedBy(Seq(gens(0), gens(1))),
        s4.subgroupGeneratedBy(Seq(gens(0), gens(1), gens(2))),
        (0 until s4.order).toSet
      )
      val prime = args(1).toInt
      ClassifyingSpace
        .persistentGroupHomology(s4, chain, args(2).toInt, prime)
        .groupBy(identity)
        .toList
        .sortBy(_._1)
        .foreach { (bar, same) =>
          println(s"H_${bar._1}: [${bar._2}, ${bar._3}) x ${same.size}")
        }
      return
    val g = args(0) match
      case "S3" => FiniteGroup.symmetric(3)
      case "S4" => FiniteGroup.symmetric(4)
      case "S5" => FiniteGroup.symmetric(5)
      case "V4" => FiniteGroup.product(FiniteGroup.cyclic(2), FiniteGroup.cyclic(2))
      case n    => FiniteGroup.cyclic(n.toInt)
    val prime = args(1).toInt
    val degree = args(2).toInt
    val t0 = System.nanoTime()
    val betti = ClassifyingSpace.bettiNumbers(g, degree, prime)
    println(s"${args(0)} F_$prime H_0..H_$degree = $betti   (${(System.nanoTime() - t0) / 1000000} ms)")
