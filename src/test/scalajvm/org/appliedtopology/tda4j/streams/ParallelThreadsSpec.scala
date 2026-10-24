package org.appliedtopology.tda4j

import SpecThrows.throwWith
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.specs2.mutable.Specification

/** `Parallel.withThreads`: the body sees the thread count it asked for, steps called inside share that many threads,
  * results come back in order, and an exception of the body arrives as itself.
  */
class ParallelThreadsSpec extends Specification:
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
