// Natural image patches at real size: a circle, three circles, and a Klein bottle.
//
// Run, from the directory that holds n50000Dct.csv (convert.py makes it from JavaPlex's n50000Dct.mat):
//   java -Xmx4g -jar tda4j-demo-0.6.1.jar
//   scala> :load patches.scala
// The data: 50 000 random 3x3 high-contrast patches of natural images (Lee-Pedersen-Mumford, van Hateren's images), as
// unit vectors in R^8 (a DCT basis). Carlsson, Ishkhanov, de Silva, Zomorodian, "On the local behavior of spaces of natural
// images", IJCV 2008; the JavaPlex tutorial, section 6.
import scala.io.Source

def load(path: String): Array[Array[Double]] =
  val s = Source.fromFile(path)
  try s.getLines().filter(_.nonEmpty).map(_.split(',').map(_.toDouble)).toArray
  finally s.close()

/** Betti numbers of the complex at filtration value `t`: the bars of each degree that are alive at `t`. */
def betti(d: PersistenceDiagram[?], t: Double): List[Int] =
  List.tabulate(3)(k => d.bars.count(b => b.dim == k && b.birth <= t && t < b.death))

// ---- 1. Where are the patches dense? ----------------------------------------------------------------------------------
val patches = load("n50000Dct.csv")
val space = EuclideanMetricSpace(patches, false) // 50 000^2 distances are never stored
// The distance to measure with k = 15 neighbours: small where the patches are dense. JVPTree = fast neighbour search.
val density = DistanceToMeasure(space, JVPTree(space), 15, 2.0)

// ---- 2. The densest 30% (15 000 patches) -----------------------------------------------------------------------------
val core = density.zipWithIndex.sortBy(_._1).take(15000).map(_._2).sorted.map(patches(_)).toArray

// ---- 3. 50 landmarks, the lazy witness complex up to R/4, persistent homology over F_2 ------------------------------
val coreSpace = EuclideanMetricSpace(core, false)
val chosen = LandmarkSelector.maxmin(coreSpace, 50)
val range = chosen.coveringRadius / 4
val witness = Witness(coreSpace, chosen.landmarks, 2, Witness.Variant.Lazy, 1, range)
val diagram = Persistence(witness, maxDimension = 2, characteristic = 2)
println(
  s"Betti numbers (degrees 0, 1, 2) in the middle of the range: ${betti(diagram, range / 2).mkString("(", ", ", ")")}"
)
// (1, 5, 0): one component and five loops -- the "three circle model": a big circle of linear gradients and two smaller
// circles of quadratic ones (horizontal and vertical), each meeting the big one twice.
Plot.barcode(diagram).view()

// ---- 4. A Klein bottle of patches ------------------------------------------------------------------------------------
// This sample is too thin to show it (see the worklog): the paper needed 4 million patches. Their ideal Klein bottle is
// the family c(ax+by)^2 + d(ax+by) on the 3x3 grid, (a,b) and (c,d) on the unit circle, mean removed, normalised.
val rnd = new scala.util.Random(2026)
val grid = for y <- -1 to 1; x <- -1 to 1 yield (x.toDouble, y.toDouble)
val klein = Array.fill(5000) {
  val (t, s) = (rnd.nextDouble() * 2 * math.Pi, rnd.nextDouble() * 2 * math.Pi)
  val (a, b, c, d) = (math.cos(t), math.sin(t), math.cos(s), math.sin(s))
  val v = grid.map { (x, y) =>
    val l = a * x + b * y; c * l * l + d * l
  }.toArray
  val centred = v.map(_ - v.sum / v.length)
  centred.map(_ / math.sqrt(centred.map(x => x * x).sum))
}
val kleinSpace = EuclideanMetricSpace(klein, true)
val kLandmarks = LandmarkSelector.maxmin(kleinSpace, 100)
val kStream = Witness(kleinSpace, kLandmarks.landmarks, 2, Witness.Variant.Lazy, 1, kLandmarks.coveringRadius)
val overF2 = Persistence(kStream, maxDimension = 2, characteristic = 2)
val overF17 = Persistence(kStream, maxDimension = 2, characteristic = 17)
val mid = 0.75 * kLandmarks.coveringRadius
println(
  s"Klein bottle, Betti numbers at R * 0.75:  over F_2 ${betti(overF2, mid).mkString("(", ", ", ")")},  over F_17 ${betti(overF17, mid).mkString("(", ", ", ")")}"
)
// (1, 2, 1) over F_2 and (1, 1, 0) over F_17: the Klein bottle has 2-torsion in H_1. A torus gives (1, 2, 1) over both.
Plot
  .side(
    Plot.barcode(overF2).copy(title = "Klein bottle over F_2"),
    Plot.barcode(overF17).copy(title = "Klein bottle over F_17")
  )
  .view()
