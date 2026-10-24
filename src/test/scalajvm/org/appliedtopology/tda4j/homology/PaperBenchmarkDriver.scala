package org.appliedtopology.tda4j

import java.io.{BufferedInputStream, DataInputStream, FileInputStream, PrintWriter}
import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.{ByteBuffer, ByteOrder}
import scala.jdk.CollectionConverters.ListHasAsScala

/** TDA4j's side of the cross-platform benchmark (`bench/`, `.claude/PLAN-paper.md` §3). One computation per JVM: read
  * the input once, run `warmup` untimed and `trials` timed computations through the public `Persistence` verb (or a
  * named engine), print one JSON line with the timings, and write the last barcode as `dim<TAB>birth<TAB>death`.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.PaperBenchmarkDriver task=vr input=sphere.txt format=points dim=2 \
  *   [threshold=1.8] [p=2] [engine=auto|ripser|cohomology|chunks|naive|fastcubical|fastalpha|blocks|parallel] \
  *   [reps=cycles|cocycles|none] [backend=default|bowyer-watson|helix|dqp] [warmup=2] [trials=5] [out=bars.tsv]
  *   [read=true] [hold=seconds] [digest=true] [count=true] [threads=N]
  * }}}
  *
  *   - `task=vr`: points (`format=points`, one point per line) or a full distance matrix (`format=distance`).
  *   - `task=cubical`: a `.npy` array of float64 in C order (any dimension), sublevel filtration, T-construction.
  *   - `task=alpha`: points; filtration values are circumradii. `backend=default` (Bowyer-Watson up to 4 dimensions,
  *     Helix above, DQP when `threshold` is small enough), `bowyer-watson`, `helix` or `dqp`. `threshold` is the
  *     largest radius: bars alive at it are essential, and DQP builds only what lies within it. `engine=fastalpha` runs
  *     on the backend's triangulation and takes no threshold.
  *   - `task=cech`: points; filtration values are radii (the same barcode as alpha in general position).
  *   - `task=vr engine=parallel [threads=N]`: the block-parallel experiment `ParallelPackedCohomology` (implicit
  *     coboundaries) instead of `Persistence`, bars only (no representatives), on `threads` threads (default: every
  *     processor). Without a threshold it stops at the enclosing radius, as `Persistence` does; the edge table and the
  *     complex are built inside the timed region. `engine=parallel wide=true` uses two-word keys even where one Long
  *     would do (what they cost).
  *   - `task=vr collapse=true`: Vietoris-Rips after `EdgeCollapse.collapse` (the collapse is inside the timed region);
  *     the cross-tool twin of GUDHI's edge collapse.
  *   - `task=sparse eps=0.3`: Sheehy's sparse Rips (`SparseRips`, Cavanna-Jahanseir-Sheehy); points or a distance
  *     matrix as for `vr`. A barcode that approximates the exact Rips one, not equal to it.
  *   - `task=dtm k=10 [q=2] [dtmp=1]`: the DTM-weighted Rips complex (`DtmRips.fromNeighbours`, `k` self-inclusive).
  *   - `task=witness [landmarks=file | L=50] [wvariant=lazy|general] [nu=2]`: the witness complex of the points on
  *     landmarks given as a file of point indices, one per line (the same for every tool), or chosen by maxmin.
  *   - `task=dowker [dual=true]`: a relation matrix (one row per vertex, one column per witness) in `input`, as `vr`'s
  *     `format=distance` file; `dual=true` builds the complex on the other side.
  *   - `task=helix-validity d=5 n=40 [shape=interior|surface] [draws=1000] [seed0=0]`: no input file. For each of
  *     `draws` random clouds of the unit cube (the generator of `bench/sweep_alpha.py`, but Java's `SplittableRandom`,
  *     so the clouds are not the sweep's) build `HelixDelaunay` as `Persistence` does and count the clouds whose
  *     triangulation fails the checks the fast alpha engine relies on (a facet with other than one or two top cofaces,
  *     an interior void, `looksValid`), those for which `requireValidTriangulation = true` throws, and those where the
  *     walk fell back to its slow candidate search; one JSON line (`bench/sweep_alpha.py --validity`).
  *   - `count=true` (tasks `vr`, `alpha`, `cech`, `sparse`, `dtm`, `witness`, `dowker`): build the complex the task
  *     would reduce, count its cells by dimension (up to `dim + 1`, all a barcode up to degree `dim` needs) and print
  *     one JSON line with `cells`; no engine runs (`bench/count_cells.py`).
  *
  * Times cover the whole `Persistence(...)` call: building the complex, the reduction and the representatives. For
  * `task=cubical`, `read=true` also reads every representative's terms inside the timed region (the JSON line reports
  * how many), `hold=N` keeps the last diagram reachable for N seconds after the trials (for a heap histogram), and
  * `digest=true` adds a SHA-256 of the last diagram's bars with their representatives (to compare two builds term for
  * term).
  */
