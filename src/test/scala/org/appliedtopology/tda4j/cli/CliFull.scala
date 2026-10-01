package org.appliedtopology.tda4j.cli

/** Test-only: `TDA4jCLI.run` with the default persistence threshold switched OFF (`--min-persistence 0`), so the
  * pre-existing CLI specs keep seeing complete barcodes. The threshold's own behaviour is covered separately in
  * `PersistenceThresholdCLISpec`, which calls `TDA4jCLI.run` directly.
  */
private[tda4j] object CliFull:
  // Modes with no reported barcode (and so no threshold to turn off) are passed through untouched.
  private val noBarcodeModes = Set("--select-landmarks", "--distance-to")

  def run(args: Seq[String], out: java.io.PrintStream): Int =
    if args.exists(noBarcodeModes) then TDA4jCLI.run(args, out)
    else TDA4jCLI.run(Seq("--min-persistence", "0") ++ args, out)
