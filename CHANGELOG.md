# Changelog

What changed in each release of `tda4j`, newest first. Every pull request that changes what a user sees or calls adds a
line under **Unreleased**; at a release the section is renamed to the version (`RELEASE.md`). Each section opens with
the changes that break existing code. Before 1.0, a minor version may break the API, and every break inside a series
is named here and in a commented `mimaBinaryIssueFilters` entry in `build.sbt`.

## Unreleased

### Breaking

- `Plot.save` (and `PersistenceResult.save`, and so the MATLAB `save`) return the absolute path as a `String`, not a
  `java.nio.file.Path`: the Scala.js build has no `java.nio.file`. Call `Paths.get(result)` where you need a `Path`.
- `CubicalImage.fromBufferedImage` and `CubicalImage.fromFile` are members of the JVM build only (they are still there, on
  the JVM); on Scala.js `fromFile` throws and `fromImageData` takes their place.
- The library no longer depends on Apache Commons Math, Apache Commons Numbers, jvptree or Miniball: none has a Scala.js
  build. `DenseVector`, `DenseMatrix`, `ConjugateGradient`, `Erf`, `VantagePointTree` and `MinimumEnclosingBall` replace
  them (see Added); the next three entries list the signatures that changed.
- `RingModule.scale` is `scalarMultiply`, and so is the infix extension (`r.scalarMultiply(x)`, was `r.scale(x)`). The
  operators `⊠`, `|*|`, `<*` and `mul` are as they were. A `RingModule` instance of your own implements
  `scalarMultiply(x, y)`; the labs (`TDAlab`, `CubicalLab`) lose `scale` and gain `scalarMultiply`.
- The plot model follows the grammar of graphics (see Added). `plot.Axis` has `min` and `max` instead of `range`, a
  `Ticks` choice instead of a list of ticks (`extra` ticks for the `∞` mark), a `Transform` and a number `format`;
  `Figure.colorbar` and the `Colorbar` class are `Figure.colorScale` and `ColorScale`; `Figure` and `Scene` take a
  `style` and per-degree `degrees` (a `Figure` also a `legendTitle`). The functions of `Plot`, which `matlab` and `cli`
  call, keep their signatures.
