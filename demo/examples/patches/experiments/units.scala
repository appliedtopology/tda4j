// What do the filtration values of VietorisRips, SparseRips and DtmRips mean, on the same 200 points?
val pts = load("nk15c30Dct.csv").take(200)
val ms = EuclideanMetricSpace(pts, true)
val vr = VietorisRips(ms, 1, 3.0)
val tiny = SparseRips(ms, 0.02, 1, 0, 3.0)
val coarse = SparseRips(ms, 0.9, 1, 0, 3.0)
val w = DistanceToMeasure(ms, 10, 2.0)
val dtm = DtmRips(ms, w, 1, 1.0, 3.0)
def val1(s: LevelwiseSimplexStream[Int, Double], i: Int, j: Int): String =
  val e = Simplex(i, j)
  if s.filtrationValue.isDefinedAt(e) then f"${s.filtrationValue(e)}%.4f" else "(not in complex)"
for (i, j) <- List((0, 1), (0, 2), (3, 7), (10, 150)) do
  println(
    f"edge ($i,$j): d = ${ms.distance(i, j)}%.4f  w_i = ${w(i)}%.4f  w_j = ${w(j)}%.4f | VR ${val1(vr, i, j)} | Sparse(eps=0.02) ${val1(tiny, i, j)} | Sparse(eps=0.9) ${val1(coarse, i, j)} | DtmRips(p=1) ${val1(dtm, i, j)}"
  )
println(
  s"vertex 0: VR ${vr.filtrationValue(Simplex(0))}  Sparse(0.02) ${tiny.filtrationValue(Simplex(0))}  Dtm ${dtm.filtrationValue(Simplex(0))} (w_0 = ${w(0)})"
)
println(
  s"edges: VR ${vr.iterateDimension(1).size}, Sparse(0.02) ${tiny.iterateDimension(1).size}, Sparse(0.9) ${coarse.iterateDimension(1).size}, Dtm ${dtm.iterateDimension(1).size} (of ${200 * 199 / 2} pairs)"
)
