package org.appliedtopology.tda4j

import org.specs2.mutable.Specification
import scala.jdk.CollectionConverters.ListHasAsScala

/** The benchmark driver's V-construction input (`construction=V`): an array of vertex values is run as the
  * T-construction image that gives every cell the largest value of its vertices. The refinement is checked cell by cell
  * against the definition, and the barcode on a vertex array where the V and the T construction disagree.
  */
class PaperBenchmarkDriverSpec extends Specification:
  private val refine = PaperBenchmarkDriver.refineVertexValues

  // The definition: cell (i_1, .., i_d) of the (2 n_k - 1)-grid has the vertices i_k / 2 on the axes where i_k is even
  // and i_k / 2, i_k / 2 + 1 where it is odd; its value is the largest value among them.
  private def definition(values: Array[Double], shape: IndexedSeq[Int]): Array[Double] =
    val refinedShape = shape.map(2 * _ - 1)
    Array.tabulate(refinedShape.product) { flat =>
      val index = refinedShape.indices.map(axis => (flat / refinedShape.drop(axis + 1).product) % refinedShape(axis))
      val vertices = index
        .map(i => if i % 2 == 0 then List(i / 2) else List(i / 2, i / 2 + 1))
        .foldLeft(List(List.empty[Int]))((prefixes, choices) => for prefix <- prefixes; c <- choices yield prefix :+ c)
      vertices.map(v => values(v.zip(shape).foldLeft(0) { case (flatIndex, (i, n)) => flatIndex * n + i })).max
    }

  private def randomValues(shape: IndexedSeq[Int], seed: Long): Array[Double] =
    val rng = scala.util.Random(seed)
    Array.fill(shape.product)(rng.nextInt(7).toDouble) // ties on purpose

  private def bars(values: Array[Double], shape: IndexedSeq[Int], dim: Int) =
    Persistence(Image(values.toIndexedSeq, shape), maxDimension = dim, characteristic = 2).triples.sorted

  "refineVertexValues" should {
    "give every cell the largest value of its vertices, in 1, 2 and 3 dimensions" in {
      val shapes = List(IndexedSeq(5), IndexedSeq(3, 4), IndexedSeq(4, 1), IndexedSeq(2, 3, 2), IndexedSeq(3, 3, 3))
      val wrong = for
        shape <- shapes
        seed <- 1L to 4L
        values = randomValues(shape, seed)
        (refined, refinedShape) = refine(values, shape)
        if refinedShape != shape.map(2 * _ - 1) || !java.util.Arrays.equals(refined, definition(values, shape))
      yield s"shape $shape seed $seed"
      wrong must beEmpty
    }
    "keep the vertex values where every index is even" in {
      val values = randomValues(IndexedSeq(4, 5), 11L)
      val (refined, shape) = refine(values, IndexedSeq(4, 5))
      val kept = for i <- 0 until 4; j <- 0 until 5 yield refined(2 * i * shape(1) + 2 * j) == values(i * 5 + j)
      kept.forall(identity) must beTrue
    }
  }

  "the V-construction through the refinement" should {
    // Two low vertices on a diagonal of one square. Each is a component at 0; the edges of the square all contain a
    // vertex of value 5, so they are born at 5, and the two components join there. As top cells of a T-construction
    // image the same four values touch at their common corner, which is born at 0: one component, no finite bar.
    val checkerboard = Array(0.0, 5.0, 5.0, 0.0)
    val shape = IndexedSeq(2, 2)

    "see the two components of the checkerboard that the T-construction joins" in {
      val (refined, refinedShape) = refine(checkerboard, shape)
      bars(refined, refinedShape, 1) === List((0, 0.0, 5.0), (0, 0.0, Double.PositiveInfinity))
      bars(checkerboard, shape, 1) === List((0, 0.0, Double.PositiveInfinity))
    }
    // A ring of low vertices around a high one: the loop of the ring is born at 0 and filled at 5, when the squares
    // around the central vertex appear.
    "see the loop around a high vertex, born with the ring and filled with the squares that contain the vertex" in {
      val ring = Array(0.0, 0.0, 0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0)
      val (refined, refinedShape) = refine(ring, IndexedSeq(3, 3))
      bars(refined, refinedShape, 1) === List((0, 0.0, Double.PositiveInfinity), (1, 0.0, 5.0))
    }
  }

