# WORKLOG: plots from MATLAB and the CLI, and saved results (#63, the plots part)

Session 2026-10-12, cloud branch `ccr-e8a1c6ce-0hh99n` (restarted from `scala` after #74 merged). The project lead
asked for the plots slice of #63 and went to sleep: "trust but record your decisions and designs". Every decision below
was taken in this session without them; each says why, so it can be overturned cheaply.

## The ask (project lead, 2026-10-12)

> Issue #63, specifically the plots. They should definitely be available from CLI and Matlab as well as from Scala.
> One way could be to serialize a computed persistence object so it can be pulled in on a later cli call and used to
> plot? In Matlab we can just hand the result object back, and that should get us what we want.
>
> Also, Matlab can hold anything we make in the library just fine, it just cannot instantiate anything that requires
> parametrized types.

This overrides `WORKLOG-plotting.md`'s "No MATLAB/CLI surface (plots are for Scala users; MATLAB has its own
plotting)", and relaxes `rules/facade.md`'s "public methods take/return only double, int, String, double[][],
String[]": a facade method may RETURN a library object (a `plot.Figure`) that MATLAB then calls methods on, as long as
no call needs MATLAB to build a parametrized type (or a Scala default argument, implicit, `Option`, ...).

## Starting point

- `plot.Plot.*` draws everything from Scala values: `barcode`/`diagram`/`landscape`/`persistenceImage` (bars),
  `cycle`/`cocycle` (a `Chain[Simplex[Int], ?]` over a `PointCloud`), `cubicalChain` (a `Chain[Cube, ?]` over an
  `Image`), `points`, `image`, `complex(points, at, kind)`, `circularCoordinates`, `torus`, 3-D `points3D`/`complex3D`/
  `cycle3D`. Every plot is a `trait Plot` value with `view()` and `save(path, theme: Option[Theme] = None)`.
- `matlab.PersistenceResult` holds the bars (all of them, plus the `visible` index and threshold), a lazy
  representative provider giving `(cells as Int arrays, coefficients as Double)` and a lazy boundary-matrix provider.
  It does NOT hold its input: nothing to draw a representative over.
- The facade's coefficients for `Z/p` are `Fp.toInt` = `norm`, the BALANCED representative (`-1`, not `16`), so a
  cocycle drawn from the facade's arrays gets the same diverging colors as `Plot.cocycle` on the typed chain.
- `PersistenceResult`'s class doc still says "a cocycle for the `ripser` and `cohomology` engines, a cycle for the
  others"; since `representativeType` every engine gives cycles by default. Fact fix, made here.

## Design

### 1. The result remembers its input (provenance)

`PersistenceResult` gets a private `ResultSource`: what was computed (entry point; resolved `complex`, `cycles` or
`cocycles`, field and prime; the options as passed) and the input geometry when there is one to draw on:

- points (`computeFromPoints`, `...AndLandmarks`): the coordinates;
- an image (`computeFromCubicalImage`/`computeFromImage`): shape and ORIGINAL values (before the superlevel negation,
  NaN masks kept);
- a distance matrix or a relation: none. Representative, input and complex plots then refuse with a message saying
  why (no coordinates) and what does work (barcode, diagram, landscape, persistence image).

Attached at the public entry points, which know the input (see the advisor review below: the `dispatch*` wrappers
do not all see it), through a `private[matlab]` copy method, last, after the threshold; `fromBars` and the
per-engine branches are untouched.

### 2. MATLAB: plot methods on the result, returning `plot.Figure`

On `PersistenceResult` (each returns a `Figure` -- MATLAB holds it and calls `fig.view()` or `fig.save('x.svg')`):

| method | draws |
|---|---|
| `plotBarcode()`, `plotBarcode(longest)` | the REPORTED bars (what `toArray()` lists); the subtitle says how many the threshold hid |
| `plotDiagram()` | the reported bars |
| `plotRepresentative(i)`, `plotRepresentative(i, at)` | bar `i`'s cycle or cocycle over the points (cocycle restricted to cells of diameter <= `at` with `at`), or its cubical chain over a 2-D image |
| `plotInput()` | the points (first two coordinates) or the 2-D image |
| `plotComplex(at)` | the `vr`, `cech` or `alpha` complex at scale `at` over the points, with its balls |
| `plotLandscape(dimension)`, `plotPersistenceImage(dimension)` | the vectorizations, of EVERY bar (as the facade's numeric `landscape`/`persistenceImage` do: rules/facade.md's invariant) |
| `mostPersistentBar(dimension)` | not a plot: the index of the reported bar of that degree with the largest persistence (an essential bar counts as infinite), to pass to `plotRepresentative` |

