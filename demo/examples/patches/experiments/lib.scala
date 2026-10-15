// Shared helpers for the natural-image-patch experiments. Loaded with `:load` in the demo jar's REPL
// (the imports `modularity`, `tda4j.*`, `plot.*` are already run by the jar).
import scala.io.Source

val dataDir: String = sys.props.getOrElse("klein.dir", ".")

def load(name: String): Array[Array[Double]] =
  val s = Source.fromFile(s"$dataDir/$name")
  try s.getLines().filter(_.nonEmpty).map(_.split(',').map(_.toDouble)).toArray
  finally s.close()

def timed[A](label: String)(body: => A): A =
  val t0 = System.nanoTime()
  val r = body
  println(f"[time] $label: ${(System.nanoTime() - t0) / 1e9}%.1f s")
  r

/** Betti numbers (degrees 0..top) of the complex at filtration value t: bars with birth <= t < death. */
def bettiAt(bars: List[PersistenceBar[Double, ?]], top: Int, t: Double): Vector[Int] =
  Vector.tabulate(top + 1)(k => bars.count(b => b.dim == k && b.birth <= t && t < b.death))

/** Run-length summary of the Betti vector over a grid of `steps` values in [0, upTo]: (betti, from, to) per run. */
def plateaus(
  bars: List[PersistenceBar[Double, ?]],
  top: Int,
  upTo: Double,
  steps: Int = 400
): List[(Vector[Int], Double, Double)] =
  val ts = (0 to steps).map(i => upTo * i / steps)
  val vs = ts.map(t => bettiAt(bars, top, t))
  val runs = scala.collection.mutable.ListBuffer.empty[(Vector[Int], Double, Double)]
  var i = 0
  while i < vs.size do
    var j = i
    while j + 1 < vs.size && vs(j + 1) == vs(i) do j += 1
    runs += ((vs(i), ts(i), ts(j)))
    i = j + 1
  runs.toList

def report(
  label: String,
  bars: List[PersistenceBar[Double, ?]],
  top: Int,
  upTo: Double,
  minShare: Double = 0.04
): Unit =
  println(s"--- $label: Betti plateaus (>= ${(minShare * 100).round}% of the range [0, ${"%.4f".format(upTo)}])")
  for (v, a, b) <- plateaus(bars, top, upTo) if (b - a) >= minShare * upTo do
    println(f"    ${v.mkString("(", ",", ")")}%-14s from $a%.4f to $b%.4f  (width ${(b - a) / upTo * 100}%.0f%%)")

/** The tutorial's recipe: sequential maxmin landmarks, lazy witness complex, maxFiltration = R / divisor. */
def witnessRun(
  name: String,
  pts: Array[Array[Double]],
  landmarks: Int,
  nu: Int,
  divisor: Double,
  top: Int,
  p: Int
): (PersistenceDiagram[Simplex[Int]], Double) =
  val ms = EuclideanMetricSpace(pts)
  val sel = timed(s"$name: maxmin ($landmarks landmarks of ${pts.length})")(LandmarkSelector.maxmin(ms, landmarks))
  val maxF = sel.coveringRadius / divisor
  println(f"    $name: R = ${sel.coveringRadius}%.4f, maxFiltrationValue = R/$divisor%.1f = $maxF%.4f")
  val stream = timed(s"$name: lazy witness stream (nu=$nu, top degree $top)")(
    Witness(ms, sel.landmarks, top, Witness.Variant.Lazy, nu, maxF)
  )
  val d = timed(s"$name: persistence over F_$p")(Persistence(stream, maxDimension = top, characteristic = p))
  (d, maxF)

/** The pass criterion, fixed in advance (WORKLOG-klein-bottle-patches.md): an interval of >= 10% of the range where the
  * Betti vector is (1,2,1) over F_2 and (1,1,0) over F_17 throughout is Klein-like; (1,2,1) over both is torus-like.
  */
def verdict(d2: List[PersistenceBar[Double, ?]], d17: List[PersistenceBar[Double, ?]], maxF: Double): String =
  val wide = plateaus(d2, 2, maxF).filter((v, a, b) => v == Vector(1, 2, 1) && (b - a) >= 0.10 * maxF)
  if wide.isEmpty then "NO interval of >= 10% with (1,2,1) over F_2: neither Klein nor torus by this criterion"
  else
    wide
      .map { (_, a, b) =>
        val sample = (0 to 20).map(i => a + (b - a) * i / 20).map(t => bettiAt(d17, 2, t)).toSet
        val kind =
          if sample == Set(Vector(1, 1, 0)) then "KLEIN-LIKE"
          else if sample == Set(Vector(1, 2, 1)) then "TORUS-LIKE"
          else "MIXED"
        f"$kind on [$a%.4f, $b%.4f]: F_2 = (1,2,1), F_17 over the same interval = ${sample.map(_.mkString("(", ",", ")")).mkString(" / ")}"
      }
      .mkString("; ")

/** Builds the lazy witness stream ONCE and runs it over F_2 and F_17. */
def witnessTwo(name: String, pts: Array[Array[Double]], landmarks: Int, nu: Int, divisor: Double): Unit =
  val ms = EuclideanMetricSpace(pts, false)
  val sel = timed(s"$name: maxmin ($landmarks of ${pts.length})")(LandmarkSelector.maxmin(ms, landmarks))
  val maxF = sel.coveringRadius / divisor
  println(f"    $name: R = ${sel.coveringRadius}%.4f, maxFiltrationValue = R/$divisor%.1f = $maxF%.4f")
  val stream =
    timed(s"$name: lazy witness stream (nu=$nu)")(Witness(ms, sel.landmarks, 2, Witness.Variant.Lazy, nu, maxF))
  val d2 = timed(s"$name: persistence F_2")(Persistence(stream, maxDimension = 2, characteristic = 2))
  val d17 = timed(s"$name: persistence F_17")(Persistence(stream, maxDimension = 2, characteristic = 17))
  report(s"$name F_2", d2.bars, 2, maxF)
  report(s"$name F_17", d17.bars, 2, maxF)
  println(s"*** $name VERDICT: " + verdict(d2.bars, d17.bars, maxF))
