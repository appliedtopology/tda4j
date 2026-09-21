package org.appliedtopology.tda4j
package cli

import org.rogach.scallop.*

/** Command-line argument definitions for the `tda4j` executable. Deliberately a thin mirror of
  * `org.appliedtopology.tda4j.matlab.Tda4j`'s own flat key/value option set (see that class's doc) -- every
  * `--foo` flag here corresponds 1:1 to an option key `Tda4j.computeFromPoints`/`computeFromDistanceMatrix`
  * already recognizes and validates, so this class does no validation of its own: an unset flag is simply omitted
  * from the options array `Tda4jCli` builds (see that object's `buildOptions`), letting `Tda4j`'s own defaults and
  * `IllegalArgumentException` messages apply unchanged rather than duplicating them here.
  */
class Tda4jConf(arguments: Seq[String]) extends ScallopConf(arguments):
  banner(
    """tda4j: compute persistent (co)homology of a point cloud or distance matrix.
      |
      |Loads one of several file formats (see --input-format), computes a Vietoris-Rips or alpha-complex
      |barcode via the same Tda4j/PersistenceResult facade the MATLAB bridge uses (see
      |org.appliedtopology.tda4j.matlab.Tda4j's own doc for the underlying --complex/--engine/--field options),
      |and writes the resulting persistence diagram in one of several formats (see --output-format).
      |
      |Usage: tda4j [options] <input-file>
      |""".stripMargin
  )

  val inputFormat: ScallopOption[String] = opt[String](
    default = Some("csv-points"),
    descr =
      "input file format: csv-points, csv-distances, csv-lower, ripser-points, ripser-lower, ripser-upper, " +
        "ripser-distance, ripser-binary, dipha-distance, or off (default: csv-points)"
  )

  val output: ScallopOption[String] = opt[String](
    descr =
      "output file path (a filename PREFIX for --output-format=perseus, which writes one file per dimension). " +
        "Default: stdout, --output-format=text only."
  )
  val outputFormat: ScallopOption[String] = opt[String](
    default = Some("text"),
    descr = "output format: text, csv, gudhi, dipha, or perseus (default: text)"
  )
  val representatives: ScallopOption[Boolean] = opt[Boolean](
    default = Some(false),
    descr =
      "also print each bar's representative chain (--output-format=text only). Best-effort: the default " +
        "engine (ripser) has no representative recorded for some bars resolved via its apparent-pairs shortcut " +
        "-- those print as '(no representative recorded)' rather than failing the whole run. engine=chunks " +
        "records one for every bar."
  )

  val complex: ScallopOption[String] = opt[String](descr = "vr (default) or alpha")
  val engine: ScallopOption[String] =
    opt[String](descr = "ripser, naive, or chunks (default depends on --complex -- see Tda4j's own doc)")
  val alphaBackend: ScallopOption[String] =
    opt[String](descr = "helix (default) or DQP -- only consulted when --complex=alpha")
  val maxDimension: ScallopOption[Int] =
    opt[Int](descr = "highest homological degree to report, i.e. \"give me H_0..H_k\" (default: 2)")
  val maxFiltrationValue: ScallopOption[Double] = opt[Double](
    descr = "truncate the filtration at this value (default: the point cloud's own minimum enclosing radius)"
  )
  val field: ScallopOption[String] = opt[String](descr = "Z (default, a prime finite field) or R (floating point)")
  val prime: ScallopOption[Int] = opt[Int](descr = "prime for --field=Z (default: 2)")
  val epsilon: ScallopOption[Double] = opt[Double](descr = "tolerance for --field=R (default: 1e-9)")

  val input: ScallopOption[String] = trailArg[String](name = "input-file", descr = "input file", required = true)

  verify()
