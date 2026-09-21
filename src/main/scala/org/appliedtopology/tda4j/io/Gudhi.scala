package org.appliedtopology.tda4j
package io

import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.{given, *}

import scala.io.Source
import java.io.PrintWriter

/** GUDHI's own OFF/nOFF point-cloud format and `.pers` persistence-diagram format -- verified against
  * `gudhi.inria.fr`'s own file-format documentation (see `.claude/WORKLOG-io-module.md`). GUDHI's cubical-complex
  * module also reads Perseus's cubical toplex format directly, byte-for-byte the same as real Perseus (confirmed
  * against GUDHI's own `Bitmap_cubical_complex_base.h` reader source) -- see `Perseus.readCubicalToplex` for that, not
  * duplicated here.
  */
object Gudhi:

  /** `OFF`/`nOFF` point clouds (Geomview's OFF format): a header line (`OFF` for 3 dimensions, `<dim>OFF` for any other
    * dimension), then a `[dim] vertices faces edges` count line, then one vertex per line. `#`-prefixed lines are
    * comments, skipped anywhere. Faces/edges are never read -- GUDHI's own reader doesn't use them for a point set
    * either.
    */
  def readOff(path: String): Array[Array[Double]] =
    val src = Source.fromFile(path)
    try
      val lines = src.getLines().map(_.trim).filterNot(l => l.isEmpty || l.startsWith("#")).toIndexedSeq
      val header = lines.head.toUpperCase
      val dim =
        if header == "OFF" then 3
        else
          val m = "^([0-9]+)OFF$".r.findFirstMatchIn(header)
          require(m.isDefined, s"unrecognized OFF header: '${lines.head}'")
          m.get.group(1).toInt
      val nVertices = lines(1).split("\\s+")(0).toInt
      lines.slice(2, 2 + nVertices).map(_.split("\\s+").take(dim).map(_.toDouble)).toArray
    finally src.close()

  def readEuclideanMetricSpace(path: String): EuclideanMetricSpace =
    EuclideanMetricSpace(readOff(path))

  /** Writes an `OFF` (3D) or `nOFF` (any other dimension) point cloud, no faces/edges. */
  def writeOff(path: String, points: Seq[Seq[Double]]): Unit =
    require(points.nonEmpty, "cannot write an OFF file for an empty point cloud")
    val dim = points.head.size
    require(points.forall(_.size == dim), "every point must have the same dimension")
    val out = new PrintWriter(path)
    try
      out.println(if dim == 3 then "OFF" else s"${dim}OFF")
      out.println(s"${points.size} 0 0")
      points.foreach(p => out.println(p.mkString(" ")))
    finally out.close()

  /** GUDHI's `.pers` diagram format: `#`-prefixed comment lines are ignored; every other line has 2, 3, or 4
    * whitespace-separated fields, `[[field] dimension] birth death`. This reads the LAST two fields as birth/death
    * always, and: 4 fields -> `dimension` is the second field (the first, the coefficient-field characteristic, is not
    * represented in [[PersistenceBar]] and is discarded); 3 fields -> the first field is `dimension`; 2 fields ->
    * `dimension` defaults to `0`. `inf`/`-inf` (any case) are infinite endpoints.
    */
  def readPersistenceDiagram(path: String): Seq[PersistenceBar[Double, Nothing]] =
    val src = Source.fromFile(path)
    try
      src
        .getLines()
        .map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .map { line =>
          val fields = line.split("\\s+")
          val dim = fields.length match
            case 2 => 0
            case 3 => fields(0).toInt
            case 4 => fields(1).toInt
            case n => throw new IllegalArgumentException(s"expected 2-4 fields, got $n in line '$line'")
          Endpoints.toBar(dim, fields(fields.length - 2), fields(fields.length - 1))
        }
        .toSeq
    finally src.close()

  /** Writes a 3-column `dim birth death` `.pers` file, or 4-column `field dim birth death` when `field` is given. */
  def writePersistenceDiagram(path: String, bars: Seq[PersistenceBar[Double, ?]], field: Option[Int] = None): Unit =
    val out = new PrintWriter(path)
    try
      bars.foreach { bar =>
        val prefix = field.map(f => s"$f ").getOrElse("")
        out.println(s"$prefix${bar.dim} ${Endpoints.formatEndpoint(bar.lower)} ${Endpoints.formatEndpoint(bar.upper)}")
      }
    finally out.close()