3-D (`plotInput3D()`, `plotComplex3D(at)`, `plotRepresentative3D(i)`), returning a `plot.Scene`: same data, the first
three coordinates. A cocycle in 3-D is drawn as its support (`Plot.cycle3D` has one color), titled "cocycle".

`CircularCoordinatesResult.plot()` (`Plot.circularCoordinates`, so the result keeps its points) and
`ToroidalCoordinatesResult.plot()`/`plot(c1, c2)` (`Plot.torus`).

`trait Plot` gains `save(path: String): Path` (= `save(path, None)`): Java and MATLAB cannot use Scala's default
argument, and building `scala.None` from MATLAB is not something to ask of a user. Scala resolves `save("x.svg")` to
the overload that needs no default.

Why plots are built from the facade's ARRAYS (not from the typed chains `fromBars` had): a loaded result (below) has
only the arrays, and one code path for both means a saved-then-loaded result draws the very same SVG as the live one
(which a spec checks byte for byte). The arrays become a `Chain[Simplex[Int], Double]` / `Chain[Cube, Double]` over
`Field.DoubleApproximated`; the coefficients are already balanced integers for `Z/p`.

### 3. Saved results: `result.save(path)` / `TDA4j.loadResult(path)`

One file format for MATLAB, the CLI and Scala: JSON, our own schema (`"format": "tda4j-persistence-result"`,
`"formatVersion": 1`).

- **What is saved**: every bar (dimension, birth, death), the reported bars' indices and the threshold (so
  `toArrayUnfiltered`, `hiddenCount`, distances and vectorizations of a loaded result equal the original's), the
  REPORTED bars' representatives, the provenance and the input geometry (points or image values).
- **Not saved**: representatives of the bars the threshold hid (the public API cannot reach them: `cycleVertices(i)`
  indexes reported bars; and an `H_0` cocycle is a whole component, so saving every bar's would be `O(n^2)` for `n`
  points); the boundary matrix (the whole complex). A loaded result's `numCells()`/`boundary*` throw
  `UnsupportedOperationException` saying to recompute.
- **Why JSON**: any language reads it with no tda4j (Python `json.load`, MATLAB `jsondecode`, R, Julia) -- a research
  platform's results should leave the platform easily. No JSON library in the build, and adding one to the core for
  this is not worth it: a writer and a strict small reader (~200 lines, private to `matlab`) suffice because we write
  the files.
- **Numbers**: doubles written by `Double.toString` (round-trips exactly); non-finite values (`Infinity` deaths, `NaN`
  masked pixels) as the JSON strings `"Infinity"`, `"-Infinity"`, `"NaN"` -- standard JSON, and Python's
  `float(x)` reads both forms. Integers (dimensions, vertices, doubled cube coordinates) as integers.
- **Versioning**: the reader refuses a `formatVersion` it does not know, naming the version it reads.

### 4. CLI

- `--save-result FILE`: also save the result (any run that computes a barcode).
- `--input-format result`: read a saved result instead of computing; picked automatically when `--input-format` is not
  given and the file starts with `{` (no point cloud, matrix or image file does). Every computation flag is then
  refused, naming the flags (the result is finished; its own options are in the file).
- `--plot KIND`: instead of writing the diagram, draw `barcode`, `diagram`, `representative`, `input`, `complex`,
  `landscape`, `persistence-image` (and `input-3d`, `complex-3d`, `representative-3d`) to `--output` (`.svg`: plain
  light colors; `.html`: a page following the reader's light or dark setting) -- or, without `--output`, to a page in
  the system's temporary directory opened in the browser (path always printed on standard error; never opened with
  `TDA4J_PLOT_BROWSER=none`). Not the live viewer: its server is daemon threads that die with the CLI process.
