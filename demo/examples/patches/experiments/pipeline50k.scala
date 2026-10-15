// The whole pipeline inside tda4j: 50 000 CSV rows -> distance to measure (density) -> densest 30% -> witness complex.
// Compared with the shipped cores (which are the densest 30% by 1/rho_k, the k-th neighbour, self not counted).
val all = timed("load n50000Dct.csv (50000 x 8)")(load("n50000Dct.csv"))
val ms = EuclideanMetricSpace(all, false)
val tree = JVPTree(ms)
for (k, shippedName) <- List((15, "nk15c30Dct.csv"), (300, "nk300c30Dct.csv")) do
  println(s"=== density k = $k")
  val dtm = timed(s"DTM(k=$k, q=2, JVPTree) of 50000 points")(DistanceToMeasure(ms, tree, k, 2.0))
  val order = dtm.zipWithIndex.sortBy(_._1).map(_._2)
  val keep = order.take(15000).sorted
  val core = keep.map(all(_)).toArray
  val shipped = load(shippedName).map(_.toSeq).toSet
  val common = core.count(r => shipped(r.toSeq))
  println(s"    DTM core vs the shipped $shippedName: $common of 15000 rows in common")
  val (d, maxF) = witnessRun(s"DTM-core k=$k", core, landmarks = 50, nu = 1, divisor = 4.0, top = 2, p = 2)
  report(s"DTM core (k=$k) over F_2", d.bars, 2, maxF)
