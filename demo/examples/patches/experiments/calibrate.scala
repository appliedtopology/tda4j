// Calibration against the JavaPlex tutorial (section 6): 50 maxmin landmarks, lazy witness, nu = 1, R/4, Z/2.
//   X(300,30): Betti_0 = Betti_1 = 1 (a circle).   X(15,30): Betti_0 = 1, Betti_1 = 5, R = 0.6554 (three circles).
for name <- List("nk300c30Dct", "nk15c30Dct") do
  val pts = load(s"$name.csv")
  println(s"=== $name: ${pts.length} points in R^${pts(0).length}")
  val (d, maxF) = witnessRun(name, pts, landmarks = 50, nu = 1, divisor = 4.0, top = 2, p = 2)
  report(s"$name over F_2", d.bars, 2, maxF)
