// The paper's ideal Klein bottle and a flat torus (control), 5000 points. JavaPlex's own recipe for the flat Klein bottle
// (tutorial exercise 22): sequential maxmin, nu = 1, maxFiltrationValue = R; landmarks 50 there (1000 points), 100 and 150
// here as the sensitivity check. F_2 and F_17 from one stream each.
for (name, file) <- List(("ideal Klein 5000", "ideal_klein_5000.csv"), ("flat torus 5000", "torus_5000.csv")) do
  val pts = load(file)
  for landmarks <- List(50, 100, 150) do
    println(s"=== $name, $landmarks landmarks, nu=1, maxF = R")
    witnessTwo(s"$name L=$landmarks", pts, landmarks, nu = 1, divisor = 1.0)