- `--bar N` (representative: the bar's 0-based position in the listing; default: `mostPersistentBar(--dimension)`),
  `--dimension K` (representative without `--bar`, landscape, persistence image; default 1, the loops), `--at R`
  (complex: required; representative: restricts a cocycle). New flags are `noshort` (rules/facade.md).
- `--plot` refuses `--distance-to`, `--select-landmarks`, `--representatives` and a supplied `--output-format`;
  `--bar`/`--dimension`/`--at` refuse to run without `--plot`.

## Advisor review (a Plan agent, read-only, given the draft above and the code) and what changed

Asked to push back on: JSON vs other formats, saving only reported representatives, where provenance attaches, the
CLI surface, building chains from arrays, the `save(path)` overload, scope, and the tests. Verdicts and my decisions:

- **Bugs it found in the in-progress code, all fixed:**
  - Cocycle `at` restricted cells by Euclidean DIAMETER (`Plot.cocycle`'s own rule), which is the filtration only of
    plain Vietoris-Rips: for Cech and alpha (radii) `at = birth` dropped every edge. Now `at` keeps the cocycle's cells
    that are in the result's own complex (`vr`, `cech`, `alpha`, built by the `PointCloudComplex` at `at`), and refuses
    other complexes, like `plotComplex`.
  - Plot refusals are `UnsupportedOperationException`, which `TDA4jCLI.run` did not catch (stack trace instead of
    `tda4j: ...`). The CLI now catches it.
  - Building a `Chain[_, Double]` over `DoubleApproximated(1e-9)` invented a field: a `field=R` coefficient under
    1e-9 vanished. `Plot.cycle`/`cocycle`/`cubicalChain`/`cycle3D` now take `Chain[C, ?] | Seq[(C, Double)]` (like
    `Plot.Bars`): public, so users can draw their own real-valued cochains too; the facade passes its arrays.
  - `save` swallowed a missing representative (the class doc calls that an engine bug). It now propagates.
- **JSON: kept.** Reader now also accepts bare `Infinity`/`-Infinity`/`NaN` (Python's default `json.dump` writes them,
  so a file filtered in Python reads back) and skips a UTF-8 BOM. The tda4j version is recorded from a generated
  resource (the class files have no manifest version in tests or `sbt console`). Whole-file-in-memory reading stays;
  documented. Docs give the Python/MATLAB recipes for the string-encoded non-finite numbers instead of "any language
  reads it" unqualified.
- **Reported-only representatives: kept.** Loaded representatives are handed out as copies (the live result builds
  fresh arrays per call; a loaded one would otherwise expose its own). A hidden bar's representative says it was not
  saved, not "engine bug". File size: `--min-persistence 0` with cocycles can still be large (an `H_0` cocycle is a
  component); documented, as is that saving computes every reported representative.
- **Provenance: attached at the public entry points** (the draft said the `dispatch*` wrappers; two of them see only a
  stream or a metric space -- `dispatchCubical` sees negated values). The code already did this.
- **CLI:** new flags declared last, `noshort`, and `CLISpec` pins EVERY 0.5.0 short flag (it pinned four). `--plot`
  kind validated in `run` (Scallop's `choices`/`validate` call `System.exit`). `--output` must end in `.svg` or
  `.html` (any case; `Plot.save` now checks the extension case-insensitively). A loaded result refuses every flag
  `buildOptions` would pass (so a future computation flag is refused automatically) and the landmark flags, but
  allows `--output-format`, `--representatives` and `--distance-to` (converting a saved result is free). The chosen
  bar is printed on standard error (the listing has no index column). `--bar`/`--dimension`/`--at` are refused for
  kinds that do not use them, not only without `--plot`. Browser opening is `Viewer.browse`, shared with the viewer.
  Rejected: writing SVG to standard output when it is not a terminal (Java 21's `System.console()` cannot tell
  stdout alone; fragile).
- **Scope:** kept 2-D, `mostPersistentBar` (fixed: a zero-length `[Inf, Inf)` bar gave `Inf - Inf = NaN`, the
  largest), circular/toroidal `.plot()`, 3-D for cycles. `plotRepresentative3D` REFUSES cocycles (one color, no
  values: a cocycle's support would read as a loop).
- **Tests it asked for**, added: bitwise array equality (SVG rounds to 4 digits and prints `-0` as `0`, so
  byte-identical plots cannot see precision loss; a superlevel image gives `-0.0` births), a hidden bar before a
  reported one, `includeZeroLength` surviving, alpha/Cech cocycles at their birth non-empty, witness tooltips within
  the landmarks, a reader fuzz (every truncation is an `IllegalArgumentException`), a Python-style file (bare
  `Infinity`, BOM, reordered and unknown keys), `getMethod("save", classOf[String])`, the CLI cases.
- Noted, not changed: `Plot.complex(..., AlphaShapes)` draws the default alpha backend's triangulation, which can differ
  from the result's `alphaBackend` on degenerate (cospherical, grid) points; `plotComplex(at)` above the result's
  `maxFiltrationValue` draws cells the computation never built; image cocycles are drawn in one color
  (`Plot.cubicalChain`).