/** `facade=true`: the driver makes the call the Scala.js worker makes, through `matlab.TDA4j`, with the option strings
  * the worker's options object becomes. Two things are checked: that the option strings are the worker's (the
  * discriminating failure is a facade run that quietly does other work than the JavaScript one it is compared with),
  * and that on every task the facade run reports the bars the verb run reports, with the representatives read.
  */
class PaperBenchmarkDriverFacadeSpec extends Specification:
  sequential

  private def options(
    task: String = "vr",
    engine: String = "auto",
    reps: String = "cycles",
    threshold: Option[String] = None,
    backend: String = "default"
  ) = PaperBenchmarkDriver.facadeOptions(task, 2, 17, threshold, engine, reps, backend).toList

  private val base = List("maxDimension", "2", "prime", "17", "minPersistence", "0")

  "facadeOptions" should {
    "pass the dimension, the prime and a zero persistence threshold, and nothing else, for the defaults" in
      options() === base
    "name an engine the way the facade does, and only when it is not auto" in
      (
        options(engine = "fastcubical", task = "cubical"),
        options(engine = "fastalpha", task = "alpha"),
        options(engine = "cohomology")
      ) === (
        (base ++ List("engine", "fast-cubical")),
        (base ++ List("engine", "fast-alpha", "complex", "alpha")),
        (base ++ List("engine", "cohomology"))
      )
    "ask for cocycles, the threshold, the complex and the alpha backend where the worker does" in {
      options(task = "alpha", reps = "cocycles", threshold = Some("1.8"), backend = "dqp") ===
        base ++ List(
          "representativeType",
          "cocycles",
          "maxFiltrationValue",
          "1.8",
          "complex",
          "alpha",
          "alphaBackend",
          "dqp"
        )
      options(task = "cech") === base ++ List("complex", "cech")
    }
    "refuse what the JavaScript API does not offer" in {
      val refused = List(
        () => options(reps = "none"),
        () => options(engine = "parallel"),
        () => options(engine = "fastalpha-top", task = "alpha"),
        () => options(engine = "fastalpha", task = "alpha", backend = "dqp"),
        () => options(engine = "fastalpha", task = "alpha", threshold = Some("1.0")),
        () => options(task = "witness")
      ).map(call => scala.util.Try(call()).isFailure)
      refused === List.fill(6)(true)
    }
  }

  // The driver on a file, one JSON line out: what the harness reads.
  private def tempFile(name: String, write: java.io.File => Unit): java.io.File =
    val file = java.io.File.createTempFile("facade-spec-", name)
    file.deleteOnExit()
    write(file)
    file

  private def writeText(lines: Seq[String])(file: java.io.File): Unit =
    java.nio.file.Files.write(file.toPath, lines.mkString("\n").getBytes("UTF-8"))

  /** A version 1 `.npy` file: float64, little-endian, C order. */
  private def writeNpy(values: Array[Double], shape: Seq[Int])(file: java.io.File): Unit =
    val shapeText = if shape.size == 1 then s"(${shape.head},)" else shape.mkString("(", ", ", ")")
    val body = s"{'descr': '<f8', 'fortran_order': False, 'shape': $shapeText, }"
    val padded = body + " " * ((64 - (10 + body.length + 1) % 64) % 64) + "\n"
    val buffer =
      java.nio.ByteBuffer.allocate(10 + padded.length + 8 * values.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put(0x93.toByte).put("NUMPY".getBytes("ASCII")).put(1.toByte).put(0.toByte).putShort(padded.length.toShort)
    buffer.put(padded.getBytes("ASCII"))
    values.foreach(buffer.putDouble)
    java.nio.file.Files.write(file.toPath, buffer.array())

  private case class Run(bars: List[String], json: String)

  private def run(input: java.io.File, args: String*): Run =
    val out = java.io.File.createTempFile("facade-spec-", ".tsv")
    out.deleteOnExit()
    val captured = new java.io.ByteArrayOutputStream
    Console.withOut(captured) {
      PaperBenchmarkDriver.main(
        Array(s"input=${input.getPath}", s"out=${out.getPath}", "warmup=0", "trials=1", "sequential=true") ++ args
      )
    }
    val bars = java.nio.file.Files.readAllLines(out.toPath).asScala.toList.sorted
    Run(bars, captured.toString("UTF-8").linesIterator.next())

  private def field(json: String, key: String): String =
    s""""$key":(\\{[^}]*\\}|[^,}]*)""".r.findFirstMatchIn(json).map(_.group(1)).getOrElse("")

  // Twelve points on a circle of radius 1, then three noise points: a loop at scale about 0.5 and some small bars.
  private val circle: Seq[String] =
    (0 until 12).map(k => s"${math.cos(2 * math.Pi * k / 12)} ${math.sin(2 * math.Pi * k / 12)}") ++
      Seq("0.13 0.07", "-0.4 0.21", "0.3 -0.62")

  /** What differs between a verb run and a facade run of the same task on `input`: the barcode, the counts the JSON
    * line reports, the facade marker. Empty if nothing does, and the verb's barcode has bars in it (an empty one would
    * agree with anything).
    */
  private def differences(task: String, input: java.io.File, args: String*): List[String] =
    val verb = run(input, ("task=" + task) +: "dim=2" +: args*)
    val facade = run(input, ("task=" + task) +: "dim=2" +: "facade=true" +: args*)
    List(
      Option.when(verb.bars.isEmpty)(s"$task ${args.mkString(" ")}: the verb run has no bars"),
      Option.when(facade.bars != verb.bars)(s"$task ${args.mkString(" ")}: bars ${facade.bars} against ${verb.bars}"),
      Option.when(field(facade.json, "bars") != field(verb.json, "bars"))(
        s"$task ${args.mkString(" ")}: counts ${field(facade.json, "bars")} against ${field(verb.json, "bars")}"
      ),
      Option.when(field(facade.json, "facade") != "true")(s"$task: the facade line does not say facade"),
      Option.when(field(verb.json, "facade") != "")(s"$task: the verb line says facade")
    ).flatten

  "a facade run" should {
    "report the bars of the verb run on points, with and without a threshold, with cocycles and over F_2" in {
      val input = tempFile(".txt", writeText(circle))
      val wrong = List(
        differences("vr", input, "format=points"),
        differences("vr", input, "format=points", "threshold=0.7"),
        differences("vr", input, "format=points", "reps=cocycles", "engine=cohomology"),
        differences("vr", input, "format=points", "p=2")
      ).flatten
      wrong must beEmpty
    }
    "report the bars of the verb run on a distance matrix" in {
      val points = circle.map(_.split(" ").map(_.toDouble))
      val matrix = points.map(a => points.map(b => math.hypot(a(0) - b(0), a(1) - b(1))).mkString(" "))
      val input = tempFile(".txt", writeText(matrix))
      differences("vr", input, "format=distance") must beEmpty
    }
    "report the bars of the verb run on alpha and Cech complexes" in {
      val input = tempFile(".txt", writeText(circle))
      val wrong = List(
        differences("alpha", input),
        differences("alpha", input, "backend=bowyer-watson", "reps=cocycles"),
        differences("cech", input)
      ).flatten
      wrong must beEmpty
    }
    "report the bars and the representatives of the verb run on a cubical image, whichever the engine" in {
      // A 6 x 6 image: a frame of high values, a block of middle ones inside, low ones between: loops that are born
      // and die at different values. The frame makes the scale 400, so the default persistence threshold (1% of it)
      // would hide the short bars: the representatives are read for every bar only because `minPersistence` is 0.
      val values = Array.tabulate(36) { k =>
        val (i, j) = (k / 6, k % 6)
        if i == 0 || j == 0 || i == 5 || j == 5 then 400.0
        else if i >= 2 && i <= 3 && j >= 2 && j <= 3 then 2.0
        else ((i * 7 + j * 3) % 3).toDouble
      }
      val input = tempFile(".npy", writeNpy(values, Seq(6, 6)))
      val wrong = for
        engine <- List(Nil, List("engine=fastcubical"), List("engine=cohomology", "reps=cocycles"))
        verb = run(input, List("task=cubical", "dim=1", "p=2", "read=true") ++ engine*)
        facade = run(input, List("task=cubical", "dim=1", "p=2", "read=true", "facade=true") ++ engine*)
        problem <- List(
          Option.when(verb.bars.isEmpty)(s"$engine: the verb run has no bars"),
          Option.when(facade.bars != verb.bars)(s"$engine: bars ${facade.bars} against ${verb.bars}"),
          Option.when(field(facade.json, "bars") != field(verb.json, "bars"))(s"$engine: counts differ"),
          Option.when(field(facade.json, "terms_read").toLong <= 0L)(s"$engine: no representative terms were read"),
          Option.when(field(facade.json, "terms_read") != field(verb.json, "terms_read"))(
            s"$engine: terms read ${field(facade.json, "terms_read")} against ${field(verb.json, "terms_read")}"
          )
        ).flatten
      yield problem
      wrong must beEmpty
    }
  }
