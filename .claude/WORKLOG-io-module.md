# WORKLOG: `io` module (file format adaptors)

Point-in-time record, per this project's worklog convention (`[[tda4j-worklog-convention]]`). Not retroactively
edited after the fact.

## Ask

"Create an io module with methods to load a range of useful file formats. At the very least, it should include csv
and the ripser format for packed distance matrices -- but having adaptors matching most main TDA projects would be
good."

## Scope decision (via `advisor()`, before writing code)

Ship only formats verified against a primary source (the originating project's own source code or its own
documentation, not a secondhand description). A wrong parser that silently produces a plausible-looking wrong
answer is worse than no parser at all -- several of this codebase's own worst historical bugs (see CLAUDE.md's
`filtrationOrdering`/axis-order history) were exactly this shape: internally consistent, wrong.

Shipped: **Csv** (this codebase's own generic delimited-text convention), **Ripser** (point-cloud, lower-distance,
upper-distance, dense-but-lower-read, packed binary), **DIPHA** (distance matrix, image data, persistence diagram
-- all binary), **GUDHI** (OFF/nOFF point cloud, `.pers` diagram), **Perseus** (cubical toplex, per-dimension
interval output). Dionysus and JavaPlex need no dedicated adaptor -- both consume the same plain
whitespace/comma-separated point-cloud and distance-matrix text `Csv`/`Ripser` already read; said so in `Csv.scala`'s
own doc rather than writing redundant code.

