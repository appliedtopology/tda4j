// One core of the fixed Klein-search grid: -Dk=<K> -DT=<percent>. 100 maxmin landmarks, nu = 1, maxFiltrationValue = R,
// F_2 and F_17; the verdict function is the one fixed in advance. Nothing is tuned.
val k = sys.props("k").toInt; val t = sys.props("T").toInt
val name = s"core k=$k T=$t%"
println(s"=== $name")
try witnessTwo(name, load(s"core_k${k}_T$t.csv"), landmarks = 100, nu = 1, divisor = 1.0)
catch case e: Throwable => println(s"*** $name FAILED: ${e.getClass.getName}: ${e.getMessage}")
