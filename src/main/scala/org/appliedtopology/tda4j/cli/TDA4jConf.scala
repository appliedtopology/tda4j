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
      |complex for a relation), computes its persistence and writes the diagram (--output-format), or draws it
      |(--plot). --save-result keeps the whole result in a file, which is then an input itself: plot or print it
      |later without computing again.
      |
      |Usage: tda4j [options] <input-file>
      |""".stripMargin
  )

  val inputFormat: ScallopOption[String] = opt[String](
    name = "input-format",
    default = Some("csv-points"),
    descr = "input format. Point clouds and distance matrices: csv-points (default), csv-distances, " +
      "csv-lower, ripser-points, ripser-lower, ripser-upper, ripser-distance, ripser-sparse, ripser-binary, " +
      "dipha-distance, off. " +
      "Images: perseus-cubical, dipha-image, image. Relations: csv-relation (one row per point, one column per " +
      "witness), for the Dowker complex. A result saved with --save-result: result (chosen by itself when " +
      "--input-format is not given)"
  )

  val output: ScallopOption[String] = opt[String](
    name = "output",
    descr = "output file path (a filename PREFIX for --output-format=perseus, which writes one file per dimension). " +
      "Default: stdout, --output-format=text only."
  )
  val outputFormat: ScallopOption[String] = opt[String](
    name = "output-format",
    default = Some("text"),
    descr = "output format: text, csv, gudhi, dipha, or perseus (default: text)"
  )
  val representatives: ScallopOption[Boolean] = opt[Boolean](
    name = "representatives",
    default = Some(false),
    descr = "also print each bar's representative (--output-format=text only)"
  )

  val complex: ScallopOption[String] =
    opt[String](name = "complex", descr = "vr (default), alpha, cech, witness, dtm-rips, dtm-alpha, or sparse-rips")
  val engine: ScallopOption[String] =
    opt[String](
      name = "engine",
      descr = "ripser, chunks, naive, cohomology, fast-cubical or fast-alpha. Default: ripser for vr and the lazy " +
        "witness complex, fast-cubical for images of dimension 2 and up, cohomology otherwise. ripser needs vr or " +
        "--witness-variant=lazy; fast-cubical is for images " +
        "of dimension 2 and up; fast-alpha for --complex=alpha with the bowyer-watson or helix backend"
    )
  val alphaBackend: ScallopOption[String] =
    opt[String](
      name = "alpha-backend",
      descr = "default, bowyer-watson, helix or DQP, for --complex=alpha. bowyer-watson: points spanning at most 4 " +
        "dimensions, exact predicates, fastest there. default: bowyer-watson up to 4 dimensions and helix above, " +
        "or with --max-filtration-value DQP instead when it is expected to be faster for that radius (same result " +
        "in general position)"
    )
  val requireValidTriangulation: ScallopOption[String] = opt[String](
    name = "require-valid-triangulation",
    descr = "true or false (default), for --complex=alpha with the helix backend: also check the triangulation for " +
      "cavities, and fail rather than return one that could not be repaired (every triangulation is checked and " +
      "repaired by default)"
  )
  val maxDimension: ScallopOption[Int] =
    opt[Int](
      name = "max-dimension",
      descr = "the highest homological degree to compute (default: 2; for an image, its dimension)"
    )
  val maxFiltrationValue: ScallopOption[Double] = opt[Double](
    name = "max-filtration-value",
    descr = "stop the filtration at this value (default: the minimum enclosing radius); in the units of the " +
      "complex: a diameter for vr, a radius for cech"
  )
  val minPersistence: ScallopOption[Double] = opt[Double](
    name = "min-persistence",
    descr = "report only bars longer than this (essential bars are always reported); 0 reports every bar of positive " +
      "length. " +
      "Default: --min-persistence-fraction 0.01. Give at most one of the two"
  )
  val minPersistenceFraction: ScallopOption[Double] = opt[Double](
    name = "min-persistence-fraction",
    descr = "report only bars longer than this fraction of the input's scale: the minimum enclosing radius, " +
      "or the range of values of an image or relation (default: 0.01)"
  )
  val representativeType: ScallopOption[String] = opt[String](
    name = "representative-type",
    descr =
      "cycles (default) or cocycles: the kind of representative each bar gets. Every --engine gives both except " +
        "fast-cubical and fast-alpha (cycles only); ripser and cohomology compute cocycles natively, the others cycles"
  )
  val includeZeroLength: ScallopOption[String] = opt[String](
    name = "include-zero-length",
    descr = "true or false (default): also compute bars of length zero. They are reported with " +
      "--min-persistence 0"
  )
  val field: ScallopOption[String] =
    opt[String](name = "field", descr = "Z (default, a prime finite field) or R (floating point)")
  val prime: ScallopOption[Int] = opt[Int](name = "prime", descr = "prime for --field=Z (default: 17)")
  val epsilon: ScallopOption[Double] = opt[Double](name = "epsilon", descr = "tolerance for --field=R (default: 1e-9)")
  // Boolean-valued options are Strings: Scallop's opt[Boolean] is always supplied (false when absent), which would
  // pass an explicit value to the facade on every run instead of leaving its default in charge.
  val sublevel: ScallopOption[String] = opt[String](
    name = "sublevel",
    descr = "true (default) for the sublevel filtration of an image, false for the superlevel one"
  )
  // No short flag: Scallop gives a letter to the first option declared with it, so taking `-n` here would move it
  // from --num-landmarks (declared below), which had it first.
  val nanMask: ScallopOption[String] = opt[String](
    name = "nan-mask",
    noshort = true,
    descr = "true (default): a NaN pixel of an image is masked, left out of the complex (a Perseus -1 reads as NaN); " +
      "false: a NaN pixel is an error"
  )

  val numLandmarks: ScallopOption[Int] =
    opt[Int](name = "num-landmarks", descr = "the number of landmarks, required for --complex=witness")
  val witnessVariant: ScallopOption[String] =
    opt[String](
      name = "witness-variant",
      descr = "lazy (default) or general, for --complex=witness. The lazy complex is a flag complex and runs " +
        "with --engine=ripser; the general one needs naive or cohomology"
    )
  val landmarkSelector: ScallopOption[String] =
    opt[String](
      name = "landmark-selector",
      descr = "maxmin (default, farthest-point sampling) or random (seeded by --landmark-seed), for " +
        "--complex=witness"
    )
  val landmarkSeed: ScallopOption[Int] =
    opt[Int](name = "landmark-seed", descr = "the seed of --landmark-selector=random (default: 0)")
  val nu: ScallopOption[Int] =
    opt[Int](name = "nu", descr = "0, 1 or 2 (default: 2): the nu of the lazy witness complex")

  val dtmK: ScallopOption[Int] =
    opt[Int](
      name = "dtm-k",
      descr = "the number of nearest neighbours (the point itself included) of the distance to measure, " +
        "required for --complex=dtm-rips and dtm-alpha"
    )
  val dtmQ: ScallopOption[Double] =
    opt[Double](
      name = "dtm-q",
      descr = "the exponent of the distance to measure (default: 2.0), for dtm-rips and dtm-alpha"
    )
  val dtmP: ScallopOption[Double] =
    opt[Double](name = "dtm-p", descr = "1.0 (default) or 2.0: how vertex weights combine with distances, for dtm-rips")
  val sparseEpsilon: ScallopOption[Double] =
    opt[Double](
      name = "sparse-epsilon",
      descr = "the approximation parameter in (0, 1), required for --complex=sparse-rips: the barcode is " +
        "within a factor 1 + epsilon of the Vietoris-Rips barcode (Cavanna, Jahanseir, Sheehy 2015)"
    )
  val edgeCollapse: ScallopOption[String] = opt[String](
    name = "edge-collapse",
    descr = "true or false (default), for --complex=vr: collapse edges first (Boissonnat-Pritam, " +
      "Glisse-Pritam). Same diagram, often a much smaller complex"
  )
  val dual: ScallopOption[String] = opt[String](
    name = "dual",
    descr = "true or false (default), for --input-format=csv-relation: the Dowker complex on the columns " +
      "instead of the rows. By Dowker duality both have the same diagram"
  )

  // These two choose which facade entry point runs; they are not facade options. --select-landmarks is a plain
  // toggle, read as `conf.selectLandmarks()`.
  val selectLandmarks: ScallopOption[Boolean] = opt[Boolean](
    name = "select-landmarks",
    default = Some(false),
    descr = "step 1 of the two-step witness complex: select --num-landmarks landmarks and write them, one " +
      "0-based index per line, with a '# coveringRadius=...' line, to --output or standard output. Then run " +
      "step 2 with --landmarks-file"
  )
  val landmarksFile: ScallopOption[String] = opt[String](
    name = "landmarks-file",
    descr = "step 2 of the two-step witness complex: read the landmarks from this file (as written by " +
      "--select-landmarks) instead of selecting them; implies --complex witness"
  )

  // Diagram distances, as in BarcodeDistance.
  val distanceTo: ScallopOption[String] = opt[String](
    name = "distance-to",
    descr = "instead of writing the diagram, print its bottleneck and Wasserstein distances in each " +
      "dimension to the diagram in this file (--output-format text only)"
  )
  val distanceFormat: ScallopOption[String] = opt[String](
    name = "distance-format",
    default = Some("csv"),
    descr = "the format of --distance-to: csv (default), gudhi or dipha"
  )
  val distanceOrder: ScallopOption[Double] =
    opt[Double](
      name = "distance-order",
      descr = "the order of the Wasserstein distance (default: 1.0), with --distance-to"
    )
  val distanceGroundNorm: ScallopOption[Double] = opt[Double](
    name = "distance-ground-norm",
    descr = "the ground norm on the birth-death plane: a finite p >= 1.0 (default: L-infinity), with " +
      "--distance-to"
  )

  // Plots and saved results. Declared last and without short flags: Scallop gives a letter to the first option declared
  // with it, so an earlier declaration would take a short flag users already type.
  val plot: ScallopOption[String] = opt[String](
    name = "plot",
    noshort = true,
    descr = "instead of writing the diagram, draw: barcode, diagram, representative (a bar's cycle or cocycle over " +
      "the points or image), input, complex (at --at), landscape, persistence-image, or input-3d, complex-3d, " +
      "representative-3d. Into --output (.svg in plain light colors, .html a page that follows the reader's light " +
      "or dark setting), or without --output into a page opened in the browser"
  )
  val bar: ScallopOption[Int] = opt[Int](
    name = "bar",
    noshort = true,
    descr = "for --plot representative: the bar, by its 0-based line number in the text output (default: the most " +
      "persistent bar of --dimension)"
  )
  val dimension: ScallopOption[Int] = opt[Int](
    name = "dimension",
    noshort = true,
    descr = "for --plot representative (without --bar), landscape and persistence-image: the homological " +
      "dimension (default: 1, the loops)"
  )
  val at: ScallopOption[Double] = opt[Double](
    name = "at",
    noshort = true,
    descr = "the scale, in the units of the complex: required for --plot complex; for --plot representative, a " +
      "cycle is drawn over the complex at this scale and a cocycle restricted to it (default for a cocycle: its " +
      "bar's birth; Infinity draws it whole)"
  )
  val saveResult: ScallopOption[String] = opt[String](
    name = "save-result",
    noshort = true,
    descr = "also save the whole result to this file (JSON): every bar, the reported bars' representatives, the " +
      "input, and how it was computed. Give the file as the input of a later run to print or plot it"
  )

  // How a --plot looks: each flag is one change of the plot (see the plot package's `Tweak`), refused without --plot.
  val title: ScallopOption[String] = opt[String](name = "title", noshort = true, descr = "for --plot: the plot's title")
  val xLabel: ScallopOption[String] =
    opt[String](name = "x-label", noshort = true, descr = "for --plot: the x axis label")
  val yLabel: ScallopOption[String] =
    opt[String](name = "y-label", noshort = true, descr = "for --plot: the y axis label")
  val caption: ScallopOption[String] = opt[String](
    name = "caption",
    noshort = true,
    descr = "for 2-D --plot kinds: a line of text under the plot (a figure number and what it shows)"
  )
  val tag: ScallopOption[String] = opt[String](
    name = "tag",
    noshort = true,
    descr =
      "for 2-D --plot kinds: a short label in the plot's corner, such as (a), when it will be one panel of a figure"
  )
  val description: ScallopOption[String] = opt[String](
    name = "description",
    noshort = true,
    descr = "for 2-D --plot kinds: what a screen reader says of the plot, the <desc> of the SVG"
  )
  val look: ScallopOption[String] = opt[String](
    name = "look",
    noshort = true,
    descr = "for --plot: a whole look at once, default, print (--palette print and --style print) or clear " +
      "(--palette clear and --style clear); --palette, --style and --set after it win"
  )
  val width: ScallopOption[Int] =
    opt[Int](name = "width", noshort = true, descr = "for --plot: the width in pixels (100 to 20000)")
  val height: ScallopOption[Int] =
    opt[Int](name = "height", noshort = true, descr = "for --plot: the height in pixels (100 to 20000)")
  val palette: ScallopOption[String] = opt[String](
    name = "palette",
    noshort = true,
    descr = "for --plot: the color palette, brand (default), reference, print (black on pure white, for a paper), " +
      "grayscale (print in grays only), or clear (black for a light page, white for a dark one, with --theme; for " +
      "--style clear)"
  )
  val style: ScallopOption[String] = opt[String](
    name = "style",
    noshort = true,
    descr = "for --plot: the look apart from colors, default, slides (larger text and marks), print (portable " +
      "fonts, for a paper) or clear (portable fonts and no background)"
  )
  val theme: ScallopOption[String] = opt[String](
    name = "theme",
    noshort = true,
    descr = "for --plot: light, dark, or auto (follow the reader's setting). Default: light for an .svg file, auto " +
      "for an .html page"
  )
  val set: ScallopOption[List[String]] = opt[List[String]](
    name = "set",
    noshort = true,
    descr = "for --plot: a change to the look of one element, as element.property=value (title.size=18, " +
      "tick-label.angle=45, grid.dash=dotted, ticks.length=4, frame.visible=true, panel.color=#f4f4f4, " +
      "diagonal.shade=none, x-axis=top); repeat the flag for several"
  )
  val textScale: ScallopOption[Double] =
    opt[Double](
      name = "text-scale",
      noshort = true,
      descr = "for --plot: a factor on every text size (1.3 suits a slide)"
    )
  val xScale: ScallopOption[String] = opt[String](
    name = "x-scale",
    noshort = true,
    descr = "for 2-D --plot kinds: the x axis scale, linear (default), log, sqrt or symlog. On a log axis what is at " +
      "0 sits on a -∞ line, and a negative value makes it a symmetric log, which the plot says"
  )
  val yScale: ScallopOption[String] =
    opt[String](name = "y-scale", noshort = true, descr = "for 2-D --plot kinds: the y axis scale, as --x-scale")

  val input: ScallopOption[String] =
    trailArg[String](name = "input-file", descr = "input file", required = true)

  verify()
