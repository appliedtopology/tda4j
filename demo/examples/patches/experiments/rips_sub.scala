// Rips-type complexes on a maxmin SUBSAMPLE of a real core. -Dkind=vr|sparse|dtm -Dfile -Dnsub -Dparam (eps | k) -Dcap -Dtop -Dp
// vr     : plain Vietoris-Rips through the verb (packed Ripser), the baseline.
// sparse : SparseRips(eps) on the subsample.   dtm : DtmRips with DTM(k) weights computed on ALL the points (JVPTree),
//          restricted to the subsample.
// A cell budget (-Dbudget, default 5 000 000) turns an out-of-memory run into a one-line "too big".
val kind = sys.props("kind"); val file = sys.props("file"); val nsub = sys.props("nsub").toInt
val param = sys.props.getOrElse("param", "0").toDouble; val cap = sys.props("cap").toDouble
val top = sys.props("top").toInt; val p = sys.props("p").toInt
val budget = sys.props.getOrElse("budget", "5000000").toInt
try
  val all = load(file)
  val msAll = EuclideanMetricSpace(all, false)
  val sel = timed(s"maxmin $nsub of ${all.length}")(LandmarkSelector.maxmin(msAll, nsub))
  val sub = sel.landmarks.map(all(_)).toArray
  val ms = EuclideanMetricSpace(sub, true)
  println(
    f"=== CASE kind=$kind file=$file ${all.length} -> $nsub landmarks (covering radius ${sel.coveringRadius}%.4f) param=$param cap=$cap top=$top F_$p"
  )
  kind match
    case "vr" =>
      val d = timed("persistence: Vietoris-Rips through the verb")(
        Persistence(sub, maxDimension = top, maxFiltrationValue = cap, characteristic = p)
      )
      println(
        s"    bars: ${d.bars.size}, Betti numbers of the whole complex: ${d.bettiNumbers.mkString("(", ",", ")")}"
      )
      report(s"vr n=$nsub", d.bars, top, cap)
    case _ =>
      val stream = timed("stream") {
        kind match
          case "sparse" => SparseRips(ms, param, top, 0, cap)
          case "dtm"    =>
            val w = timed(s"DTM(k=${param.toInt}) of all ${all.length} points")(
              DistanceToMeasure(msAll, JVPTree(msAll), param.toInt, 2.0)
            )
            DtmRips(ms, sel.landmarks.map(w(_)), top, 1.0, cap)
      }
      val cells = timed(s"count cells (budget $budget)")(stream.iterator.take(budget + 1).length)
      if cells > budget then println(s"    cells: > $budget -- NOT reduced (too big for this input and scale)")
      else
        println(s"    cells: $cells")
        val d = timed(s"persistence F_$p")(Persistence(stream, maxDimension = top, characteristic = p))
        println(
          s"    bars: ${d.bars.size}, Betti numbers of the whole complex: ${d.bettiNumbers.mkString("(", ",", ")")}"
        )
        report(s"$kind n=$nsub", d.bars, top, cap)
catch
  case e: Throwable =>
    println(s"*** CASE FAILED: ${e.getClass.getName}: ${e.getMessage}")
    e.getStackTrace.filter(f => f != null && f.getClassName != null).take(8).foreach(f => println("      at " + f))
