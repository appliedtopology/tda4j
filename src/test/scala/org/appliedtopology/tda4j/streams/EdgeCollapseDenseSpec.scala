package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

/** The collapse keeps its graph as a matrix up to a vertex count and as a sorted map per vertex above it
  * (`EdgeCollapse.collapseWith`). Both must take every decision the same way: the same edges with the same values bit
  * for bit, and the same counts, on inputs where many edges tie, where the common neighbourhoods are large, with a
  * threshold, with zero distances and when collapsing a collapsed graph (whose removed pairs are infinite).
  */
class EdgeCollapseDenseSpec extends Specification:

  private def bits(x: Double) = java.lang.Double.doubleToLongBits(x)

  private def cloud(n: Int, dim: Int, seed: Long): Array[Array[Double]] =
    val rng = new scala.util.Random(seed)
    Array.fill(n)(Array.fill(dim)(rng.nextDouble()))

  // Symmetric, zero on the diagonal, entries from a few values: not a metric, which the collapse does not need.
  private def tied(n: Int, values: Int, seed: Long): ExplicitMetricSpace =
    val rng = new scala.util.Random(seed)
    val upper = Array.tabulate(n, n)((i, j) => if i < j then (1 + rng.nextInt(values)).toDouble else 0.0)
    ExplicitMetricSpace(Seq.tabulate(n, n)((i, j) => if i < j then upper(i)(j) else if i > j then upper(j)(i) else 0.0))

  private val inputs: Seq[(String, FiniteMetricSpace[Int], Option[Double])] =
    (for
      n <- Seq(3, 6, 11, 18, 27)
      seed <- 0L until 3L
      bound <- Seq(None, Some(0.6))
    yield (
      s"cloud of $n points in the plane, seed $seed, bound $bound",
      EuclideanMetricSpace(cloud(n, 2, seed)),
      bound
    )) ++
      (for
        n <- Seq(5, 9, 14, 22)
        values <- Seq(2, 3, 5)
        seed <- 0L until 3L
        bound <- Seq(None, Some(2.0))
      yield (s"$n points at $values distinct distances, seed $seed, bound $bound", tied(n, values, seed), bound)) ++
      Seq(
        (
          "a repeated point",
          EuclideanMetricSpace(
            Array(Array(0.0, 0.0), Array(0.0, 0.0), Array(1.0, 0.0), Array(0.0, 1.0), Array(1.0, 1.0))
          ),
          None
        ),
        ("points on a line", EuclideanMetricSpace(Array.tabulate(12)(i => Array(i.toDouble))), None),
        ("a cloud in space with a large radius", EuclideanMetricSpace(cloud(40, 3, 9L)), Some(0.9))
      )

  private def same(a: EdgeCollapsedMetricSpace, b: EdgeCollapsedMetricSpace): Boolean =
    val (ea, eb) = (a.edges.toVector, b.edges.toVector)
    ea.length == eb.length && ea.zip(eb).forall { case ((i, j, x), (k, l, y)) =>
      i == k && j == l && bits(x) == bits(y)
    } &&
    a.stats == b.stats &&
    (for i <- 0 until a.size; j <- 0 until a.size
    yield bits(a.distance(i, j)) == bits(b.distance(i, j))).forall(identity)

  "the matrix form and the map form of the collapse give the same graph, bit for bit" >> {
    val results = inputs.map { (name, space, bound) =>
      name -> same(EdgeCollapse.collapseWith(space, bound, Int.MaxValue), EdgeCollapse.collapseWith(space, bound, 0))
    }
    results.filterNot(_._2).map(_._1) must beEmpty
  }

  "collapsing a collapsed graph gives the same graph in both forms" >> {
    val results = inputs.map { (name, space, bound) =>
      val once = EdgeCollapse.collapseWith(space, bound, Int.MaxValue)
      name -> same(
        EdgeCollapse.collapseWith(once, Some(Double.PositiveInfinity), Int.MaxValue),
        EdgeCollapse.collapseWith(once, Some(Double.PositiveInfinity), 0)
      )
    }
    results.filterNot(_._2).map(_._1) must beEmpty
  }

  "the inputs include edges that are removed, edges that are postponed and edges that stay" >> {
    val shapes = inputs.map { (_, space, bound) =>
      val collapsed = EdgeCollapse.collapseWith(space, bound, 0)
      val original = EdgeCollapse.collapseWith(space, bound, 0) // for the counts only
      val postponed = collapsed.edges.count((i, j, value) => value != space.distance(i, j))
      (collapsed.stats.edgesRemoved > 0, postponed > 0, collapsed.stats.edgesAfter > 0, original.stats.edgesBefore)
    }
    (shapes.exists(_._1) must beTrue) and (shapes.exists(_._2) must beTrue) and (shapes.exists(_._3) must beTrue)
  }
