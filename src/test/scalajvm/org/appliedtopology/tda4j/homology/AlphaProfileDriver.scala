package org.appliedtopology.tda4j

import scala.collection.mutable

/** Where a Delaunay alpha complex spends its time: one input, each phase timed once in a fresh JVM, the faces counted
  * per dimension, optionally a poor man's stack sampler on the main thread.
  *
  * {{{
  * java -cp $CP org.appliedtopology.tda4j.AlphaProfileDriver points.txt [backend=helix|bowyer-watson|dqp] [sample=true] [dim=2] [dump=file] [verb=only] [sequential=true]
  * }}}
  *
  * Phases: the triangulation (`HelixDelaunay` / `BowyerWatsonDelaunay` constructor: the walk and its validity check),
  * `simplicesMap` (every face of every dimension and its alpha value, on flat arrays, then as `Simplex` objects), the
  * sorted map, and the reduction of the stream cut at `dim + 1` (the cohomology engine, as the verb picks it).
  */
object AlphaProfileDriver:
  given Epsilon = Epsilon(1e-5)

  private def timed[A](label: String)(body: => A): A =
    val start = System.nanoTime()
    val result = body
    println(f"  $label%-34s ${(System.nanoTime() - start) / 1e9}%9.3f s")
    result

  /** Samples `target`'s stack every few milliseconds: the self frame, and the frame closest to the top inside tda4j. */
  private class Sampler(target: Thread) extends Thread:
    setDaemon(true)
    @volatile var running = true
    val self: mutable.HashMap[String, Int] = mutable.HashMap.empty
    val owner: mutable.HashMap[String, Int] = mutable.HashMap.empty
    var samples = 0
    override def run(): Unit =
      while running do
        Thread.sleep(4)
        val stack = target.getStackTrace
        if stack.nonEmpty then
          samples += 1
          def name(e: StackTraceElement) =
            s"${e.getClassName.stripPrefix("org.appliedtopology.tda4j.")}.${e.getMethodName}"
          self.updateWith(name(stack.head))(c => Some(c.getOrElse(0) + 1))
          stack.find(_.getClassName.startsWith("org.appliedtopology.tda4j")).foreach { e =>
            owner.updateWith(s"${name(e)}:${e.getLineNumber}")(c => Some(c.getOrElse(0) + 1))
          }
    def report(title: String, counts: mutable.HashMap[String, Int], top: Int = 14): Unit =
      println(s"  -- $title ($samples samples)")
      counts.toSeq.sortBy(-_._2).take(top).foreach((k, v) => println(f"     ${100.0 * v / samples}%5.1f%%  $k"))

  def main(args: Array[String]): Unit =
    val opts = args.drop(1).map(a => a.takeWhile(_ != '=') -> a.dropWhile(_ != '=').drop(1)).toMap
    // `sequential=true`: every parallel step of the library runs on this thread (the reference for the parallel run).
    if opts.get("sequential").contains("true") then Parallel.sequentially(run(args(0), opts)) else run(args(0), opts)

  private def run(input: String, opts: Map[String, String]): Unit =
    val dim = opts.getOrElse("dim", "2").toInt
    val pts = PaperBenchmarkDriver.readMatrix(input)
    val ambient = pts.head.length
    println(s"${pts.length} points in R^$ambient, ${opts.getOrElse("backend", "helix")}")
    if opts.get("backend").contains("dqp") then
      // The dual quadratic program has no triangulation: time the simplices and their values, then the reduction.
      val stream = timed("dqp complex up to dimension " + (dim + 1)) {
        val s = AlphaShapes.upToEnclosingRadius(pts, AlphaBackend.DQP, maxDimension = dim)
        val counts = (0 to dim + 1).map(k => s.iterateDimension(k).size)
        println("  simplices per dimension: " + counts.zipWithIndex.map((c, k) => s"$k:$c").mkString(" "))
        s
      }
      val bars =
        timed(s"Persistence verb, degree $dim")(Persistence(Truncated(stream, dim), characteristic = 17).bars.size)
      println(s"  $bars bars")
      // The same dump as the triangulation backends: every simplex with its value in full precision, to diff builds.
      opts.get("dump").foreach { path =>
        val lines = (0 to dim + 1).flatMap(k =>
          stream
            .iterateDimension(k)
            .map(s => s.toSeq.mkString(",") + " " + java.lang.Double.toHexString(stream.filtrationValue(s)))
        )
        java.nio.file.Files.write(java.nio.file.Paths.get(path), lines.sorted.mkString("\n").getBytes("UTF-8"))
      }
      return
    val sampler = Option.when(opts.get("sample").contains("true"))(new Sampler(Thread.currentThread()))
    sampler.foreach(_.start())
    val total = System.nanoTime()
    val delaunay: DelaunayAlphaShapes = timed("triangulation") {
      opts.getOrElse("backend", "helix") match
        case "bowyer-watson" => BowyerWatsonDelaunay(pts)
        case _               => HelixDelaunay(pts)
    }
    val tops = delaunay match
      case h: HelixDelaunay => h.validated.size
      case _                => -1
    delaunay match
      case h: HelixDelaunay if opts.contains("claimants") =>
        val claimed = h.validated.groupBy(_.simplex).filter(_._2.size > 1)
        println(
          s"  simplices claimed by several cells: ${claimed.size}, cells larger than top: ${h.validated.count(_.simplex.size > h.ambientDimension + 1)}"
        )
      case _ => ()
    // `verb=only` skips the all-dimensions phases: the verb reads the stream up to `dim + 1` only, as a user's call does.
    if !opts.get("verb").contains("only") then
      val map = timed("faces + alpha values (simplicesMap)")(delaunay.simplicesMap)
      println("  simplices per dimension: " + map.toSeq.sortBy(_._1).map((k, v) => s"$k:${v.size}").mkString(" "))
      println(s"  top simplices (Delaunay cells): $tops, all simplices: ${map.values.map(_.size.toLong).sum}")
    opts.get("dump").foreach { path =>
      val map = delaunay.simplicesMap
      // Every simplex with its value in full precision, in a canonical order: two builds of the same complex diff to nothing.
      val lines = map.toSeq
        .sortBy(_._1)
        .flatMap((_, simplices) =>
          simplices.map(s => s.toSeq.mkString(",") + " " + java.lang.Double.toHexString(delaunay.filtrationValue(s)))
        )
      java.nio.file.Files.write(java.nio.file.Paths.get(path), lines.sorted.mkString("\n").getBytes("UTF-8"))
      // The emission order of the stream, which the engines rely on.
      val order = delaunay.simplicesSortedMap.toSeq
        .sortBy(_._1)
        .flatMap((k, simplices) => simplices.map(s => s"$k ${s.toSeq.mkString(",")}"))
      java.nio.file.Files.write(java.nio.file.Paths.get(path + ".order"), order.mkString("\n").getBytes("UTF-8"))
    }
    if !opts.get("verb").contains("only") then timed("sort every dimension")(delaunay.simplicesSortedMap)
    val bars =
      timed(s"Persistence verb, degree $dim")(Persistence(Truncated(delaunay, dim), characteristic = 17).bars.size)
    println(f"  total ${(System.nanoTime() - total) / 1e9}%.3f s, $bars bars")
    sampler.foreach { s =>
      s.running = false
      s.report("self frame", s.self)
      s.report("topmost tda4j frame", s.owner, 22)
    }