Explicitly NOT shipped, and why: Perseus's own simplicial toplex format and PHAT's boundary-matrix format -- no
primary-source verification was done for either in this session, so neither is implemented. Ripser's `--format
sparse` and DIPHA's `SPARSE_DISTANCE_MATRIX` (both `(i,j,d)` triplet formats) are also not implemented: tda4j has no
"only these edges are known, the rest are unknown rather than infinite" metric-space type (`SparseMetricSpace` is a
diameter-cutoff *wrapper* around an already-fully-known metric space, not that) -- a real, open design question
flagged rather than resolved by guessing a convention.

## Primary sources actually checked (not reconstructed from memory)

- **Ripser** (`github.com/Ripser/ripser`, `ripser.cpp`): `file_format` enum and `--format` help text; the exact
  `read_lower_distance_matrix`/`read_upper_distance_matrix`/`read_distance_matrix`/`read_binary` parsing bodies; the
  `compressed_lower_distance_matrix`/`compressed_upper_distance_matrix` `init_rows` pointer arithmetic (traced
  term-by-term to derive the upper-triangular flat order, not assumed symmetric with the lower case); the `read<T>`
  binary-read template (confirms the on-disk format is always little-endian, host byte order reversed only if
  needed); and, critically, **`typedef float value_t`** -- Ripser's binary format is packed 32-bit float, not
  64-bit double. This was flagged by `advisor()` as a blocking check before writing the binary reader: a
  write-then-read round trip would have passed with the wrong element width (4 vs 8 bytes) because both directions
  would share the same wrong assumption -- only a real ripser-produced file, or the typedef itself, would have
  caught it. Read via a fresh `WebFetch` of the raw source, not recalled.
- **DIPHA** (`github.com/DIPHA/dipha`): `include/dipha/file_types.h` (magic number `8067171840`, file-type enum:
  `WEIGHTED_BOUNDARY_MATRIX=0`, `IMAGE_DATA=1`, `PERSISTENCE_DIAGRAM=2`, `DISTANCE_MATRIX=7`,
  `SPARSE_DISTANCE_MATRIX=8`) and `README.md` (explicit "little-endian binary format" statement; exact `DISTANCE_MATRIX`/
  `IMAGE_DATA`/`PERSISTENCE_DIAGRAM` payload layouts, including the "`x`-fastest" axis-order statement and the
  `dim(k) < 0` => essential class of dimension `-dim(k)-1` convention).
- **GUDHI** (`gudhi.inria.fr`): the file-formats documentation page for OFF/nOFF and `.pers` (exact example text
  quoted, including the 2/3/4-column `.pers` grammar); and, because the file-formats page's own prose description of
  axis order for its Perseus-style cubical reader was not fully conclusive on its own, `Bitmap_cubical_complex_base.h`'s
  actual source -- specifically `compute_position_in_bitmap`'s multiplier construction (`multiplier *= 2*sizes[i]+1`,
  starting from `multipliers[0]=1`), which is the one place either project states its exact stride arithmetic in code
  rather than prose.
- **Perseus** (`people.maths.ox.ac.uk/nanda/perseus`): the cubical toplex format description (dimension line, `d`
  size lines, then values in "lexicographical order"; `-1` reserved for a missing cube) and the per-dimension
  output-interval format (`birth death` integer pairs, `-1` death = essential).

## Two details that would have silently produced a wrong-but-plausible answer

1. **Ripser's binary element width is `float` (4 bytes), not `double` (8 bytes)** -- see above. Pinned with a
   byte-level fixture (`RipserIOSpec`, hand-built 12 bytes = 3 little-endian float32 values), not a round trip.
2. **DIPHA's and Perseus's cubical-grid axis order is the OPPOSITE of `CubicalImage.fromFlatArray`'s own
   convention.** DIPHA states "`x`-fastest" (`g(1)` varies fastest); Perseus's own "lexicographical order" was
   confirmed, via GUDHI's `compute_position_in_bitmap` source, to mean the *first* declared axis is fastest-varying
   (`multipliers[0] = 1`). `CubicalImage.fromFlatArray` (this codebase's own convention, matching ordinary row-major
   `Array[Array[...]].flatten`) has the *last* shape axis fastest. Both `Dipha.readImageData`/`writeImageData` and
   `Perseus.readCubicalToplex`/`writeCubicalToplex` reverse the shape to reconcile this (a flat array with axis-0
   fastest is, by construction, already in row-major order for the *reversed* shape -- no data movement needed,
   just relabeling which axis is which).

   This is exactly the kind of bug a round-trip test cannot catch: writing and reading back through the same
   (self-consistently wrong) convention agrees with itself, and *transposing a grid preserves its homology*, so
   even a full persistent-homology comparison against an un-transposed oracle can pass by coincidence on a
   symmetric-shaped fixture. Pinned instead with hand-built-bytes, ASYMMETRIC-shape (3x2, not 2x2 or 3x3),
   ALL-DISTINCT-value fixtures in both `DiphaSpec` and `PerseusSpec`, asserting `CubicalGridStream.topCellValue` at
   specific coordinates -- plus a cross-format oracle test (`PerseusSpec`: the identical grid built via a hand-typed
   Perseus text file and via `Dipha.writeImageData` must produce the same `CubicalGridStream` at every coordinate),
   confirming the two adaptors' reversals are not just individually self-consistent but mutually consistent.

## A real off-by-one bug caught by the test suite itself (not by primary-source checking)

`Csv.readLowerTriangularDistanceMatrix` originally computed `n = rows.size` from the physical line count. But
`writeLowerTriangularDistanceMatrix` (by design, to avoid an empty first line) omits row 0 entirely -- row `i`
(`i = 1 until n`) is the only content written, so an `n`-point matrix is `n - 1` physical lines. The correct
formula is `n = rows.size + 1`. Caught immediately by the first test that used a genuinely asymmetric ($n=3$,
all-distinct-value) fixture rather than a round trip alone (a round trip of `write` then `read` through the SAME
off-by-one bug would have silently "passed" by symmetrically shrinking the matrix on both sides -- another instance
of the general "round trip can't catch a self-consistent convention error" lesson above, this time for something
this codebase invented itself rather than copied from elsewhere). Fixed at the source; regression pinned in
`CsvSpec`.

## A real test-fixture bug caught while writing the upper-vs-lower discriminator

The first version of `RipserIOSpec`'s upper-vs-lower distance-matrix test used $n=3$ (3 raw tokens) and asserted
`upper != lower` on the same tokens. This is a **coincidental non-discriminator**: for $n=3$ specifically, the lower
convention's pair-visitation order ($\{0,1\}, \{0,2\}, \{1,2\}$, from rows $i=1,2$) happens to exactly coincide with
the upper convention's own order ($\{0,1\}, \{0,2\}, \{1,2\}$, from rows $i=0,1$) -- both are literally the same
sequence of unordered pairs at $n=3$, purely because there are only 3 pairs total and both row-major traversals
happen to visit them in the same order. The two conventions provably diverge starting at $n=4$ (lower's 4th pair is
$\{1,2\}$; upper's 4th pair is $\{0,3\}$) -- confirmed by deriving both traversals by hand before rewriting the
test, not just picking a bigger $n$ and hoping. The test now uses $n=4$ (6 distinct values) and asserts specific,
different values at specific coordinates under each convention, matching advisor's own general guidance:
"a symmetric-ish matrix won't [discriminate]," which turned out to apply to small-$n$ *asymmetric-looking* fixtures
too, not just literally-symmetric ones.

## API shape

Two layers per format family, per `advisor()`'s explicit recommendation:

- **Raw loaders**: `Array[Array[Double]]` (point clouds, dense matrices), `(shape, flatValues)` tuples (cubical
  images, in `CubicalImage.fromFlatArray`'s own convention), `Seq[PersistenceBar[Double, Nothing]]` (diagrams).
- **Thin convenience constructors** on top: `read*EuclideanMetricSpace`/`read*ExplicitMetricSpace`/
  `read*CubicalGridStream`, one line each, wrapping the raw loader.

`RipserPaperBenchmarkSpec`'s own pre-existing `loadPointCloud`/`loadDistanceMatrix` helpers (hand-rolled identical
trim/split/parse logic, predating this module) are now one-line delegations to `Csv.readPointCloud`/
`Csv.readFullDistanceMatrix` -- the cheapest available proof that the new API is actually usable in a real caller,
not just its own test suite, per advisor's suggestion.

Persistence-diagram round-tripping needed one more piece of care: `PersistenceBar.apply(dim, lower, upper)` (the
existing companion factory used throughout this codebase) produces `ClosedEndpoint(lower)`/`OpenEndpoint(upper)` --
the half-open `[birth, death)` convention -- but a file format's two raw numbers carry no open/closed distinction at
all. Reading them back as `ClosedEndpoint`/`ClosedEndpoint` (the naive choice) would make a bar built the ordinary
way fail to round-trip (`OpenEndpoint(4.0) != ClosedEndpoint(4.0)` under case-class equality) even though nothing is
actually wrong. Fixed by having every text-based reader (`Endpoints.toBar`, shared by `Csv`/`Gudhi`) and DIPHA's own
binary reader reconstruct finite bars via the SAME `PersistenceBar.apply(dim, lower, upper)`/`apply(dim, lower)`
factories a normal caller would use, rather than hand-building `ClosedEndpoint` on both ends -- caught by the first
round-trip test written for each format, not by inspection.

## Layering

`io` is a leaf: it imports `streams` (`EuclideanMetricSpace`/`ExplicitMetricSpace`/`CubicalGridStream`/
`CubicalImage`) and `barcode` (`PersistenceBar`/`BarcodeEndpoint`). Nothing outside `io` imports it back --
consistent with this codebase's existing package-layering discipline (see CLAUDE.md's `cells`-vs-`streams` note on
why `identify`'s union-find lives in `cells` rather than reusing `streams.UnionFind`).

## Verification

37 new examples across 5 specs (`CsvSpec`, `RipserIOSpec`, `DiphaSpec`, `GudhiSpec`, `PerseusSpec`), all passing,
plus a real end-to-end check per advisor's suggestion: `PerseusSpec`'s "-1 missing pixel" test builds a 3x3 grid
with the center pixel missing (mapped to `Double.PositiveInfinity`), runs it through the actual
`CellularHomologyContext`/`persistentHomology` machinery, and asserts a genuine ESSENTIAL dimension-1 bar exists --
the topologically correct signature of an 8-pixel ring (homotopy-equivalent to $S^1$) rather than merely "the loader
didn't crash." Full `sbt test`: 299 examples, 0 failures, 0 errors (294 passed, 5 skipped, 1 pending -- the
pre-existing skip/pending count, unaffected by this session). `sbt scalafmtAll`/`scalafmtCheckAll` clean.

## What's still open

- Sparse distance-matrix formats (Ripser `--format sparse`, DIPHA `SPARSE_DISTANCE_MATRIX`) -- needs a design
  decision on what tda4j type should represent "only these pairs are known" before it can be implemented (advisor's
  suggested cheapest landing: materialize into `ExplicitMetricSpace` with `Double.PositiveInfinity` for absent
  pairs, matching `SparseMetricSpace.distance`'s own past-cutoff convention -- not attempted this session).
- Perseus's simplicial toplex format and PHAT's boundary-matrix format -- deliberately not implemented, no
  primary-source verification done.
- Boundary-matrix export generally (PHAT's own format, or any other) remains unimplemented across the whole
  codebase -- already flagged as a gap in the MATLAB facade's own doc (`PersistenceResult`), unrelated to this
  session's scope.
