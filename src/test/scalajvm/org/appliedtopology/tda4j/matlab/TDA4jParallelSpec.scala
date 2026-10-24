package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.SpecThrows.throwWith
import org.specs2.mutable.Specification

/** The facade's `threads` option and the block-parallel Ripser engine behind `engine=parallel-ripser` (and behind an
  * unset `engine` when there are several threads): the bars of `engine=ripser`, representatives that are what they say,
  * `engine=ripser` itself untouched, and refusals that name the option to change.
  */
class TDA4jParallelSpec extends Specification:
  private val prime = 17

  private val circle: Array[Array[Double]] =
    val rng = scala.util.Random(21L)
    Array.tabulate(40) { i =>
      val a = 2 * math.Pi * i / 40
      Array(math.cos(a) + 0.05 * rng.nextGaussian(), math.sin(a) + 0.05 * rng.nextGaussian())
    }

  private def compute(extra: String*): PersistenceResult =
    TDA4j.computeFromPoints(circle, Array("minPersistence", "0") ++ extra)

  private def bars(r: PersistenceResult): List[(Int, Double, Double)] =
    r.toArray().toList.map(row => (row(0).toInt, row(1), row(2))).sorted

  private def representatives(r: PersistenceResult) =
    (0 until r.size()).toList.map(i => (r.cycleVertices(i).map(_.toList).toList, r.cycleCoefficients(i).toList))

  // The boundary of a cycle of edges, mod the prime: zero at every vertex.
  private def edgeCyclesClose(r: PersistenceResult): Boolean =
    (0 until r.size()).filter(r.dimension(_) == 1).forall { i =>
      val net = scala.collection.mutable.Map.empty[Int, Long].withDefaultValue(0L)
      for (edge, c) <- r.cycleVertices(i).zip(r.cycleCoefficients(i)) do
        net(edge(0)) -= c.round
        net(edge(1)) += c.round
      net.values.forall(v => ((v % prime) + prime) % prime == 0)
    }

  "engine=parallel-ripser" should {
    "give the bars of engine=ripser, cycles that close, and the boundary matrix" >> {
      val ripser = compute("engine", "ripser")
      val parallel = compute("engine", "parallel-ripser", "threads", "3")
      bars(parallel) === bars(ripser)
      edgeCyclesClose(parallel) must beTrue
      parallel.numCells() === ripser.numCells()
    }
    "give cocycles for representativeType=cocycles, with the same bars" >> {
      val ripser = compute("engine", "ripser", "representativeType", "cocycles")
      val parallel = compute("engine", "parallel-ripser", "representativeType", "cocycles", "threads", "2")
      bars(parallel) === bars(ripser)
      (0 until parallel.size()).forall(i => parallel.cycleCoefficients(i).nonEmpty) must beTrue
    }
    "give the same representatives on 1, 2 and 4 threads" >> {
      val one = representatives(compute("engine", "parallel-ripser", "threads", "1"))
      representatives(compute("engine", "parallel-ripser", "threads", "2")) === one
      representatives(compute("engine", "parallel-ripser", "threads", "4")) === one
    }
    "refuse what it cannot answer as engine=ripser would" >> {
      compute("engine", "parallel-ripser", "field", "R") must throwWith[IllegalArgumentException]("field=Z")
      compute("engine", "parallel-ripser", "includeZeroLength", "true") must throwWith[IllegalArgumentException](
        "includeZeroLength=false"
      )
      compute("engine", "parallel-ripser", "edgeCollapse", "true") must throwWith[IllegalArgumentException](
        "edgeCollapse=false"
      )
      compute("engine", "parallel-ripser", "complex", "cech") must throwWith[IllegalArgumentException](
        "only offered for complex=vr"
      )
      TDA4j.computeFromRelation(Array(Array(0.0, 1.0), Array(1.0, 0.0)), Array("engine", "parallel-ripser")) must
        throwWith[IllegalArgumentException]("only offered for complex=vr")
    }
  }

  "An unset engine" should {
    "be ripser on one thread, representatives and all" >> {
      representatives(compute("threads", "1")) === representatives(compute("engine", "ripser"))
    }
    "be the parallel engine on several threads, exactly as engine=parallel-ripser" >> {
      val auto = compute("threads", "3")
      bars(auto) === bars(compute("engine", "ripser"))
      representatives(auto) === representatives(compute("engine", "parallel-ripser", "threads", "3"))
    }
    "stay on ripser where the parallel engine cannot report: zero-length bars" >> {
      val withZero = compute("includeZeroLength", "true", "threads", "3")
      bars(withZero) === bars(compute("includeZeroLength", "true", "engine", "ripser"))
    }
  }

  "The threads option" should {
    "be refused below 1 or when it is not a number, before anything runs" >> {
      compute("threads", "0") must throwWith[IllegalArgumentException]("at least 1")
      compute("threads", "many") must throwWith[IllegalArgumentException]("must be an integer")
    }
    "be accepted by every computation" >> {
      TDA4j.computeFromRelation(Array(Array(0.0, 1.0), Array(1.0, 0.0)), Array("threads", "2")).size() must
        beGreaterThanOrEqualTo(1)
      TDA4j.computeFromCubicalImage(Array(3, 3), Array.fill(9)(1.0), Array("threads", "2")).size() must
        beGreaterThanOrEqualTo(0)
    }
  }
