package org.appliedtopology.tda4j

import SpecThrows.throwWith
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import org.specs2.mutable.Specification
import scala.jdk.CollectionConverters.*

/** What a JVM started with other flags counts: `ParallelThreadsSpec` starts one for each case. The processors and the
  * common pool's size are fixed when a JVM starts, so no spec can vary them in its own JVM.
  */
object ParallelThreadsProbe:
  def main(args: Array[String]): Unit = println(s"threads=${Parallel.threads}")

/** `Parallel.threads` and `Parallel.withThreads`: the count is the threads that can work at once, on a machine with one
  * processor too; the body of `withThreads` sees the count it asked for, steps called inside share that many threads,
  * results come back in order, and an exception of the body arrives as itself.
  */
class ParallelThreadsSpec extends Specification:
  // The classes of this JVM, for a second one. sbt runs specs inside its own JVM, where `java.class.path` is sbt's:
  // the class loaders of this spec hold the library's and the specs' classes. A runner that starts specs with a plain
  // class path has them in `java.class.path`.
  private def classPath: String =
    def held(loader: ClassLoader): List[String] = loader match
      case null                 => Nil
      case urls: URLClassLoader => urls.getURLs.toList.map(url => Paths.get(url.toURI).toString) ++ held(urls.getParent)
      case other                => held(other.getParent)
    (held(getClass.getClassLoader) ++ sys.props("java.class.path").split(File.pathSeparator)).distinct
      .mkString(File.pathSeparator)

  /** Starts a JVM with `flags` and returns what `ParallelThreadsProbe` printed there: `threads=N`, or everything the
    * JVM said when that line is missing.
    */
  private def started(flags: String*): () => String =
    val java = Paths.get(sys.props("java.home"), "bin", "java").toString
    val command = (java +: flags) ++ Seq("-cp", classPath, "org.appliedtopology.tda4j.ParallelThreadsProbe")
    val process = ProcessBuilder(command*).redirectErrorStream(true).start()
    () =>
      val said = String(process.getInputStream.readAllBytes())
      process.waitFor(60, TimeUnit.SECONDS)
      said.linesIterator.find(_.startsWith("threads=")).getOrElse(said)

  private val pool = "-Djava.util.concurrent.ForkJoinPool.common.parallelism"

  "Parallel.threads" should
    "be the processors of the machine, and what the common pool's size asks for when it was given" >> {
      // Four JVMs, started together.
      val oneProcessor = started("-XX:ActiveProcessorCount=1")
      val fourProcessors = started("-XX:ActiveProcessorCount=4")
      val noWorkers = started("-XX:ActiveProcessorCount=4", s"$pool=0")
      val threeWorkersOnOne = started("-XX:ActiveProcessorCount=1", s"$pool=3")
      // The JVM gives its common pool one worker even on one processor: two threads that take turns.
      oneProcessor() === "threads=1"
      fourProcessors() === "threads=4"
      // A pool with no workers reports a size of 1, as a pool with one worker does.
      noWorkers() === "threads=1"
      // Asked for by name: three workers and the caller, whatever the machine has.
      threeWorkersOnOne() === "threads=4"
    }

  "Parallel.commonThreads" should
    "count the pool's workers and the caller, within the processors unless the pool's size was asked for" >> {
      def count(reported: Int, property: Option[String], processors: Int): Int =
        Parallel.commonThreads(reported, Parallel.presetWorkers(property), processors)
      // Nobody sized the pool: the JVM gives it the processors less one, and one worker at the least.
      count(reported = 1, property = None, processors = 1) === 1
      count(reported = 1, property = None, processors = 2) === 2
      count(reported = 7, property = None, processors = 8) === 8
      // Made smaller since (`ForkJoinPool.setParallelism`).
      count(reported = 3, property = None, processors = 16) === 4
      // Sized by the property. A pool of 0 and a pool of 1 both report 1.
      count(reported = 1, property = Some("0"), processors = 8) === 1
      count(reported = 1, property = Some("1"), processors = 8) === 2
      count(reported = 3, property = Some("3"), processors = 1) === 4
      // As the JDK reads the property: below 0 is 0, and not a number is not set.
      count(reported = 1, property = Some("-3"), processors = 8) === 1
      count(reported = 7, property = Some("many"), processors = 8) === 8
      count(reported = 1, property = Some("many"), processors = 1) === 1
    }

  "Parallel.sequentially" should
    "run every kind of step on the calling thread, a range in one piece" >> {
      val caller = Thread.currentThread()
      val elsewhere = ConcurrentLinkedQueue[String]()
      def here(step: String): Unit = if Thread.currentThread() ne caller then elsewhere.add(step)
      val pieces = ConcurrentLinkedQueue[(Int, Int)]()
      Parallel.sequentially {
        Parallel.foreach(0 until 64)(_ => here("foreach"))
        Parallel.map(0 until 64)(_ => here("map"))
        Parallel.tasks(64)(_ => here("tasks"))
        Parallel.both(here("both"), here("both"))
        Parallel.ranges(1 << 16, 1) { (from, until) =>
          here("ranges")
          pieces.add((from, until))
          Thread.sleep(20) // long enough for another thread to take the next piece, were there one
        }
      }
      elsewhere.asScala.toList.distinct must beEmpty
      pieces.asScala.toList === List((0, 1 << 16))
    }

  "Parallel.withThreads" should {
    "report the threads it was given, and fall back to the default outside" >> {
      Parallel.withThreads(3)(Parallel.threads) === 3
      Parallel.withThreads(1)(Parallel.threads) === 1
      Parallel.withThreads(6)(Parallel.limit) === Some(6)
      Parallel.sequentially(Parallel.limit) === Some(1)
      Parallel.limit === None
    }
    "override sequentially for a larger count, and nest without a new pool for the same one" >> {
      Parallel.sequentially(Parallel.withThreads(4)(Parallel.threads)) === 4
      Parallel.withThreads(3) {
        val outer = Thread.currentThread()
        Parallel.withThreads(3)(Thread.currentThread() eq outer)
      } must beTrue
    }
    "carry the count into work done later" >> {
      val n = Parallel.withThreads(3)(Parallel.limit)
      Parallel.limited(n)(Parallel.threads) === 3
      Parallel.limited(None)(Parallel.threads) === Parallel.threads
    }
    "share work between threads: two items that wait for each other finish on two threads" >> {
      val latch = CountDownLatch(2)
      Parallel.withThreads(2) {
        Parallel.foreach(Vector(0, 1)) { _ =>
          latch.countDown()
          latch.await(20, TimeUnit.SECONDS)
        }
      }
      latch.getCount === 0L
    }
    "keep the order of map's results" >> {
      val xs = (0 until 5000).toVector
      Parallel.withThreads(4)(Parallel.map(xs)(_ * 2)) === xs.map(_ * 2)
    }
    "give an exception of the body as itself" >> {
      def fails: Int = throw IllegalStateException("boom")
      Parallel.withThreads(2)(fails) must throwWith[IllegalStateException]("boom")
    }
    "refuse fewer than one thread" >> {
      Parallel.withThreads(0)(1) must throwWith[IllegalArgumentException]("at least 1")
    }
  }