- `Barcode.isMap`, `image`, `imageMatrix`, `kernel`, `cokernel`, `cokernelMatrix`, `reduceMatrix` and `pivotsOf` take and
  return a `DenseMatrix` (was commons-math3's `RealMatrix`), and `Barcode.reduceMatrix` no longer changes the matrix it
  is given. `AlphaShapes.Point`, and so the `normal` of a `Hyperplane` and the `center` of a `Hypersphere`, is a
  `DenseVector` (was a `RealVector`); `Hyperplane.from` returns the normal with a fixed sign. The library no longer
  uses commons-math3 in code that builds for Scala.js.
- `JVPTree` is `VantagePointQuery`, and the vantage-point tree behind it is our own `VantagePointTree` instead of the
  Java library jvptree (the Scala.js build cannot use Java libraries). `nearestNeighbors` breaks ties by the order of
  `metricSpace.elements` and always returns `k` points; `EuclideanMetricSpace.vpdf` is a Scala function, `vpt` a
  `VantagePointTree`, and `neighbors(point, eps)` returns every index within `eps`, repeated points included.

### Added

- **tda4j runs in JavaScript.** The library is built for the JVM and for Scala.js from the same sources (sbt 2 project
  matrix: `tda4j` and `tda4jJS`). Scala.js programs depend on the same coordinates (`"org.appliedtopology" %% "tda4j"`
  picks `tda4j_sjs1_3` in a Scala.js project); JavaScript and TypeScript programs install the npm package `@appliedtopology/tda4j`:
  `npm install @appliedtopology/tda4j`, `import { persistence } from "@appliedtopology/tda4j"`, with `persistence`, `persistenceFromDistances`, `persistenceFromImage`
  and `persistenceFromGrid` returning a `Result` (bars, representatives, distances, landscapes, plots) and typings. `npx
  tda4j` is the command line on Node.js. Browsers and Node.js 20.16 or newer; one thread; no file access in a browser. See
  *Using it from JavaScript* in the user guide.
- `CubicalImage.fromImageData` (Scala.js) builds a cubical complex from the pixels of a canvas.
- The benchmark harness (`bench/`) times the Scala.js build, installed as the npm package and run on Node.js, beside the
  JVM build and the other tools on the same machine and inputs: `bench/run.sh setup tda4j-js`, then the `tda4j-js*` tools
  of `run` and the `js-*` engines of `sweep-cubical`; `summary.md` has a section comparing each with its JVM twin. `--node-heap`
  and `--node-opts` set Node's heap and flags. `run.sh setup` can install one tool (`setup tda4j-js`), and takes the JVM
  classpath from the `tda4j` project (the root only aggregates).
- `Parallel`, `FileAccess` and `CubicalImagePlatform` exist once per platform; on Scala.js `parallelFiltrationValue` and the
  DQP alpha complex's `parallel` give the same answer on one thread.
- `DenseVector` and `DenseMatrix`, small immutable `Double` vectors and matrices with value equality, `matrix.svd` (the
  compact singular value decomposition by Jacobi rotations, with `rank` and the pseudo-inverse `solve`), `matrix.lu()`
  (partial pivoting, `isNonSingular`, `solve`); `ConjugateGradient.solve(operator, b)` for a symmetric positive definite
  operator given as a function; `Erf.erf` and `Erf.erfc`. Pure Scala, so they also build for Scala.js.

- Customizing a plot, by chaining: `Plot.diagram(d).withLabels(title = ..., x = ..., y = ...).withXYScale(Transform.Log10)
  .withSize(960, 540)`, with `withXScale`/`withYScale` (scale, `min`, `max`, `breaks`, `format = "%.1f"`, gridlines),
  `withColorScale(min, max, stops, transform)` for a heatmap's or a cocycle's colors, `withDegree(1, color = ..., label =
  ..., shape = ...)`, `withPalette(Palette.reference)` and `withStyle(Style(font, textScale, markScale, grid, legend,
  transparent))`; the preset `Style.slides`. Each is also a value, a `Tweak` (`Labels`, `Size`, `Scale.x`, `Scale.color`,
  `Scale.degree`, `Palette`, `Style`), added with `+`: tweaks of different parts add in any order, `a + b` of tweaks is a
  tweak (a look kept in a `val`), `Tweak(f)` makes one from a function. Boards (`Plot.side`) and 3-D scenes take them too, where they
  apply. `Transform.Log10`, `Sqrt` and `SymLog(c)` place every value: what is at 0 on a log axis (an `H0` bar's birth)
  sits on a `-∞` line, as an essential bar ends at `∞`; a log axis with a negative value is a symmetric log and says so under
  the plot; the square root keeps the sign. Only a value that is not a number is left out, and counted. `Palette.withSeries` and `withSurface` make a palette of your own; `ColorScale.viridis` and `greys` are ramps.
- Two looks for publication, each a palette and a style. For a paper, `Palette.print` and `Style.print`: black on a
  background of exactly white (not the default's cream, which prints as a grey patch), degree colors that stay apart for
  every color vision and read on white, a heatmap ramp that keeps its order in grayscale, and named fonts instead of
  `system-ui`. For a figure with no background that goes onto a page of either color, `Palette.clear` and
  `Style.clear`: black elements in the light theme, white ones in the dark theme, each with colors checked on its own
  page color. By name in MATLAB (`withPalette('print')`, `withStyle('clear')`) and on the command line (`--palette
  print --style print`, `--palette clear --style clear --theme dark`); `Style.named` and `withStyle(name)` take
  `default`, `slides`, `print` or `clear`.
- Finer control of a plot's look, by element: `Text(role, size, weight, italic, align, angle, font, color)` for each of the
  eight pieces of text (`Title`, `Subtitle`, `AxisLabel`, `TickLabel`, `Legend`, `LegendTitle`, `ColorTitle`, `Note`),
  with the margins following the text; `Lines.grid`, `minorGrid`, `axis`, `ticks`, `minorTicks` and `frame` (width, color,
  dash, opacity, tick-mark length and direction); `Guide.panel` (the plot area's fill) and `Guide.diagonal` (the shaded
  half plane of a diagram); `Scale.x(position = Side.Top)` and `Scale.y(position = Side.Right)`. Chained as `withText`,
  `withGridLines`, `withTickMarks`, `withFrame`, `withPanel`, `withDiagonal`, ...; written as text for MATLAB and Java
  (`withSetting("title.size=18")`) and the command line (`--set title.size=18`, repeated). They sit beside the style, so a
  `Style` added later does not undo them. Default output is unchanged.
- Markers: `Geom.points` (`withPoints`) sets the shape, size, opacity, open or filled, and the **edge** of every point's
  marker, which can be removed (`edgeWidth = 0`); `Scale.degree(k, marker = MarkerStyle(...))` does it for one degree.
  Eleven more shapes (`TriangleDown`, `TriangleLeft`, `TriangleRight`, `Pentagon`, `Hexagon`, `Star`, `Dot`, strokes `Plus`,
  `Cross`, `VLine`, `HLine`) and `Shape.Glyph("★")`, text as a marker. A shape is also a string for MATLAB, Java and the
  command line: a name, a matplotlib code (`o s ^ v D + x *`), an R or ggplot `pch` number (`pch:1` is an open circle) or
  `glyph:text` (`withSetting("marker.shape=pch:1")`, `--set degree-1.size=6`). `Geom.segments`, `polygons`, `discs` and
  `lines` change a barcode's bars, a complex's triangles and balls. A 3-D scene's points take a size, an opacity and an edge.
- The legend can go to the left, the top, or a corner inside the plot area (`LegendPosition.Left`, `Top`, `UpperLeft`,
  `UpperRight`, `LowerLeft`, `LowerRight`); `Style.legend` is optional, so that a style that names no place leaves a plot its
  own. `Plot.barcode(d, order = BarOrder.Persistence)` sorts the bars longest first (or by `Death`).
- Axes and numbers: `Scale.x/y(reverse = true)` runs an axis backwards (a diagram's shaded half plane and a heatmap's boxes
  follow), `padding` sets the room around the data, `Scale.aspect(ratio)` / `withAspect` the length of a y unit over an x unit
  (`free = true` for none), `Transform.Custom(name, forward, backward)` is a scale of your own. `Numbers(decimal, grouping,
  minus)` and `Numbers.locale("de")` (`withNumbers`, `withLocale`) write the numbers of the tick labels and the color bar
  the way a locale or a journal does; `Text(role, limit = px)` cuts a text that is too long with an ellipsis, the whole text
  its tooltip.
- `Labels(caption, tag, description)` (`withCaption`, `withTag`, `withDescription`): a line under the plot, a panel letter in
  the corner, and an SVG `<desc>` for a screen reader (a barcode and a diagram describe themselves); `Board.tagged()`
  letters a board's panels `(a)`, `(b)`, ...; `Size.inches/cm/mm/pt` give a size in the units of a journal template.
  Settings: `x-axis.reverse`, `x-axis.padding`, `aspect`, `numbers.locale/decimal/grouping/minus`, `size.width=3.4in`,
  `caption.size`, `tag.weight`, `legend.limit`, ... (MATLAB `withSetting`, CLI `--set`).
- Defaults for every plot: `given Look` (`Look.print`, `Look.clear`, `Look.none`, `Look.settings(...)`): every plot
  constructor starts from the `given Look` in scope, then comes what the call names (a `palette`) and what you chain on, so a
  house style is said once. The `palette` parameters of the seven constructors that have one are `Optional[Palette]` now, so that naming one
  beats the look. MATLAB and Java have `TDA4j.setPlotDefaults(settings)` and `clearPlotDefaults()`; the settings `palette=`
  and `style=` take a palette or style by name.
- Panels on a grid: `Plot.layout(rows, cols, width, height, gap, widths, heights)` is an empty `Board` on a grid of cells,
  and `place(figure, row, col, rowSpan, colSpan)` puts a panel in it, spanning cells, so that one plot can run across a
  whole row or column. `Board.sharedColumns()` and `sharedRows()` put the panels over the same columns, or rows, on one
  scale. A board is saved as an SVG with each panel at the size of its cells, and on a page as a CSS grid.
- `Camera(azimuth, elevation, zoom)` (`Scene.withCamera`, settings `camera.azimuth/elevation/zoom`) sets the view of a 3-D
  scene: the saved `.svg` is that view, a page's canvas starts from it; a 2-D plot refuses it, and the CLI says so before
  computing.
- `Board.shared()` puts the panels on one scale; `Mark.Rule` (a line across the plot at one value) and
  `Mark.Diagonal(offset)` (the line `y = x + offset`, drawn in the plot's own scales).
- MATLAB: the figures returned by the `plot...` methods have the common tweaks as methods that take strings and numbers
  (`withTitle`, `withSize`, `withXScale('log')`, `withPalette`, `withDegreeColor`, `withTextScale`, `withLegend`, ...,
  and `save(path, 'dark')`); command line: `--title`, `--x-label`, `--y-label`, `--width`, `--height`, `--palette`,
  `--theme`, `--text-scale`, `--x-scale` and `--y-scale` for a `--plot`.
- `VantagePointTree(elements, distance)`: a vantage-point tree over any metric, with `withinDistance(query, radius)` (the
  boundary included) and `nearest(query, k)` (the first `k` of the stable sort by distance); and
  `MinimumEnclosingBall(points)`, the smallest ball around points in any dimension, with `center`, `squaredRadius` and
  `radius`. `SimplexIndexing.binomialInt` is `binomial` as an `Int`, for enumerating indices.

### Changed

- The Helix alpha backend (what `AlphaShapes` uses above 4-D) builds the faces of its triangulation in time linear in
  their number and makes the `Simplex` objects of a dimension only when it is read. 40 points in R^8 take 7 s (was
  105 s), in R^10 about a minute (the dimension sweep timed out after an hour); the simplices, values and order of the
  stream, and the barcodes, are the same bit for bit.
- A persistence diagram's legend is at its lower right, inside the shaded half plane, and the plot takes the room the legend
  column had. (`withLegend("right")` puts it back.)
- A barcode and a diagram carry a `<desc>` (their counts by degree) in the SVG.
- A board of panels with no background (`Style.clear`) saved as an `.svg` has no background rectangle of its own either.
- A plot's colors for data follow its palette: a persistence image, an image, a cocycle and circular coordinates hold
  their values (`Paint.Value`) and a `ColorScale` colors them when drawn.
- The `∞` line of a persistence diagram and its diagonal run across the whole plot.

### Fixed

- `fig.copy(palette = ...)` left heatmaps, cocycles' values and the color bar in the palette the plot was built with.
- `DistanceToMeasure` through the vantage-point search returned fewer than `k` neighbours for some `k` that is a sizeable
  fraction of the number of points (a defect of the Java library it used); it returns `k`.
- `CofacetIterator` reported vertices that are close to only some of the simplex's vertices as cofacets, and some cofacets
  twice; it gives each cofacet of the sparse complex once, in order of its diameter.

### Build, CI and Documentation

- The user guide's plotting page has a "Customizing a plot" section; the CLI and MATLAB pages point to it.
- CI starts every sbt command with `--server`. The sbt server that the thin client left running intermittently failed a
  job (most visibly `mima` and `test-js`) in setup-sbt's post-job cache step, after every step of the job had passed.
- The Scala.js test run is shorter: `APISpec` (a scratchpad for call syntax, its checks covered by other specs) is gone,
  and the alpha complex properties that search for rare inputs run all their cases on the JVM and a tenth on Scala.js.


## 0.6.2 (2026-10-15)

Accidentally released 0.6.1 before everything was properly merged.

### Breaking

### Added

### Changed

- `Engine.Chunks` at `maxDimension = 1` reduces a triangle only while a class of degree 1 is open (the others cannot
  close one), and compares cells by rank as the naive engine does: 70 points in the plane, the reduction about 1.2 s to
  0.27 s. From `maxDimension = 2` it still reduces every top cell (#51).
- `view()` serves its page with Undertow, through cask, instead of the JDK's built-in HTTP server, which crashed. The
  main artifact now depends on cask (and through it on Undertow and XNIO).
- A `Figure`, `Scene` or `Board` prints as one line (`Figure("Barcode", 50 marks)`), so a REPL that echoes the value of
  `fig.view()` no longer prints every coordinate of every mark.
- `Engine.Naive` is about ten times faster in the reduction on Vietoris-Rips input (70 points at `maxDimension = 1`: about
  5 s to about 0.4 s). The engine can be capped at a degree, `persistentHomology(stream, maxDimension)`: cells above
  dimension `maxDimension + 1` are neither sorted nor reduced, and a cell of that dimension is reduced only while a class of
  degree `maxDimension` is open and keeps no V-column (#51); cells are compared by rank, as in chunks. `Persistence`,
  MATLAB (`engine = 'naive'`) and the command line (`--engine naive`) use it, so `PersistenceEngine.naive` and
  `naiveCocycles` give no bars above a stream's `homologyDegreeLimit`, as the cohomology engine already did. The uncapped
  cursor is unchanged.

### Fixed

- `Engine.Chunks` on a stream in which some cell has no filtration value ordered cells by a rank table that left
  that cell out of the weak order; such a stream now keeps its own ordering (#88).

### Build, CI and Documentation

- `demo/`: a separate sbt build for a self-contained REPL jar (`java -jar tda4j-demo-<version>.jar`: Scala 3 and TDA4j
  in one file, imports already run), for talks and teaching. Not part of the library or its CI.
- `BarcodeRegressionSpec` runs again, on 25 - 30 points in 2 - 6 dimensions: the constructions cannot enumerate the
  original 25 - 150 points in 2 - 10 dimensions, whatever the engine (#51).
- `release.yml` builds the demo REPL jar (`demo/`) against the tag's own sources, runs it on a small example and attaches
  `tda4j-demo-<version>.jar` to the GitHub release.

## 0.6.1 (2026-10-15)

### Breaking

### Added

* Slides for a tutorial seminar on 2026-10-16.
* An extra-fat demo jar that launches its own REPL, allowing demos with only a modern JRE as installation requirements.

### Changed

* Replaced HTTP server for the plot viewer.

### Fixed

* #60 (Playground link versioning)
* #79 (Bug in `FiniteFieldSpec`, comparing unwrapped integers instead of finite field elements)
* #80 (Bug in `Plot.number` dispatching)

### Build, CI and Documentation


## 0.6.0 (2026-10-14)

### Breaking

- `sset.ClassifyingSpace.persistentGroupHomology` returns a `PersistenceDiagram` (bars with representatives) instead of
  a list of `(degree, birth, death)`; `diagram.triples` is the old list.
- `Chain` is an abstract class open to new storages: a storage implements `entryIterator` and inherits every other
  member (the library's are `HeapChain` and `PackedChain`). Its 0.5.0 constructor was `private[tda4j]`, so no
  user code built a `Chain` directly. Binary break only.
- `nanMask: Boolean = true` is a new last parameter of `CubicalGridStream`, `Image`, `CubicalImage.fromFlatArray`,
  `fromGrayscale2D`, `fromVoxelGrid3D`, `Perseus.readCubicalToplex` and `Dipha.readCubicalGridStream` (see Added).
  Defaulted, so callers need no change. Binary break only.
- `Chain.chainShow` needs only `Show` of the cells, not `OrderedCell`. A given found by implicit search, so
  callers need no change. Binary break only.
- `Chain` equality is equality of formal sums: the same cells, coefficients equal by the field's `isEqual`, in any
  order. Before, one sum over `F_p` could compare unequal to itself.
- A `NaN` pixel is the mask of a cubical image (missing data): it is not in the complex, in a sublevel and a
  superlevel image alike, under every engine (the verb, `CubicalImage`, `CubicalGridStream`, MATLAB, the CLI); before,
  it was refused. `±Infinity` are ordinary values, entering last or first. Perseus's `-1` (a missing cube) now reads as
  `NaN` (it was `+Infinity`, so a superlevel reading filled the hole first), and `Perseus.writeCubicalToplex` refuses
  infinite values instead of writing them as `-1`.
- `Chain.reduceBy` throws when its pivot moves back or stays put for 64 steps, instead of looping forever.

### Added

- `nanMask = false` turns cubical masks off: a `NaN` pixel is then an error naming the pixel, for data where a `NaN`
  can only be a bug. On `Image`, `CubicalGridStream`, the `CubicalImage` array constructors, the Perseus and DIPHA
  grid readers, MATLAB option `nanMask` and CLI `--nan-mask`.
- `plot`: barcodes, persistence diagrams, images, landscapes, point clouds, complexes, cycles and cocycles, circular
  coordinates and 3-D scenes as SVG, and `view()`, a live browser viewer. In the main artifact, with no dependencies
  of its own.
- Plots from MATLAB, Java and the command line (#63): `PersistenceResult.plotBarcode`, `plotDiagram`,
  `plotRepresentative` (over the points or image, optionally at a scale), `plotInput`, `plotComplex`, `plotLandscape`,
  `plotPersistenceImage`, the 3-D `plotInput3D`/`plotComplex3D`/`plotRepresentative3D`, and `mostPersistentBar`;
  `CircularCoordinatesResult.plot()`, `ToroidalCoordinatesResult.plot()`. Each returns a figure to `view()` or
  `save(path)` (`Plot.save(path)` is the overload Java and MATLAB call). CLI: `--plot KIND` with `--bar`,
  `--dimension`, `--at`, into `--output` or a page opened in the browser.
- Saved results: `PersistenceResult.save(path)`, `TDA4j.loadResult(path)` and CLI `--save-result FILE` write and read a
  whole result (every bar, the reported bars' representatives, the points or image, how it was computed) as JSON; a
  saved file is a CLI input (`--input-format result`, chosen by itself for such a file), to print, convert or plot
  without computing again.
- `Plot.cycle`, `cocycle`, `cubicalChain` and `cycle3D` also take a representative as plain `(cell, coefficient)`
  terms (`Plot.Terms`), so any real cochain can be drawn; `Viewer.browse` opens a page under the viewer's browser
  rules.
- `Field.toDouble(x)`: an element as a real number (the balanced integer for `Z/p`, the value for the reals), with a
  default for any field, so a field of your own plots with no change. `Plot.number` uses it (#80).
- `PackedCubicalCohomologyEngine`: Ripser's reduction on cubical grids. `Engine.Cohomology` on an image uses it.
- `PackedChain`: compact representatives for the grid engines; a `Cube` is built only when read.
- `Show` instances for diagrams, bars, endpoints, cubes, pairs and simplicial-set generators.
- `Persistence.Input` takes new kinds of input through a `Conversion`.
- `CubicalGridStream.topValues` and `EdgeCollapsedMetricSpace.edges`, read-only views of formerly private state.
- `sset`:
  - `FiniteCategory`, with `fromMonoid`, `fromPoset`, `freeOnAcyclicQuiver`, `actionGroupoid` and `homotopyOrbits`,
    and `CategoryNerve` with `filtrationBy`;
  - `FiniteMonoid.rectangularBand` and `reesMatrix`; `FiniteGroup.pSubgroups`, `alternating` and `generalLinear`;
  - `SimplicialSet.persistentHomology(filtration, maxDegree, prime)` for any simplicial set, with representatives;
  - `CollapsedNerve` (Brown's collapsing scheme) and `RewritingSystem.refining`.

### Changed

- Group homology (`persistentGroupHomology`, `bettiNumbers`) uses Brown's collapse whenever the subgroup chain refines
  to a polycyclic presentation, and the bar complex otherwise. H_*(S_4) through degree 6 is now in reach.
- The fast cubical engine and `Engine.Cohomology` on images are much faster.
- The construction streams behind `VietorisRips`, `Cech`, `Witness`, `Dowker`, `DtmRips` and `SparseRips`, the
  pairings, the involution, the signed union-finds, the Delaunay triangulation and its predicates, the matching
  algorithms and the io helpers are public. Every concrete public class is `open`.
- The chunks engine builds degree-1 representatives when they are first read.
- `Engine.Chunks` is about six times faster on Vietoris-Rips input (70 points at `maxDimension = 1`: about 12 s to about
  2 s): `diagramAt`, `barcodeAt` and `pairing` no longer repeat the reduction, and cells are compared by rank. Every top
  cell is still reduced (#51).

### Fixed

- `Plot.number` no longer decides by the element's runtime class: on Scala.js every `Fp` would have plotted as its raw
  `Int` form instead of the balanced representative (#80).
- The chunks engine's dimension-0/1 union-find handles every edge a chain complex can contain. Six inputs used to throw
  or return a wrong barcode, among them `a + b + c` over `F_3`.

### Build, CI and documentation

- CI builds the fat jar and runs the command line on a sample.
- Test and build tooling: specs2 5.9.1 (its typed `===` exposed `FiniteFieldSpec`'s two always-true properties, #79),
  sbt-scalafmt 2.6.2, sbt-assembly 2.5.0.
- The docs site's Playground link and snippet configuration load the last release, read from the git tags (#60).
- Contributor guides: `CONTRIBUTING.md` rewritten (setup, commands, issues and labels, review principles), issue and
  pull request templates, and this changelog.
- A coverage report, run by hand or weekly (`coverage.yml`) and sent to Codecov, where it is informational only.
- Dependabot opens weekly update pull requests for the GitHub Actions and the sbt dependencies.
- A format check before every commit, for anyone who opts in (`CONTRIBUTING.md`).
- The developer's guide covers the public API and its principles, nerves and group homology, plotting and measuring
  performance; the class diagrams render on the docs site.
- The release workflow runs only for `v*` tags and attaches the release to the tag it was started for.

## 0.5.0 (2026-10-09)

The first release of the 0.5 series, with the `Persistence` verb, `PersistenceDiagram`, the labs and the MATLAB and
command-line facades. It does not keep binary compatibility with 0.4.x.
