package org.appliedtopology.tda4j
package cli

import org.rogach.scallop.*

/** The flags of the `tda4j` executable. Each compute flag is an option of [[org.appliedtopology.tda4j.matlab.TDA4j]]
  * under its kebab-case name, with no default here: an unset flag is left out, so defaults and validation are the
  * facade's.
  */
class TDA4jConf(arguments: Seq[String]) extends ScallopConf(arguments):
  banner(
    """tda4j: persistent homology of a point cloud, distance matrix, image or relation.
      |
      |Reads the input (--input-format), builds a complex (--complex; a cubical complex for an image, the Dowker
      |complex for a relation), computes its persistence and writes the diagram (--output-format).
      |
      |Usage: tda4j [options] <input-file>
      |""".stripMargin
  )

  val inputFormat: ScallopOption[String] = opt[String](
    default = Some("csv-points"),
    descr = "input format. Point clouds and distance matrices: csv-points (default), csv-distances, " +
      "csv-lower, ripser-points, ripser-lower, ripser-upper, ripser-distance, ripser-binary, dipha-distance, off. " +
      "Images: perseus-cubical, dipha-image, image. Relations: csv-relation (one row per point, one column per " +
      "witness), for the Dowker complex"
  )

  val output: ScallopOption[String] = opt[String](
    descr = "output file path (a filename PREFIX for --output-format=perseus, which writes one file per dimension). " +
      "Default: stdout, --output-format=text only."
  )
  val outputFormat: ScallopOption[String] = opt[String](
    default = Some("text"),
    descr = "output format: text, csv, gudhi, dipha, or perseus (default: text)"
  )
  val representatives: ScallopOption[Boolean] = opt[Boolean](
    default = Some(false),
    descr = "also print each bar's representative (--output-format=text only)"
  )

  val complex: ScallopOption[String] =
    opt[String](descr = "vr (default), alpha, cech, witness, dtm-rips, dtm-alpha, or sparse-rips")
  val engine: ScallopOption[String] =
    opt[String](descr =
      "ripser, chunks, naive, cohomology, fast-cubical or fast-alpha. Default: ripser for vr and the lazy " +
        "witness complex, naive otherwise. ripser needs vr or --witness-variant=lazy; fast-cubical is for images " +
        "of dimension 2 and up; fast-alpha for --complex=alpha with the helix backend"
    )
  val alphaBackend: ScallopOption[String] =
    opt[String](descr = "helix (default) or DQP, for --complex=alpha")
  val requireValidTriangulation: ScallopOption[String] = opt[String](
    descr = "true or false (default), for --complex=alpha with the helix backend: repair a degenerate " +
      "triangulation (needed by --engine=fast-alpha) instead of failing. Tested in dimensions 2 and 3"
  )
  val maxDimension: ScallopOption[Int] =
    opt[Int](descr = "the highest homological degree to compute (default: 2; for an image, its dimension)")
  val maxFiltrationValue: ScallopOption[Double] = opt[Double](
    descr = "stop the filtration at this value (default: the minimum enclosing radius); in the units of the " +
      "complex: a diameter for vr, a radius for cech"
  )
  val minPersistence: ScallopOption[Double] = opt[Double](descr =
    "report only bars longer than this (essential bars are always reported); 0 reports every bar of positive " +
      "length. " +
      "Default: --min-persistence-fraction 0.01. Give at most one of the two"
  )
  val minPersistenceFraction: ScallopOption[Double] = opt[Double](descr =
    "report only bars longer than this fraction of the input's scale: the minimum enclosing radius, " +
      "or the range of values of an image or relation (default: 0.01)"
  )
  val includeZeroLength: ScallopOption[String] = opt[String](descr =
    "true or false (default): also compute bars of length zero. They are reported with " +
      "--min-persistence 0"
  )
  val field: ScallopOption[String] = opt[String](descr = "Z (default, a prime finite field) or R (floating point)")
  val prime: ScallopOption[Int] = opt[Int](descr = "prime for --field=Z (default: 17)")
  val epsilon: ScallopOption[Double] = opt[Double](descr = "tolerance for --field=R (default: 1e-9)")
  // Boolean-valued options are Strings: Scallop's opt[Boolean] is always supplied (false when absent), which would
  // pass an explicit value to the facade on every run instead of leaving its default in charge.
  val sublevel: ScallopOption[String] = opt[String](
    descr = "true (default) for the sublevel filtration of an image, false for the superlevel one"
  )

  val numLandmarks: ScallopOption[Int] =
    opt[Int](descr = "the number of landmarks, required for --complex=witness")
  val witnessVariant: ScallopOption[String] =
    opt[String](descr =
      "lazy (default) or general, for --complex=witness. The lazy complex is a flag complex and runs " +
        "with --engine=ripser; the general one needs naive or cohomology"
    )
  val landmarkSelector: ScallopOption[String] =
    opt[String](descr =
      "maxmin (default, farthest-point sampling) or random (seeded by --landmark-seed), for " +
        "--complex=witness"
    )
  val landmarkSeed: ScallopOption[Int] =
    opt[Int](descr = "the seed of --landmark-selector=random (default: 0)")
  val nu: ScallopOption[Int] =
    opt[Int](descr = "0, 1 or 2 (default: 2): the nu of the lazy witness complex")

  val dtmK: ScallopOption[Int] =
    opt[Int](descr =
      "the number of nearest neighbours (the point itself included) of the distance to measure, " +
        "required for --complex=dtm-rips and dtm-alpha"
    )
  val dtmQ: ScallopOption[Double] =
    opt[Double](descr = "the exponent of the distance to measure (default: 2.0), for dtm-rips and dtm-alpha")
  val dtmP: ScallopOption[Double] =
    opt[Double](descr = "1.0 (default) or 2.0: how vertex weights combine with distances, for dtm-rips")
  val sparseEpsilon: ScallopOption[Double] =
    opt[Double](descr =
      "the approximation parameter in (0, 1), required for --complex=sparse-rips: the barcode is " +
        "within a factor 1 + epsilon of the Vietoris-Rips barcode (Cavanna, Jahanseir, Sheehy 2015)"
    )
  val edgeCollapse: ScallopOption[String] = opt[String](
    descr = "true or false (default), for --complex=vr: collapse edges first (Boissonnat-Pritam, " +
      "Glisse-Pritam). Same diagram, often a much smaller complex"
  )
  val dual: ScallopOption[String] = opt[String](
    descr = "true or false (default), for --input-format=csv-relation: the Dowker complex on the columns " +
      "instead of the rows. By Dowker duality both have the same diagram"
  )

  // These two choose which facade entry point runs; they are not facade options. --select-landmarks is a plain
  // toggle, read as `conf.selectLandmarks()`.
  val selectLandmarks: ScallopOption[Boolean] = opt[Boolean](
    default = Some(false),
    descr = "step 1 of the two-step witness complex: select --num-landmarks landmarks and write them, one " +
      "0-based index per line, with a '# coveringRadius=...' line, to --output or standard output. Then run " +
      "step 2 with --landmarks-file"
  )
  val landmarksFile: ScallopOption[String] = opt[String](
    descr = "step 2 of the two-step witness complex: read the landmarks from this file (as written by " +
      "--select-landmarks) instead of selecting them; implies --complex witness"
  )

  // Diagram distances, as in BarcodeDistance.
  val distanceTo: ScallopOption[String] = opt[String](
    descr = "instead of writing the diagram, print its bottleneck and Wasserstein distances in each " +
      "dimension to the diagram in this file (--output-format text only)"
  )
  val distanceFormat: ScallopOption[String] = opt[String](
    default = Some("csv"),
    descr = "the format of --distance-to: csv (default), gudhi or dipha"
  )
  val distanceOrder: ScallopOption[Double] =
    opt[Double](descr = "the order of the Wasserstein distance (default: 1.0), with --distance-to")
  val distanceGroundNorm: ScallopOption[Double] = opt[Double](
    descr = "the ground norm on the birth-death plane: a finite p >= 1.0 (default: L-infinity), with " +
      "--distance-to"
  )

  val input: ScallopOption[String] = trailArg[String](name = "input-file", descr = "input file", required = true)

  verify()
