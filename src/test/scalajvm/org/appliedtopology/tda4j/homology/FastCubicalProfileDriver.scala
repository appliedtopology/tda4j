package org.appliedtopology.tda4j

/** Test-scope timing driver for `FastCubicalHomologyEngine`, one engine per JVM:
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.FastCubicalProfileDriver <n> <image=random|blob|truchet|spikes> [engine=fast|cohomology] [trials=3] [seed=42]
  * }}}
  *
  * `truchet` is the distance image of a random Truchet tiling (tiles of 16 pixels, `n / 16` of them along a side) in a
  * frame of one pixel below every value: the image whose bars are those of the tiling relative to its boundary
  * (`_docs/tutorials/truchet-tiles.md`), a grid of `n + 2` pixels a side, with many equal maxima, one at every lattice
  * corner. `spikes` is a plateau at 3 inside a frame at 0, with a spike every 16 pixels whose heights rise in the order
  * the dual pass meets them: every merge kills the whole region accumulated so far, an elder-rule death no tie-break
  * can avoid (a stress case for the cost of a representative, `.claude/WORKLOG-fast-cubical-ties.md`). `random` is
  * uniform noise on an `n x n` grid. `blob` is a bright Gaussian bump on a dark background plus a little noise -- the
  * shape of most microscopy and medical images. In the dual union-find the bump forms one large component long before
  * the dark border reaches `∞`, so every later merge lands in that component rather than in `∞`'s; this is the input
  * that exposed the quadratic representative bookkeeping (`.claude/WORKLOG-fast-cubical-representatives.md`). F_17
  * coefficients. Prints one line per trial and the median.
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
    // The pixels along a side: the picture's, and a frame of one pixel around it for the tiling.
    val size = if image == "truchet" then n + 2 else n
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
      case "truchet" =>
        val side = 16
        val tiles = n / side
        val flips = Array.fill(tiles, tiles)(rng.nextBoolean())
        val picture = truchetDistances(flips, side)
        Array.tabulate(size * size) { k =>
          val (i, j) = (k / size, k % size)
          if i == 0 || j == 0 || i == size - 1 || j == size - 1 then -2.0 else picture(i - 1)(j - 1)
        }
      case "spikes" =>
        val spikes = for i <- 16 until n - 1 by 16; j <- 16 until n - 1 by 16 yield (i, j)
        val heights = spikes.zipWithIndex.map { case ((i, j), k) => (i * n + j) -> (4.0 + k * 1e-3) }.toMap
        Array.tabulate(n * n) { k =>
          val (i, j) = (k / n, k % n)
          if i == 0 || j == 0 || i == n - 1 || j == n - 1 then 0.0 else heights.getOrElse(k, 3.0)
        }
      case other => throw IllegalArgumentException(s"unknown image '$other' (random|blob|truchet|spikes)")

    def stream() = CubicalGridStream(IndexedSeq(size, size), idx => values(idx(0) * size + idx(1)))

    val times = (1 to trials).map { t =>
      val s = stream()
      var walked = 0L
      val t0 = System.nanoTime()
      val bars = engine match
        case "fast" =>
          val fast = FastCubicalHomologyEngine[f17.Fp]()
          val count = fast.persistentHomology(s).size
          walked = fast.representativeCellsWalked
          count
        case "cohomology" =>
          CellularCohomologyEngine[Cube, f17.Fp, Double]().persistentHomology(s).size
        case other => throw IllegalArgumentException(s"unknown engine '$other' (fast|cohomology)")
      val ms = (System.nanoTime() - t0) / 1e6
      println(f"n=$n image=$image engine=$engine trial=$t bars=$bars walked=$walked ms=$ms%.1f")
      ms
    }
    println(f"MEDIAN n=$n image=$image engine=$engine ms=${times.sorted.apply(times.size / 2)}%.1f")

  /** The distance of each pixel's centre to the nearest arc of its own tile, less one pixel: quarter circles of radius
    * `side / 2` about two opposite corners of each tile, the corners chosen by the tile's flip.
    */
  private def truchetDistances(flips: Array[Array[Boolean]], side: Int): Array[Array[Double]] =
    val r = side / 2.0
    def arc(x: Double, y: Double, cx: Double, cy: Double, sx: Double, sy: Double): Double =
      val vx = sx * (x - cx)
      val vy = sy * (y - cy)
      if vx >= 0 && vy >= 0 then math.abs(math.hypot(vx, vy) - r)
      else math.min(math.hypot(x - (cx + sx * r), y - cy), math.hypot(x - cx, y - (cy + sy * r)))
    val n = flips.length
    Array.tabulate(n * side, n * side) { (row, col) =>
      val (x, y) = (col + 0.5, row + 0.5)
      val (x0, y0) = ((col / side) * side.toDouble, (row / side) * side.toDouble)
      val (x1, y1) = (x0 + side, y0 + side)
      val d =
        if flips(row / side)(col / side) then math.min(arc(x, y, x0, y0, 1, 1), arc(x, y, x1, y1, -1, -1))
        else math.min(arc(x, y, x1, y0, -1, 1), arc(x, y, x0, y1, 1, -1))
      d - 1.0
    }
