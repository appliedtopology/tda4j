# Changelog

What changed in each release of `tda4j`, newest first. Every pull request that changes what a user sees or calls adds a
line under **Unreleased**; at a release the section is renamed to the version (`RELEASE.md`). Each section opens with
the changes that break existing code. Before 1.0, a minor version may break the API, and every break inside a series
is named here and in a commented `mimaBinaryIssueFilters` entry in `build.sbt`.

## Unreleased

### Breaking

### Added

### Changed

### Fixed

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
  cell is still reduced (#51). `BarcodeRegressionSpec` runs again, on 25 - 30 points in 2 - 6 dimensions.

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
