package org.appliedtopology.tda4j

/** Times the building of a stream's flat layers (every dimension up to `dim + 1`, listed once and not reduced), for the
  * complexes that have a weighted-edge generator, with and without it: one variant per JVM, the median of the trials.
  *
  * {{{
  * java -cp <test classpath> org.appliedtopology.tda4j.FlatLayersTimingDriver dtm|sparse|collapsed coface|generator n dim [trials] [seed] [radius]
  * }}}
  *
  * `coface` is the coface loop on flat arrays (the generator switched off), `generator` the layered in-order generator
  * over the stream's edges. The cloud is `n` uniform points in the plane for `sparse` and in 3-D for the others; the
  * distance to measure, the greedy permutation and the edge collapse are computed before the clock starts; `radius`
  * (default: the enclosing radius) is the threshold of the collapse and the stream.
  */
object FlatLayersTimingDriver:
  def main(args: Array[String]): Unit =
    val Array(complex, variant, nText, dimText) = args.take(4)
    val n = nText.toInt
    val dim = dimText.toInt
    val trials = args.lift(4).fold(3)(_.toInt)
    val seed = args.lift(5).fold(1L)(_.toLong)
    val radius = args.lift(6).map(_.toDouble)
    val rng = new scala.util.Random(seed)
    def cloud(ambient: Int): EuclideanMetricSpace =
      EuclideanMetricSpace(Array.fill(n)(Array.fill(ambient)(rng.nextDouble())))
    val withGenerator = variant == "generator"
    require(withGenerator || variant == "coface", s"variant is coface or generator, not $variant")
    val keep = CofaceSimplexStream.keepAll

    def stream(): LevelwiseSimplexStream[Int, Double] = complex match
      case "dtm" =>
        val metric = cloud(3)
        val reified = DtmRipsSimplexStream.DtmMetricSpace(metric, DistanceToMeasure(metric, 10, 2.0), 1.0)
        Truncated.ofCofaces(
          if withGenerator then new DtmRipsSimplexStream(reified, keep, None)
          else
            new DtmRipsSimplexStream(reified, keep, None):
              override protected def flatGenerator = None
          ,
          dim
        )
      case "sparse" =>
        val metric = cloud(2)
        val selection = LandmarkSelector.maxmin(metric, metric.size, 0)
        val permutation = GreedyPermutation(selection.landmarks, selection.insertionRadius)
        Truncated.ofCofaces(
          if withGenerator then new SheehyRipsSimplexStream(metric, permutation, 0.3, keep, None)
          else
            new SheehyRipsSimplexStream(metric, permutation, 0.3, keep, None):
              override protected def flatGenerator = None
          ,
          dim
        )
      case "collapsed" =>
        val collapsed = EdgeCollapse.collapse(cloud(3), radius)
        Truncated.ofCofaces(
          if withGenerator then new RipserCofaceSimplexStream(collapsed, keep, radius)
          else
            new RipserCofaceSimplexStream(collapsed, keep, radius):
              override protected def flatGenerator = None
          ,
          dim
        )
      case other => throw new IllegalArgumentException(s"complex is dtm, sparse or collapsed, not $other")

    val timings = (1 to trials).map { _ =>
      val s = stream()
      val start = System.nanoTime()
      val cells = s.asInstanceOf[FlatLayerStream].flatLayers.get.map(_.size.toLong).sum
      ((System.nanoTime() - start) / 1e6, cells)
    }
    val sorted = timings.map(_._1).sorted
    println(
      f"$complex $variant n=$n dim=$dim: median ${sorted(sorted.size / 2)}%.0f ms of ${timings.map(t => f"${t._1}%.0f").mkString(", ")}; cells ${timings.map(_._2).mkString(", ")}"
    )
