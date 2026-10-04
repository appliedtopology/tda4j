package org.appliedtopology.tda4j

import java.io.{BufferedInputStream, DataInputStream, FileInputStream, PrintWriter}
import java.nio.{ByteBuffer, ByteOrder}

/** TDA4j's side of the cross-platform benchmark (`bench/`, `.claude/PLAN-paper.md` §3). One computation per JVM: read
  * the input once, run `warmup` untimed and `trials` timed computations through the public `Persistence` verb (or a
  * named engine), print one JSON line with the timings, and write the last barcode as `dim<TAB>birth<TAB>death`.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.PaperBenchmarkDriver task=vr input=sphere.txt format=points dim=2 \
  *   [threshold=1.8] [p=2] [engine=auto|ripser|cohomology|chunks|naive|fastcubical|fastalpha] \
  *   [reps=cycles|cocycles|none] [warmup=2] [trials=5] [out=bars.tsv]
  * }}}
  *
  *   - `task=vr`: points (`format=points`, one point per line) or a full distance matrix (`format=distance`).
  *   - `task=cubical`: a `.npy` array of float64 in C order (any dimension), sublevel filtration, T-construction.
  *   - `task=alpha`: points; filtration values are circumradii. `backend=helix` (default) or `dqp`.
  *   - `task=cech`: points; filtration values are radii (the same barcode as alpha in general position).
  *
  * Times cover the whole `Persistence(...)` call: building the complex, the reduction and the representatives.
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

    def engine: Persistence.Engine = engineName match
      case "auto"        => Persistence.Engine.Auto
      case "ripser"      => Persistence.Engine.Ripser
      case "cohomology"  => Persistence.Engine.Cohomology
      case "chunks"      => Persistence.Engine.Chunks
      case "naive"       => Persistence.Engine.Naive
      case "fastcubical" => Persistence.Engine.FastCubical
      case other         => throw IllegalArgumentException(s"engine=$other not valid for task=$task")

    val loadStart = System.nanoTime()
    val compute: () => List[(Int, Double, Double)] = task match
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
        else () =>
          Persistence(image, maxDimension = dim, characteristic = p, engine = engine, representatives = reps).triples
      case "alpha" =>
        val pts = readMatrix(input)
        val backend = AlphaBackend.parse(opts.getOrElse("backend", "default"))
        if engineName == "fastalpha" then () => fastAlpha(pts, dim, p)
        else if backend == AlphaBackend.DQP then
          () =>
            Persistence(
              Truncated(AlphaShapes(pts, backend), dim),
              characteristic = p,
              engine = engine,
              representatives = reps
            ).triples
        else
          () =>
            Persistence(
              pts,
              maxDimension = dim,
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
      case other => throw IllegalArgumentException(s"task=$other (vr|cubical|alpha|cech)")
    val loadSeconds = (System.nanoTime() - loadStart) / 1e9

    var bars: List[(Int, Double, Double)] = Nil
    (1 to warmup).foreach(_ => bars = compute())
    val times = (1 to trials).map { _ =>
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
    println(
      "{" +
        s""""tool":"tda4j","task":"$task","engine":"$engineName","p":$p,"warmup":$warmup,""" +
        s""""load_s":$loadSeconds,"times_s":[${times.mkString(",")}],""" +
        s""""bars":{${counts.map((d, c) => s""""$d":$c""").mkString(",")}},""" +
        s""""heap_used_mb":${(rt.totalMemory - rt.freeMemory) / 1048576},""" +
        s""""java":"${System.getProperty("java.version")}"""" +
        "}"
    )

  private def fmt(x: Double): String = if x.isPosInfinity then "inf" else x.toString

  private def fastAlpha(pts: Array[Array[Double]], dim: Int, p: Int): List[(Int, Double, Double)] =
    val ff = FiniteField(p)
    import ff.given
    FastAlphaHomologyEngine[ff.Fp]()
      .persistentHomology(HelixDelaunay(pts))
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