object PaperBenchmarkDriver:
  given Epsilon = Epsilon(1e-5)

  def main(args: Array[String]): Unit =
    val opts: Map[String, String] = args.map { a =>
      val i = a.indexOf('=')
      require(i > 0, s"arguments are key=value, got '$a'")
      a.take(i) -> a.drop(i + 1)
    }.toMap
    val task = opts("task")
    if task == "helix-validity" then
      helixValidity(opts)
      return
    val input = opts("input")
    val dim = opts("dim").toInt
    val p = opts.getOrElse("p", "17").toInt
    val threshold: Option[Double] = opts.get("threshold").map(_.toDouble)
    val engineName = opts.getOrElse("engine", "auto").toLowerCase
    val repsName = opts.getOrElse("reps", "cycles").toLowerCase
    val reps = repsName match
      case "cycles" | "none" => Representatives.Cycles
      case "cocycles"        => Representatives.Cocycles
      case other             => throw IllegalArgumentException(s"reps=$other (cycles|cocycles|none)")
    val warmup = opts.getOrElse("warmup", "2").toInt
    val trials = opts.getOrElse("trials", "5").toInt
    val readReps = opts.get("read").contains("true")
    // hold=N (cubical): keep the last diagram reachable for N seconds after the trials, for a heap histogram.
    val holdSeconds = opts.getOrElse("hold", "0").toInt
    // digest=true (cubical): a SHA-256 over the last diagram's bars in list order, each with its representative's keys
    // and coefficients, to compare two builds on one input term for term.
    val wantDigest = opts.get("digest").contains("true")
    var digest = ""
    if opts.get("count").contains("true") then
      countCells(task, opts, dim, threshold)
      return
    var termsRead = 0L
    var held: AnyRef = null

    def engine: Persistence.Engine = engineName match
      case "auto"        => Persistence.Engine.Auto
      case "ripser"      => Persistence.Engine.Ripser
      case "cohomology"  => Persistence.Engine.Cohomology
      case "chunks"      => Persistence.Engine.Chunks
      case "naive"       => Persistence.Engine.Naive
      case "fastcubical" => Persistence.Engine.FastCubical
      case other         => throw IllegalArgumentException(s"engine=$other not valid for task=$task")

    val threads = opts.get("threads").fold(Runtime.getRuntime.availableProcessors)(_.toInt)

    val loadStart = System.nanoTime()
    val compute: () => List[(Int, Double, Double)] = task match
      case "vr" if engineName == "parallel" =>
        val ms = metricSpaceOf(opts)
        val n = ms.size
        require(n.toLong * n <= Int.MaxValue, s"engine=parallel keeps n x n tables: $n points are too many")
        val table = Array.tabulate(n * n)(k => ms.distance(k / n, k % n))
        val distance: (Int, Int) => Double = (i, j) => table(i * n + j)
        val t = threshold.getOrElse(ms.minimumEnclosingRadius)
        // Daemon threads: the pool must not keep the JVM alive after the trials.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
          threads,
          (r: Runnable) =>
            val thread = Thread(r)
            thread.setDaemon(true)
            thread
        )
        () =>
          val space =
            ParallelPackedCohomology.Space(n, distance, t, dim, p, forceWide = opts.get("wide").contains("true"))
          ParallelPackedCohomology.persistence(space, threads, 8, cocycles = false, pool).bars.toList
      case "vr" if opts.get("collapse").contains("true") =>
        val ms = metricSpaceOf(opts)
        () =>
          Persistence(
            EdgeCollapse.collapse(ms, threshold),
            maxDimension = dim,
            maxFiltrationValue = threshold,
            characteristic = p,
            engine = engine,
            representatives = reps
          ).triples
      case "vr" =>
        opts.getOrElse("format", "points") match
          case "points" =>
            val pts = readMatrix(input)
            () =>
              Persistence(
                pts,
                maxDimension = dim,
                maxFiltrationValue = threshold,
                characteristic = p,
                engine = engine,
                representatives = reps
              ).triples
          case "distance" =>
            val ms: FiniteMetricSpace[Int] = ExplicitMetricSpace(readMatrix(input).map(_.toSeq).toSeq)
            () =>
              Persistence(
                ms,
                maxDimension = dim,
                maxFiltrationValue = threshold,
                characteristic = p,
                engine = engine,
                representatives = reps
              ).triples
          case other => throw IllegalArgumentException(s"format=$other (points|distance)")
      case "cubical" =>
        val (values, shape) = readNpy(input)
        val image = Image(scala.collection.immutable.ArraySeq.unsafeWrapArray(values), shape)
        if repsName == "none" then
          // fastcubical only: the same bars without the top degree's representatives, to measure what they cost.
          require(engineName == "fastcubical", "reps=none is only for engine=fastcubical")
          val grid = CubicalImage.fromFlatArray(shape, scala.collection.immutable.ArraySeq.unsafeWrapArray(values))
          val ff = FiniteField(p)
          import ff.given
          () =>
            FastCubicalHomologyEngine[ff.Fp]()
              .barsWithoutTopRepresentatives(grid)
              .filter(_.dim <= dim)
              .map(_.toTriple)
        else
          () =>
            val diagram =
              Persistence(image, maxDimension = dim, characteristic = p, engine = engine, representatives = reps)
            // read=true: every representative's terms once, inside the timed region, as a caller reading them all.
            if readReps then termsRead = diagram.bars.iterator.map(_.representative.terms.size.toLong).sum
            if holdSeconds > 0 then held = diagram
            if wantDigest then
              val md = java.security.MessageDigest.getInstance("SHA-256")
              def put(text: String): Unit = md.update(text.getBytes("UTF-8"))
              diagram.bars.foreach { bar =>
                put(s"${bar.dim}|${bar.lower}|${bar.upper}|")
                (bar.representative: AnyRef) match
                  case packed: PackedChain[?, ?] =>
                    put(java.util.Arrays.toString(packed.keys))
                    put(packed.coefficients.mkString(","))
                  case other => put(bar.representative.terms.toString)
                put("\n")
              }
              digest = md.digest().map(byte => f"$byte%02x").mkString
            diagram.triples
      case "alpha" =>
        val pts = readMatrix(input)
        val backend = AlphaBackend.parse(opts.getOrElse("backend", "default"))
        if engineName == "fastalpha" then
          require(threshold.isEmpty, "engine=fastalpha takes no threshold: it needs the whole triangulation")
          () => fastAlpha(pts, backend, dim, p)
        else if backend != AlphaBackend.Default then
          // With a threshold the radius reaches the backend: DQP only builds the simplices within it (without one it
          // is the untruncated DQP), the triangulations are filtered.
          () =>
            Persistence(
              Truncated(AlphaShapes(pts, backend, maxRadius = threshold, maxDimension = dim), dim),
              characteristic = p,
              engine = engine,
              representatives = reps
            ).triples
        else
          () =>
            Persistence(
              pts,
              maxDimension = dim,
              maxFiltrationValue = threshold,
              complex = AlphaShapes,
              characteristic = p,
              engine = engine,
              representatives = reps
            ).triples
      case "cech" =>
        // Same barcode as alpha for points in general position (persistent nerve lemma): an independent oracle.
        val pts = readMatrix(input)
        () =>
          Persistence(
            pts,
            maxDimension = dim,
            maxFiltrationValue = threshold,
            complex = Cech,
            characteristic = p,
            engine = engine,
            representatives = reps
          ).triples
      case "sparse" | "dtm" | "witness" | "dowker" =>
        () =>
          Persistence(
            streamFor(task, opts, dim, threshold),
            characteristic = p,
            engine = engine,
            representatives = reps
          ).triples
      case other => throw IllegalArgumentException(s"task=$other (vr|cubical|alpha|cech|sparse|dtm|witness|dowker)")
    val loadSeconds = (System.nanoTime() - loadStart) / 1e9

    // The last run's bars are let go before the next run starts: a measured peak is one computation's, not two.
    var bars: List[(Int, Double, Double)] = Nil
    (1 to warmup).foreach { _ =>
      bars = Nil
      bars = compute()
    }
    // The peak use of each heap pool over the timed runs: the RSS of a JVM follows its collector's policy (a parallel
    // collector with a large heap shows its total allocation), the pools' peaks follow the computation.
    val heapPools = ManagementFactory.getMemoryPoolMXBeans.asScala.filter(_.getType == MemoryType.HEAP).toList
    heapPools.foreach(_.resetPeakUsage())
    val times = (1 to trials).map { _ =>
      bars = Nil
      val t0 = System.nanoTime()
      bars = compute()
      (System.nanoTime() - t0) / 1e9
    }
    opts.get("out").foreach { path =>
      val w = new PrintWriter(path)
      bars.foreach((d, b, e) => w.println(s"$d\t${fmt(b)}\t${fmt(e)}"))
      w.close()
    }
    val counts = bars.groupMapReduce(_._1)(_ => 1)(_ + _).toSeq.sorted
    val rt = Runtime.getRuntime
    // Pools peak at different moments, so the sum bounds the heap in use at once from above; the old generation
    // ("Old Gen", "Tenured Gen") is the closest to the live set of what the computation keeps.
    val heapPeakMb = heapPools.map(_.getPeakUsage.getUsed).sum / 1048576
    val oldPools = heapPools.filter(pool => pool.getName.contains("Old") || pool.getName.contains("Tenured"))
    val oldPeakMb = oldPools.map(_.getPeakUsage.getUsed).sum / 1048576
    println(
      "{" +
        s""""tool":"tda4j","task":"$task","engine":"$engineName","p":$p,"warmup":$warmup,""" +
        s""""load_s":$loadSeconds,"times_s":[${times.mkString(",")}],""" +
        s""""bars":{${counts.map((d, c) => s""""$d":$c""").mkString(",")}},""" +
        s""""heap_used_mb":${(rt.totalMemory - rt.freeMemory) / 1048576},""" +
        s""""heap_peak_mb":$heapPeakMb,"old_peak_mb":$oldPeakMb,""" +
        s""""terms_read":$termsRead,""" +
        (if wantDigest then s""""digest":"$digest",""" else "") +
        s""""java":"${System.getProperty("java.version")}"""" +
        "}"
    )
    if holdSeconds > 0 then
      System.out.flush()
      Thread.sleep(holdSeconds * 1000L)
      println(s"""{"held":${held != null}}""")

  private def fmt(x: Double): String = if x.isPosInfinity then "inf" else x.toString

  /** The failure rate of `HelixDelaunay` over `draws` random clouds: see `task=helix-validity` above. */
  private def helixValidity(opts: Map[String, String]): Unit =
    val d = opts("d").toInt
    val n = opts("n").toInt
    val shape = opts.getOrElse("shape", "interior")
    require(shape == "interior" || shape == "surface", s"shape=$shape (interior|surface)")
    val draws = opts.getOrElse("draws", "1000").toInt
    val seed0 = opts.getOrElse("seed0", "0").toLong
    var invalid, repairThrew, fallbacks = 0
    val start = System.nanoTime()
    for k <- 0 until draws do
      val rng = java.util.SplittableRandom(seed0 + k)
      val pts = Array.fill(n, d)(rng.nextDouble())
      if shape == "surface" then
        for row <- pts do
          val facet = rng.nextInt(2 * d)
          row(facet / 2) = (facet % 2).toDouble
      val helix = HelixDelaunay(pts, seed = seed0 + k)
      val tops = helix.validated
      val bad = HelixDelaunay.badFacetsOf(tops).nonEmpty ||
        HelixDelaunay.interiorVoidVertices(tops, helix.ambientDimension).exists(_.nonEmpty) ||
        !HelixDelaunay.looksValid(tops, helix.points)
      if bad then invalid += 1
      if helix.sphereScanFallbacks > 0 then fallbacks += 1
      try HelixDelaunay(pts, seed = seed0 + k, requireValidTriangulation = true)
      catch case _: RuntimeException => repairThrew += 1
    println(
      s"""{"tool":"tda4j","task":"helix-validity","shape":"$shape","d":$d,"n":$n,"draws":$draws,"invalid":$invalid,""" +
        s""""repair_threw":$repairThrew,"fallback_draws":$fallbacks,"seconds":${(System.nanoTime() - start) / 1e9}}"""
    )

  /** The cells of the complex a task reduces, by dimension: the stream is built as `Persistence` builds it and only
    * iterated, never reduced, so the count is the size of the problem and not of what an engine touches.
    */
  private def countCells(task: String, opts: Map[String, String], dim: Int, threshold: Option[Double]): Unit =
    val start = System.nanoTime()
    val input = opts("input")
    val stream: LevelwiseSimplexStream[Int, Double] = task match
      case "vr" =>
        opts.getOrElse("format", "points") match
          case "points"   => VietorisRips.fromPoints(readMatrix(input), dim, threshold)
          case "distance" =>
            VietorisRips(ExplicitMetricSpace(readMatrix(input).map(_.toSeq).toSeq), dim, threshold)
          case other => throw IllegalArgumentException(s"format=$other (points|distance)")
      case "alpha" =>
        val backend = AlphaBackend.parse(opts.getOrElse("backend", "default"))
        Truncated(AlphaShapes(readMatrix(input), backend, maxRadius = threshold, maxDimension = dim), dim)
      case "cech"                                  => Cech.fromPoints(readMatrix(input), dim, threshold)
      case "sparse" | "dtm" | "witness" | "dowker" => streamFor(task, opts, dim, threshold)
      case other                                   =>
        throw IllegalArgumentException(
          s"count=true is for the tasks vr, alpha, cech, sparse, dtm, witness and dowker, not $other"
        )
    val counts = scala.collection.mutable.TreeMap.empty[Int, Long]
    stream.iterator.foreach(cell => counts.update(cell.dim, counts.getOrElse(cell.dim, 0L) + 1L))
    val cells = counts.map((d, c) => s""""$d":$c""").mkString(",")
    println(
      s"""{"tool":"tda4j","task":"$task","count":true,"cells":{$cells},"seconds":${(System
          .nanoTime() - start) / 1e9},""" +
        s""""java":"${System.getProperty("java.version")}"}"""
    )

  /** The metric space of a `format=points` (default) or `format=distance` input. */
  private def metricSpaceOf(opts: Map[String, String]): FiniteMetricSpace[Int] =
    opts.getOrElse("format", "points") match
      case "points"   => EuclideanMetricSpace(readMatrix(opts("input")))
      case "distance" => ExplicitMetricSpace(readMatrix(opts("input")).map(_.toSeq).toSeq)
      case other      => throw IllegalArgumentException(s"format=$other (points|distance)")

  /** The complex a rarer task builds, as a fresh stream (the caller times it together with the reduction). */
  private def streamFor(
    task: String,
    opts: Map[String, String],
    dim: Int,
    threshold: Option[Double]
  ): LevelwiseSimplexStream[Int, Double] = task match
    case "sparse" => SparseRips(metricSpaceOf(opts), opts("eps").toDouble, dim, maxFiltrationValue = threshold)
    case "dtm"    =>
      DtmRips.fromNeighbours(
        metricSpaceOf(opts),
        opts("k").toInt,
        dim,
        q = opts.getOrElse("q", "2").toDouble,
        p = opts.getOrElse("dtmp", "1").toDouble,
        maxFiltrationValue = threshold
      )
    case "witness" =>
      val ms = metricSpaceOf(opts)
      val landmarks: IndexedSeq[Int] = opts.get("landmarks") match
        case Some(path) =>
          val src = scala.io.Source.fromFile(path)
          try src.getLines().map(_.trim).filter(_.nonEmpty).map(_.toInt).toIndexedSeq
          finally src.close()
        case None => LandmarkSelector.maxmin(ms, opts("L").toInt).landmarks
      val variant = opts.getOrElse("wvariant", "lazy") match
        case "lazy"    => Witness.Variant.Lazy
        case "general" => Witness.Variant.General
        case other     => throw IllegalArgumentException(s"wvariant=$other (lazy|general)")
      Witness(ms, landmarks, dim, variant, opts.getOrElse("nu", "2").toInt, threshold)
    case "dowker" =>
      Dowker(
        readMatrix(opts("input")),
        dim,
        threshold.getOrElse(Double.PositiveInfinity),
        dual = opts.get("dual").contains("true")
      )
    case other => throw IllegalArgumentException(s"no stream for task=$other")

  private def fastAlpha(
    pts: Array[Array[Double]],
    backend: AlphaBackend,
    dim: Int,
    p: Int
  ): List[(Int, Double, Double)] =
    val ff = FiniteField(p)
    import ff.given
    val delaunay = AlphaShapes(pts, backend) match
      case d: DelaunayAlphaShapes => d
      case _                      => throw IllegalArgumentException("engine=fastalpha needs a triangulation, not DQP")
    FastAlphaHomologyEngine[ff.Fp]()
      .persistentHomology(delaunay)
      .filter(_.dim <= dim)
      .map(_.toTriple)

  /** Whitespace- or comma-separated numbers, one row per line; blank lines and `#` comments skipped. */
  def readMatrix(path: String): Array[Array[Double]] =
    val src = scala.io.Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .map(_.split("[,\\s]+").filter(_.nonEmpty).map(_.toDouble))
        .toArray
    finally src.close()

  /** A version 1-3 `.npy` file holding little-endian float64 in C order. */
  def readNpy(path: String): (Array[Double], IndexedSeq[Int]) =
    val in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)))
    try
      val magic = new Array[Byte](6)
      in.readFully(magic)
      require(magic(0) == 0x93.toByte && new String(magic, 1, 5, "ASCII") == "NUMPY", s"$path is not a .npy file")
      val major = in.readUnsignedByte()
      in.readUnsignedByte()
      val headerLength =
        if major == 1 then Integer.reverseBytes(in.readShort() << 16) & 0xffff
        else Integer.reverseBytes(in.readInt())
      val headerBytes = new Array[Byte](headerLength)
      in.readFully(headerBytes)
      val header = new String(headerBytes, "latin1")
      require(header.contains("'<f8'"), s"$path: expected little-endian float64 ('<f8'), header $header")
      require(header.contains("'fortran_order': False"), s"$path: expected C order, header $header")
      val shape = "'shape':\\s*\\(([^)]*)\\)".r
        .findFirstMatchIn(header)
        .map(_.group(1).split(",").map(_.trim).filter(_.nonEmpty).map(_.toInt).toIndexedSeq)
        .getOrElse(throw IllegalArgumentException(s"$path: no shape in header $header"))
      val n = shape.product
      val bytes = new Array[Byte](n * 8)
      in.readFully(bytes)
      val values = new Array[Double](n)
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(values)
      (values, shape)
    finally in.close()
