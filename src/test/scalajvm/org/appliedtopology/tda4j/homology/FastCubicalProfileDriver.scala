package org.appliedtopology.tda4j

/** Test-scope timing driver for `FastCubicalHomologyEngine`, one engine per JVM:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.FastCubicalProfileDriver <n> <image=random|blob> [engine=fast|cohomology] [trials=3] [seed=42]
  * }}}
  *
  * `random` is uniform noise on an `n x n` grid. `blob` is a bright Gaussian bump on a dark background plus a little
  * noise -- the shape of most microscopy and medical images. In the dual union-find the bump forms one large component
  * long before the dark border reaches `∞`, so every later merge lands in that component rather than in `∞`'s; this is
  * the input that exposed the quadratic representative bookkeeping (`.claude/WORKLOG-fast-cubical-representatives.md`).
  * F_17 coefficients. Prints one line per trial and the median.
  */
object FastCubicalProfileDriver:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val image = if args.length > 1 then args(1) else "random"
    val engine = if args.length > 2 then args(2) else "fast"
    val trials = if args.length > 3 then args(3).toInt else 3
    val seed = if args.length > 4 then args(4).toLong else 42L

    val f17 = FiniteField(17)
    import f17.given

    val rng = new scala.util.Random(seed)
    val values: Array[Double] = image match
      case "random" => Array.fill(n * n)(rng.nextDouble())
      case "blob"   =>
        val c = (n - 1) / 2.0
        val s = n / 4.0
        Array.tabulate(n * n) { k =>
          val (i, j) = (k / n, k % n)
          val r2 = ((i - c) * (i - c) + (j - c) * (j - c)) / (s * s)
          math.exp(-r2 / 2) + 0.05 * rng.nextDouble()
        }
      case other => throw IllegalArgumentException(s"unknown image '$other' (random|blob)")

    def stream() = CubicalGridStream(IndexedSeq(n, n), idx => values(idx(0) * n + idx(1)))

    val times = (1 to trials).map { t =>
      val s = stream()
      val t0 = System.nanoTime()
      val bars = engine match
        case "fast"       => FastCubicalHomologyEngine[f17.Fp]().persistentHomology(s).size
        case "cohomology" =>
          CellularCohomologyEngine[Cube, f17.Fp, Double]().persistentHomology(s).size
        case other => throw IllegalArgumentException(s"unknown engine '$other' (fast|cohomology)")
      val ms = (System.nanoTime() - t0) / 1e6
      println(f"n=$n image=$image engine=$engine trial=$t bars=$bars ms=$ms%.1f")
      ms
    }
    println(f"MEDIAN n=$n image=$image engine=$engine ms=${times.sorted.apply(times.size / 2)}%.1f")
