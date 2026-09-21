package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}

import scala.io.Source
import java.io.PrintWriter

/** Ripser's own input file formats (`ripser.cpp`'s `read_point_cloud`/`read_lower_distance_matrix`/
  * `read_upper_distance_matrix`/`read_distance_matrix`/`read_binary`) -- verified directly against
  * `github.com/Ripser/ripser`'s own source (see `.claude/WORKLOG-io-module.md`), not reconstructed from
  * documentation: a wrong flat-index convention here would silently produce a plausible-looking wrong matrix rather
  * than an error.
  *
  * '''Not implemented''': `--format sparse` (a triplet edge list -- tda4j has no "only these edges are known, the
  * rest are unknown rather than infinite" metric-space type, so this is a real, open design question, not an
  * oversight -- see the worklog) and `--format dipha` (use `Dipha.scala` directly instead, which reads the exact
  * same file DIPHA itself produces).
  */
object Ripser:
  private val delimiterRegex = "[\\s,]+"

  /** One point per line, whitespace- or comma-separated coordinates -- Ripser's default `--format point-cloud`. */
  def readPointCloud(path: String): Array[Array[Double]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_.split(delimiterRegex).map(_.toDouble))
        .toArray
    finally src.close()

  def readEuclideanMetricSpace(path: String): EuclideanMetricSpace =
    EuclideanMetricSpace(readPointCloud(path))

  def writePointCloud(path: String, points: Seq[Seq[Double]]): Unit =
    val out = new PrintWriter(path)
    try points.foreach(p => out.println(p.mkString(",")))
    finally out.close()

  private def readFlatValues(path: String): IndexedSeq[Double] =
    val src = Source.fromFile(path)
    try src.getLines().mkString(" ").split(delimiterRegex).filter(_.nonEmpty).map(_.toDouble).toIndexedSeq
    finally src.close()

  /** `--format lower-distance`: every value in the file (across line breaks; comma- or whitespace-separated) is one
    * flat token stream: row `i` (`i = 1 until n`) contributes `i` entries `d(i,0),...,d(i,i-1)`, rows concatenated
    * in order -- confirmed against `read_lower_distance_matrix`/`compressed_lower_distance_matrix`'s `init_rows` in
    * `ripser.cpp` directly.
    */
  def readLowerDistanceMatrix(path: String): Array[Array[Double]] =
    val flat = readFlatValues(path)
    DistanceMatrices.expandLowerTriangular(flat, DistanceMatrices.sizeFromTriangularCount(flat.size))

  def readLowerDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readLowerDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeLowerDistanceMatrix(path: String, matrix: IndexedSeq[IndexedSeq[Double]]): Unit =
    val out = new PrintWriter(path)
    try out.println(DistanceMatrices.flattenLowerTriangular(matrix).mkString(","))
    finally out.close()

  /** `--format upper-distance`: row `i` (`i = 0 until n-1`) contributes `n-1-i` entries `d(i,i+1),...,d(i,n-1)`,
    * rows concatenated -- confirmed against `read_upper_distance_matrix`/`compressed_upper_distance_matrix`'s
    * `init_rows` pointer arithmetic in `ripser.cpp` directly, traced term-by-term rather than assumed symmetric with
    * the lower case (see `.claude/WORKLOG-io-module.md`).
    */
  def readUpperDistanceMatrix(path: String): Array[Array[Double]] =
    val flat = readFlatValues(path)
    DistanceMatrices.expandUpperTriangular(flat, DistanceMatrices.sizeFromTriangularCount(flat.size))

  def readUpperDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readUpperDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeUpperDistanceMatrix(path: String, matrix: IndexedSeq[IndexedSeq[Double]]): Unit =
    val out = new PrintWriter(path)
    try out.println(DistanceMatrices.flattenUpperTriangular(matrix).mkString(","))
    finally out.close()

  /** `--format distance` (Ripser's own default): a dense `n x n` matrix, one row per line, but ONLY the strictly
    * lower triangle of each line is actually read (`for (j = 0; j < i && s >> value; ++j)`) -- so line `i`
    * (0-indexed) must have at least `i` values, and anything at or past the diagonal is ignored outright, never
    * cross-checked against the lower triangle.
    */
  def readDistanceMatrix(path: String): Array[Array[Double]] =
    val src = Source.fromFile(path)
    try
      val lines = src.getLines().toIndexedSeq
      val n = lines.size
      val flat = lines.zipWithIndex.flatMap { case (line, i) =>
        line.trim.split(delimiterRegex).filter(_.nonEmpty).take(i)
      }.map(_.toDouble)
      DistanceMatrices.expandLowerTriangular(flat, n)
    finally src.close()

  def readDistanceExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  /** `--format binary`: no header at all, just raw little-endian IEEE-754 `float32` values read until EOF, in the
    * same flat lower-triangular order as `readLowerDistanceMatrix` -- Ripser's own `value_t` is `typedef float
    * value_t`, NOT `double` (confirmed directly against `ripser.cpp`'s own typedef), so every value narrows to
    * 32 bits on write and widens back to `Double` on read; this is real precision loss, matching what real
    * `ripser --format binary` output already has baked in, not something this reader introduces.
    */
  def readBinaryLowerDistanceMatrix(path: String): Array[Array[Double]] =
    val buf = BinaryIO.readAllLE(path)
    val count = buf.remaining() / 4
    val flat = new Array[Double](count)
    var i = 0
    while i < count do
      flat(i) = buf.getFloat().toDouble
      i += 1
    DistanceMatrices.expandLowerTriangular(flat.toIndexedSeq, DistanceMatrices.sizeFromTriangularCount(count))

  def readBinaryExplicitMetricSpace(path: String): ExplicitMetricSpace =
    ExplicitMetricSpace(readBinaryLowerDistanceMatrix(path).map(_.toIndexedSeq).toIndexedSeq)

  def writeBinaryLowerDistanceMatrix(path: String, matrix: IndexedSeq[IndexedSeq[Double]]): Unit =
    val flat = DistanceMatrices.flattenLowerTriangular(matrix)
    val buf = BinaryIO.newBufferLE(flat.length * 4)
    flat.foreach(v => buf.putFloat(v.toFloat))
    BinaryIO.writeLE(path, buf)
