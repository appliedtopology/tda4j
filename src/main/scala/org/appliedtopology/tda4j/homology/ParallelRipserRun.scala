package org.appliedtopology.tda4j

/** What the block-parallel Ripser engine hands to `Persistence`: the bars, and what the representatives are built from.
  * A simplex is named by a `Long` key that orders the simplices of one dimension oldest first (`key(a) < key(b)`: `a`
  * enters the filtration before `b`), so the verb can reduce on keys without the engine's types, and the Scala.js build
  * can compile the verb without the engine (which exists on the JVM only).
  */
// Restricted: a public top-level `ParallelRipserRun` would join every user's `import org.appliedtopology.tda4j.*`.
private[tda4j] trait ParallelRipserRun:
  /** `(degree, birth, death)` of the bars of positive length, sorted; `death` is infinity for an essential class. */
  def bars: IndexedSeq[(Int, Double, Double)]

  /** The cocycle of bar `i` of `bars`: the vertices (ascending) of each simplex of the cochain with its coefficient in
    * `1 until p`. Built when asked for, from the reduction's log; safe to call from several threads.
    */
  def cocycle(i: Int): IndexedSeq[(Array[Int], Int)]

  /** Every pair of the pairing, zero-length ones included: `(dimension, birth key, death key or -1)`. Only when the run
    * was made `withPairs`.
    */
  def pairs: IndexedSeq[(Int, Long, Long)]

  /** The index in `pairs` of the pair that bar `i` of `bars` is. */
  def pairOfBar(i: Int): Int

  /** The vertices (ascending) of the simplex that opens bar `i` of `bars`, and of the one that closes it (none for an
    * essential class).
    */
  def cellsOfBar(i: Int): (Array[Int], Option[Array[Int]])

  /** The keys of the faces of the simplex with key `key` and dimension `dim`; face `i` leaves out vertex `i`. */
  def faces(key: Long, dim: Int): Array[Long]

  /** The vertices (ascending) of the simplex of dimension `dim` with key `key`. */
  def vertices(key: Long, dim: Int): Array[Int]
