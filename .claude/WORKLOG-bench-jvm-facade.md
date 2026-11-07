# Bench: the JS worker's call on the JVM (#232)

2026-11-07, thread "Triage issues before 1.0.0 and paper". A point-in-time snapshot; the rules that came out of it are in
`bench/README.md` (the `tda4j-facade*` rows) and `PaperBenchmarkDriver`'s scaladoc (`facade=true`).

## The problem

`bench/workers/tda4j_js_worker.mjs` calls the npm API, a thin layer over `matlab.TDA4j`. The JVM rows call the
`Persistence` verb. They do different work (the #228 "493x" compared the 3-skeleton on the JVM with the whole complex on JS;
the cubical sweep compared an engine that built every zero-length bar with one that did not), and nothing in the harness
kept a JS row comparable with a JVM row. The facade and the verb now agree on bar counts, but a ratio JS ÷ verb still
includes whatever the facade adds on top of the verb: it copies the input, parses option strings, builds a
`PersistenceResult`, and the worker reads every bar and, with `read=true`, every representative.

## What was built

- `PaperBenchmarkDriver facade=true`: the same call, `matlab.TDA4j.computeFromPoints` / `computeFromDistanceMatrix` /
  `computeFromCubicalImage`, with the option strings the worker's options object becomes (`facadeOptions`: `maxDimension`,
  `prime`, `minPersistence 0`, then `engine`, `representativeType`, `maxFiltrationValue`, `complex`, `alphaBackend`, in the
  worker's key order; `fastcubical` and `fastalpha` spelled `fast-cubical` and `fast-alpha`). Every bar of positive length is
  read with `toArrayUnfiltered` (the worker's `allBars`) and `read=true` reads `cycleVertices(i)` for every bar. What the JS
  API does not offer (`reps=none`, an engine the worker has no name for, `construction=V`, `fastalpha` on DQP or with a
  threshold) throws here too. The JSON line carries `"facade":true`.
- `bench.py`: a `tda4j-facade*` row generated from every `tda4j-js*` row (same task and options, `facade`, `experiment`):
  run only when `--tools` names it exactly (`experiment_wanted`, which `run` and the sweeps share). It runs on one thread
  (`sequential=true`), as the JS worker is one thread. `facade_twin` / `verb_twin` / `jvm_twin` name the three.
- The summary: "Scala.js against the JVM" gets the facade's warm and cold ratios and a `bar counts` column (`same`, or what
  differs among the JS, verb and facade rows, per degree); a new "The JVM facade against the verb" section is the facade's
  own overhead. The alpha sweep takes `tda4j-facade`, `-helix`, `-fastalpha`, `-dqp` when named and ends `sweep.md` with a
  table of whether each JS and facade row's bars equal the verb's; the cubical sweep takes `facade-fastcubical` and
  `facade-cohomology-cycles` when named, with `js/facade` and `bars` columns.

Not in `final_run.sh`: the paper's tables compare the verb's rows, and the facade rows exist to explain a JS ratio, so a
run that wants them names them: `--tools tda4j-js,tda4j,tda4j-facade`.

## Checks

- Specs (`PaperBenchmarkDriverFacadeSpec`): the option strings for each option, and the driver on files, facade against
  verb, bars and the counts of the JSON line equal on points (threshold, cocycles, F_2), a distance matrix, alpha (default
  and Bowyer-Watson) and Cech, and on a cubical image with `read=true` for three engines (terms read equal). Mutation-checked
  on the driver: dropping `minPersistence 0` (an error: the representatives of the hidden bars are read by index), the
  `complex` option for alpha, the threshold, the `fast-cubical` spelling, and the representative reading each fail a spec.
  The example for `minPersistence` needs a bar the default threshold would hide, hence the 400 in the test image. Equivalent
  mutant, not killable: `toArrayUnfiltered` for `toArray` in the driver's `bars` (with `minPersistence 0` they are the same).
- `bench/test_bench.py` (`FacadeRowsTests`, 13 tests): the generated rows, the twin names, the command, the experiment rule
  for `run` and the alpha sweep (`candidate_tools`), the verdicts, both summary tables, the alpha and cubical sweep tables.
  Six mutations of the harness each fail a test.
- A real harness run (JVM processes, and the npm package built from this branch with `sbt tda4jJS/npmPack`, unpacked,
  `TDA4J_JS` pointing at its `main.mjs`) on a noisy circle (60 points), an alpha case and a 9 x 9 image: `tda4j-js`, `tda4j`
  and `tda4j-facade` (and the `-fastalpha` and `-cohomology` rows) report the same bars per degree on every case, and both
  tables came out as designed. At this size the times say nothing, so none are quoted.

## Not measured

How much of the JS slowdown quoted so far (JS ÷ verb) is the facade's work and how much is the platform: that is what the
rows are for, and needs a run on the server with the npm package installed (`bench/run.sh setup tda4j-js`) over the cases
of the JS stage. Until it has run, a "JS is Nx the JVM" in the paper should name the verb as the baseline.

A note for the summary's existing `bars (> tol)` column: it shows the bars longer than the tolerance where a reference
barcode was compared and the raw count where not, so two rows of one case can differ by a short bar in that column
without differing in their bars. The facade comparison uses the raw counts of both.
