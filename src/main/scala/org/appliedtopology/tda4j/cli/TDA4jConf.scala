package org.appliedtopology.tda4j
package cli

import org.rogach.scallop.*

/** Command-line argument definitions for the `tda4j` executable. Deliberately a thin mirror of
  * `org.appliedtopology.tda4j.matlab.TDA4j`'s own flat key/value option set (see that class's doc) -- every `--foo`
  * flag here corresponds 1:1 to an option key `TDA4j.computeFromPoints`/`computeFromDistanceMatrix` already recognizes
  * and validates, so this class does no validation of its own: an unset flag is simply omitted from the options array
  * `TDA4jCLI` builds (see that object's `buildOptions`), letting `TDA4j`'s own defaults and `IllegalArgumentException`
  * messages apply unchanged rather than duplicating them here.
  */
class TDA4jConf(arguments: Seq[String]) extends ScallopConf(arguments):
  banner(
    """tda4j: compute persistent (co)homology of a point cloud, distance matrix, or cubical image.
      |
      |Loads one of several file formats (see --input-format), computes a Vietoris-Rips/alpha/Cech/witness complex's
      |persistence (point-cloud/distance-matrix formats) or a cubical image's persistence (cubical-image formats)
      |via the same TDA4j/PersistenceResult facade the MATLAB bridge uses (see
      |org.appliedtopology.tda4j.matlab.TDA4j's own doc for the underlying --complex/--engine/--field options,
      |and computeFromCubicalImage's own doc for --sublevel), and writes the resulting persistence diagram in one
      |of several formats (see --output-format).
      |
      |Usage: tda4j [options] <input-file>
      |""".stripMargin
  )

  val inputFormat: ScallopOption[String] = opt[String](
    default = Some("csv-points"),
    descr = "input file format: csv-points, csv-distances, csv-lower, ripser-points, ripser-lower, ripser-upper, " +
      "ripser-distance, ripser-binary, dipha-distance, off (point-cloud/distance-matrix formats -- --complex " +
      "applies), or perseus-cubical, dipha-image, image (cubical-image formats -- --complex does not apply, " +
      "--sublevel does) (default: csv-points)"
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
    descr = "also print each bar's representative chain (--output-format=text only). Every engine records a " +
      "representative for every bar; a printed '(no representative recorded)' would indicate an engine bug, not " +
      "an expected gap."
  )

  val complex: ScallopOption[String] =
    opt[String](descr = "vr (default), alpha, cech, witness, dtm-rips, dtm-alpha, or sheehy-rips")
  val engine: ScallopOption[String] =
    opt[String](descr =
      "ripser, naive, chunks, cohomology, or fast-cubical (default depends on --complex -- see TDA4j's own doc). " +
        "cohomology is CellularCohomologyContext, generic over cell type and valid for every --complex value -- " +
        "unlike ripser, not Vietoris-Rips-specialized, so it also works with --complex=alpha/cech. fast-cubical " +
        "(FastCubicalHomologyContext) is valid ONLY for a cubical-image --input-format, and only when the image " +
        "is 2-dimensional."
    )
  val alphaBackend: ScallopOption[String] =
    opt[String](descr = "helix (default) or DQP -- only consulted when --complex=alpha")
  val maxDimension: ScallopOption[Int] =
    opt[Int](descr = "highest homological degree to report, i.e. \"give me H_0..H_k\" (default: 2)")
  val maxFiltrationValue: ScallopOption[Double] = opt[Double](
    descr = "truncate the filtration at this value (default: the point cloud's own minimum enclosing radius); " +
      "a diameter for --complex=vr, a RADIUS for --complex=cech"
  )
  val field: ScallopOption[String] = opt[String](descr = "Z (default, a prime finite field) or R (floating point)")
  val prime: ScallopOption[Int] = opt[Int](descr = "prime for --field=Z (default: 2)")
  val epsilon: ScallopOption[Double] = opt[Double](descr = "tolerance for --field=R (default: 1e-9)")
  // String, not Boolean -- Scallop's opt[Boolean] is a no-argument toggle flag whose ScallopOption is ALWAYS
  // supplied (defaulting to false when the flag is absent), unlike every other option here, which is genuinely
  // unset (None) until the user passes it. That would silently force sublevel=false into buildOptions's output
  // on every run regardless of whether the user ever mentioned it -- caught by buildOptions's own "empty when no
  // flags passed" test. A String mirrors TDA4j's own "true"/"false" string option exactly and has the same
  // genuinely-optional `.toOption` behavior as every other mirrored flag here.
  val sublevel: ScallopOption[String] = opt[String](
    descr = "true (default, sublevel) or false (superlevel) filtration -- only consulted for a cubical-image " +
      "--input-format (perseus-cubical, dipha-image, image)"
  )

  val numLandmarks: ScallopOption[Int] =
    opt[Int](descr = "number of landmarks to select -- REQUIRED when --complex=witness, ignored otherwise")
  val witnessVariant: ScallopOption[String] =
    opt[String](descr =
      "lazy (default, a flag complex -- supports --engine=ripser) or general (not a flag complex -- " +
        "--engine=ripser/chunks refused) -- only consulted when --complex=witness"
    )
  val landmarkSelector: ScallopOption[String] =
    opt[String](descr =
      "maxmin (default, sequential furthest-point sampling) or random (seeded by --landmark-seed) -- only " +
        "consulted when --complex=witness"
    )
  val landmarkSeed: ScallopOption[Int] =
    opt[Int](descr = "seed for --landmark-selector=random (default: 0) -- only consulted when --complex=witness")
  val nu: ScallopOption[Int] =
    opt[Int](descr =
      "0, 1, or 2 (default: 2) -- only consulted when --complex=witness and --witness-variant=lazy, see " +
        "streams.WitnessMetricSpace's own doc"
    )

  val dtmK: ScallopOption[Int] =
    opt[Int](descr =
      "number of nearest neighbours (self included) for distance-to-measure filtration -- REQUIRED when " +
        "--complex=dtm-rips or --complex=dtm-alpha, ignored otherwise"
    )
  val dtmQ: ScallopOption[Double] =
    opt[Double](descr = "DTM exponent, default 2.0 -- only consulted when --complex=dtm-rips or --complex=dtm-alpha")
  val dtmP: ScallopOption[Double] =
    opt[Double](descr =
      "ball-radius exponent for DTM-Rips (1.0 or 2.0, default 1.0) -- only consulted when --complex=dtm-rips"
    )
  val sheehyEpsilon: ScallopOption[Double] =
    opt[Double](descr =
      "sparsity/approximation-quality parameter in (0,1) -- REQUIRED when --complex=sheehy-rips, ignored " +
        "otherwise. The resulting barcode is a (1+epsilon)-multiplicative approximation to plain --complex=vr's " +
        "own barcode (Cavanna-Jahanseir-Sheehy 2015); see streams.SheehyRipsSimplexStream's own doc."
    )
  // String, not Boolean -- same reasoning as --sublevel above (a genuinely optional flag, not an
  // always-supplied toggle).
  val edgeCollapse: ScallopOption[String] = opt[String](
    descr = "true or false (default) -- only consulted for --complex=vr, rejected for any other --complex. " +
      "Boissonnat-Pritam/Glisse-Pritam edge collapse (streams.EdgeCollapse): reduces the Vietoris-Rips " +
      "1-skeleton to a smaller weighted graph with the SAME persistent homology, before anything is built on " +
      "top of it -- a preprocessing step, changing nothing about the output shape. Measured 73-76% of edges " +
      "removed and a 43-47x REDUCTION-phase speedup on random point clouds; see .claude/WORKLOG-edge-collapse.md."
  )

  // CLI-LOCAL control flow, unlike every option above: neither is forwarded into TDA4j's own options array
  // (see buildOptions's own comment) -- they select which of TDA4j's ENTRY POINTS this run calls, not a value
  // passed to one fixed entry point. opt[Boolean] here has the same always-supplied-defaulting-to-false
  // semantics --representatives already relies on (Scallop's own toggle-flag behavior, not a genuinely
  // optional value) -- fine for exactly the same reason: read only as `conf.selectLandmarks()`, a plain
  // Boolean, never through `.toOption`/`.isSupplied`.
  val selectLandmarks: ScallopOption[Boolean] = opt[Boolean](
    default = Some(false),
    descr = "step 1 of the two-step witness recipe: select landmarks only (requires --num-landmarks), " +
      "writing one 0-based landmark index per line to --output (or stdout) plus a '# coveringRadius=...' " +
      "line, and printing R to stderr -- see --landmarks-file for step 2. Only --output-format=text (the " +
      "default) is supported, and --representatives is meaningless here (there is no barcode)."
  )
  val landmarksFile: ScallopOption[String] = opt[String](
    descr = "step 2 of the two-step witness recipe: read landmark indices from this file (one 0-based index " +
      "per line, as --select-landmarks writes) instead of selecting them internally -- implies --complex " +
      "witness; --num-landmarks/--landmark-selector/--landmark-seed are not meaningful together with this"
  )

  // barcode.BarcodeDistance mirror (`.claude/WORKLOG-mainstream-feature-gap-analysis.md` item 4). Landscapes/
  // persistence images (item 8) are deliberately NOT mirrored here: they produce a matrix, not a diagram, which
  // doesn't fit this CLI's existing single-diagram text/csv/gudhi/dipha/perseus output model the way a second
  // diagram-shaped comparison does -- a real matrix-output CLI mode is its own design question (output format,
  // file layout for a multi-row/column result), left as a follow-up rather than bolted on here. See
  // matlab.PersistenceResult.landscape/persistenceImage for that capability's MATLAB-facing form.
  val distanceTo: ScallopOption[String] = opt[String](
    descr = "compare the computed diagram against an already-computed one read from this file (see " +
      "--distance-format), printing per-dimension bottleneck/Wasserstein distance instead of writing the " +
      "computed diagram -- --output/--output-format (text only) apply to THAT printed comparison, not a barcode"
  )
  val distanceFormat: ScallopOption[String] = opt[String](
    default = Some("csv"),
    descr = "file format of --distance-to: csv (default), gudhi, or dipha -- NOT perseus, whose format is " +
      "inherently single-dimension (see io.Perseus.readPersistenceIntervals's own `dim` parameter), not a fit " +
      "for this multi-dimension comparison"
  )
  val distanceOrder: ScallopOption[Double] =
    opt[Double](descr = "Wasserstein order (default: 1.0) -- only consulted with --distance-to")
  val distanceGroundNorm: ScallopOption[Double] = opt[Double](
    descr = "ground norm on the birth-death plane: a finite p >= 1.0, or omit for the default L-infinity -- " +
      "only consulted with --distance-to. See barcode.BarcodeDistance.GroundNorm's own doc."
  )

  val input: ScallopOption[String] = trailArg[String](name = "input-file", descr = "input file", required = true)

  verify()
