# More inputs: time series, graphs, lower star, directed flag (2026-10-29)

Context: Mikael's "yes to all" on the non-cloud inputs of the gap survey (Takens, graph geodesics, lower-star, directed
flag, sparse graph input that stays sparse).

What landed
- `TakensEmbedding` (windows, vector series, `delayByAutocorrelation`), `GraphMetric.geodesic` (Dijkstra from every
  source, dense `ExplicitMetricSpace`) and `nearestNeighbours` (Isomap graph), `LowerStar` (every face listed with its
  value into `fromFilteredFacets`), `DirectedSimplex` + `DirectedFlagStream`, `WeightedGraph` + `FlagComplex`,
  `Ripser.parseSparseGraph`/`readSparseGraph`.
- `Persistence(graph)`: `Input.ofMetricSpace` routes a `WeightedGraph` to `FlagComplex` and denies the Ripser engines
  (`ripserApplies`, `parallelRipserApplies`), so `Auto` is `Cohomology`. `VietorisRips(graph)` still works and builds the
  dense tables; `FlagComplex` is the entry point for a large sparse graph.

Oracles
- Directed flag: the complex of injective words (Farmer; Bjorner and Wachs). The complete digraph on n vertices has the
  homotopy type of a wedge of D_n (n-1)-spheres, D_n the derangement numbers: Betti numbers [1,1], [1,0,2], [1,0,0,9]
  for n = 2, 3, 4. A transitive tournament has one cell for every subset and the homology of a point. The same numbers
  over Z/2 and Z/3.
- `FlagComplex` against `VietorisRips` on the dense matrix with `Infinity` for non-edges: the same cells and values on a
  random graph, and the same barcode as the Ripser engine in degrees 0..2. A mutant (`max` to `min` in the reach update)
  fails three of the examples.
- The path with 40,000 vertices computes under the 2 GB heap, where its matrix would be 12.8 GB.
- Antipodal geodesic distance on a 100-point circle is pi to 1e-3 (a metric through the plane would say 2).

Traps
- `DirectedFlagStream` first sorted each dimension inside the same `lazy val (values, byDimension)` that builds `values`:
  the ordering reads `values`, so the sort re-entered the initializer and never returned (a hung spec, 595 s timeout).
  Values and sorted dimensions are separate lazy vals now; `FlagComplex` is built the same way.
- The rename `significant` to `longerThanFraction` in PR 1 missed three call sites of a local helper in
  `MatlabTabsSpec` (`src/test/scalajvm`), which CI compiled and a local run of only the new specs did not.
  Rename sweeps now grep the call sites of any local helper too.
- A `Show`/boundary spec over a finite field must name the field (`import GF3.{Fp, given}`, `boundary[Fp]`); a
  `summon[Fp is Field]` there resolved to a different field and printed `-1` for a sign.

Not done
- No CLI or MATLAB route to `WeightedGraph`: their inputs are dense arrays by contract. `--input-format ripser-sparse`
  still builds the matrix.
- `FlagComplex` has no vertex values (`LowerStar` and `DirectedFlagStream` do).

# Birth and death cells on bars (2026-10-30)

`PersistenceBar` has a fifth field, `cells: Option[Involution.Pair[?]]`, left out of `equals` and `hashCode` (bars compare
by interval and representative, so no existing comparison changes); `bar.birthCell`/`bar.deathCell` (extensions in the
companion) give the cells typed by the chain's cell type. Recorded where the pairing is at hand: the generic cohomology
engine (`cellHomology`, `paired`), its packed levels, the packed Ripser engine (`Pairing.bar`, decoded to simplices in
`Persistence.ripser`), the parallel engine (a `barRows` list beside `barColumns`: no `withPairs` needed, so a cocycle run
keeps nothing extra), `Involution.cocycleBars`, the naive engine (`pairedCells(i)` is aligned with `barcode(i)`) and the
chunked engine (`chain.leadingCell` is the birth cell, `killer` the death cell). `PersistenceDiagram.at(f)` keeps the
birth cell of a bar it cuts off and drops its killer.

Oracle: a textbook column reduction of the boundary matrix over F_2 in the spec, under each engine's own order of tied
cells: the generic cohomology engine orders ties by the stream's tie-break ascending, the homology engines by its reverse,
and Ripser by the combinatorial number system descending (found by trying the candidates; it fixes the convention, and
the decode from packed indices to simplices is checked by it). Degree 0 is where conventions show: all vertices tie at 0, so
which vertex a merge kills differs between Ripser and the generic engine, while the bars' values do not.

Not done: the MATLAB result, its file format, the CLI and JavaScript do not expose cells (their result file has no field
for them; adding one is a format change); PackedCubical and FastCubical record none.

# Maps of persistence modules (2026-10-30)

Starting point: `Barcode.image/kernel/cokernel` (reduced matrices of a DenseMatrix, real coefficients) disagreed with the
rank of the structure maps on random legal maps. A probe over 150 instances: image wrong 28, kernel wrong 10 and crashed
61, cokernel wrong 3 and crashed 61. So the algebra was rebuilt, not repaired.

`MapAlgebra` (any `Field`): image = elder rule over source generators in birth order, target components led by max death,
the vector restricted to the components alive at the generator's birth; cokernel = relations in time order (a target bar's
death before the image of a source generator born then), generators ordered by birth, a relation that does not reduce
kills the youngest generator in it; kernel = cokernel of the transposed map between the reflected modules. The oracle is
`MapAlgebraSpec`: for all pairs of critical times the rank of the structure maps of image, kernel, cokernel computed by
independent `Z/p` linear algebra (`ZpLinearAlgebra`), over Z/2, Z/3 and Z/5, 150 random instances each.

`PersistenceMap`: the matrix is read from the target's representatives. The pushforward of a source cycle is cleared
against the target's birth cells, highest rank first, where the rank is the depth in the DAG "birth cell k occurs in the
support of the representative of bar j"; no engine order is needed. A bar alive at the source bar's birth records the
coefficient; a dead bar's representative is a boundary there and records nothing.

Trap found by the brute-force oracle (degree 0, Naive and Chunks engines): those engines' finite-bar cycles are the `V`
column of the birth cell (a single vertex in degree 0), not `R_tau`. The two are both bases of the homology at each time,
but `V` stays a nonzero class after the bar's death, so eliminating a dead bar's birth cell with it changes the class, and
the source cycles are not module generators either. The result was a wrong matrix and no error. Fix: diagrams carry
`representativesDieWithTheirBars` (true for cohomology-derived cycles: Cohomology, Ripser, ParallelRipser), and
`PersistenceMap` refuses the rest and cocycles. Recovering `R_tau` from `V` would need the boundary chains, which the
diagram does not keep.

Oracle for `PersistenceMap`: the filtered complexes are built in the spec by brute force (Vietoris-Rips up to the needed
size), the chain map is written out again with its own sign and checked to commute with the boundaries, and the ranks of
`f(H(X_s)) -> H(Y_t)`, of the kernel at `s` mapped into `H(X_t)` and of `H(Y_s)` modulo the image at `t` are computed as
dimensions of sums of subspaces of the chain groups. Cases: identity (all engines that qualify, F2/F3/F17), subsample
inclusion, every-other-point through a vertex map, the projection of a ring with doubled points (a merging map, the
target numbered by random permutations), an octahedron inside a larger sample in degree 2, and the five-vertex Mobius band
(boundary circle goes to twice the core: zero over Z/2, iso over Z/3 and Z/17). A mutant that drops the permutation sign
passes the first version of the projection test (a monotone vertex map has no sign) and is caught once the numbering is
permuted; an overall sign changes no rank, so only a sign that varies between simplices can be tested this way.

Gotcha for users, now in the docs: a subsample's default `maxFiltrationValue` (its enclosing radius) differs from the
sample's, so the two complexes are not nested at the top; pass the same value. `ParallelRipser` cannot report zero-length
bars, so it can be a source and not a target.

Not done: relative persistence (next), the MATLAB/CLI/JS exposure of maps (their result format has no place for them), a
map from a cell map given on cubical cells with orientation (the general `cellMap` form takes any cells, but only the
simplicial form has a sign convention built in), and a `PersistenceMap` that computes its own diagrams from two inputs.

# Relative persistence (2026-10-30)

`Relative(stream, inSubcomplex)` wraps each cell of `X` outside `A` in `RelativeCell(cell)(context)` (the second parameter
list is not part of equality); the `OrderedCell` instance in `RelativeCell`'s companion takes the dimension and order from
the context's ambient instance and drops the faces in `A` from the boundary. The filtration value, the filtration order
and `homologyDegreeLimit` are those of the stream, mapped through `.cell`. Chosen over the cone `X ∪ CA`: the cone gives
the relative homology only in degrees >= 1 (in degree 0 the unreduced homology of the cone has one more class than the
pair for t past the first cell of `A`, and before it there is no cone), so its barcode differs in degree 0.

Oracles: (disc, circle), the cone on a pentagon with the circle at 0 and the cone at 1: one essential bar in degree 2 born
at 1, over Z/2, Z/3 and Z/17, for the generic cohomology, naive and chunked engines. The rank of `H(X_s, A_s) -> H(X_t, A_t)`
from the relative chain groups over `Z/p` (`RelativeSpec.disagreement`), at all pairs of filtration values of a Rips
complex of 8 points modulo three of them, degrees 0 to 2, three engines, Z/2 and Z/3. (X, vertex): the reduced homology.
My first expectation for it, that a degree-0 class dies at the bottleneck distance to the vertex, was wrong: the
dimension of `H_0(X_t) / <[v]>` drops once per edge of the minimum spanning tree, so the deaths are the minimum spanning
tree's, the same as without the vertex; the rank oracle had it right and the hand-derived expectation did not.

Not done: a cell type other than `Simplex` has no convenience constructor (the predicate form takes any cells);
`A` closed under faces is documented and unchecked (the lab principle); the representatives are chains of `RelativeCell`s.

# CLI modes and Takens in the facades (2026-10-30)

`--bootstrap-band`, `--tomato` and `--takens-dimension` (with `--takens-delay`/`--takens-max-lag`) in `TDA4jConf` and
`TDA4jCLI`, modeled on `--euler-curve`: modes of their own (`checkModes`: at most one, their flags only with them),
`refuseOtherFlags` names every flag that would change a computation the mode does not do, and the output is what the
facade call returns (`key=value` lines for the band, labels or `birth,death` lines for ToMATo). Takens is an input
transformation (`applyTakens`, right after the input is read) so it composes with the diagram, the Euler curve and the
other two; `--takens-max-lag` is for a one-column series and says the chosen delay on stderr. `TDA4j.takensEmbedding`,
`takensEmbeddingOfVectors`, `delayByAutocorrelation` are new in the facade (two overloads each, stride optional) and in
the JS exports and `index.d.ts`. Specs compare each CLI line with the facade call of the same arguments (`ModesCLISpec`).

# Bar cells in the facades (2026-10-30)

`PersistenceResult.birthCell(i)`/`deathCell(i)` return a cell in `cycleVertices`' format (empty: none recorded, or an
essential bar's death). `TDA4j.fromBars` builds them from `bar.cells` with `cellVertices(dim, birth)` and
`cellVertices(dim + 1, death)` (the Ripser engine's packed cells need the size to decode; mutating the `+ 1` fails the spec).
Result file: optional `birthCells`/`deathCells` parallel to `dimensions` (all bars, hidden ones too, since a cell is a few
ints), written only when some bar has a cell; one without the other, or a list of the wrong length, is refused on read;
format version stays 1 because older readers skip unknown keys. JS `Result.barCells(i)` gives `{birth, death}` with `null`
for none; CLI `--bar-cells` appends `cells: birth [..] death [..]` to the text lines (refused with the other modes,
`--distance-to`, `--select-landmarks`, `--plot` and any other output format).

`PackedCubicalCohomologyEngine` now records the cells (`Rows.bar` passes an `Involution.Pair`), so `engine=cohomology` on an
image has them; the oracle is the loop around a ring of 0 pixels: its death cell is the middle pixel, birth one of its edges.
`FastCubicalHomologyEngine` (H0 by union-find, H_{d-1} by the dual union-find) and `FastAlphaHomologyEngine` record none;
the H0 side has the edge and the young root vertex at hand, the dual side the facet and the root pixel, but the tie order
of each would need its own textbook oracle (#271).

Facade-vs-verb check: for each of `ripser`, `naive`, `chunks`, `cohomology` and each kind of representative the facade's
cells are the verb's for the same engine. Across engines the pairs differ (ties: all vertices enter at 0, an edge and its
triangle at one value), which `BarCellsSpec` pins per engine, so no cross-engine equality is asserted.

# Exact rational coefficients (2026-10-30)

`Coefficients.Rational` (nested in the `Coefficients` companion: a top-level `Rational` would join every user's wildcard
import) is a `BigInt` pair in lowest terms with a positive denominator, equal values for equal numbers, `Field.hash` its
hash; `Coefficients.Rationals` is the runtime selector (characteristic 0, like the reals). The verb takes it as
`coefficients: Optional[Coefficients]`, which replaces `characteristic` (the default characteristic alongside it is
tolerated, another one is an error); inside, `solve`/`compute`/`fastCubical`/`Input.ripser` take `Coefficients` where they
took the Int, and only the block-parallel engine keeps the prime (`coefficients.characteristic` is 0 for Q, which
`ParallelRipser.supports` refuses). `PersistenceMap` compares the field class when the characteristic is 0, because the
unchecked cast it uses would turn a real/rational mix into a `ClassCastException`. Facades: `field=Q` (and `--field Q`);
a coefficient is reported as the nearest `double`.

Oracles: field laws on random fractions with parts up to 2^40 and the 10^30 + 1 case; the 6-vertex projective plane
(Betti 1,1,1 over Z/2, 1,0,0 over Z/3 and Q, for Cohomology, Naive and Chunks); the bars over Q equal those over Z/10007 on
random clouds for every engine and both kinds of representative; representatives closed exactly; the Möbius band map
multiplies the loop by exactly 2. The tests do not distinguish Q from the default Z/17 by bars alone (no torsion at 17), so
the field is pinned by `1/2` being `0.5` in the diagram's field.

Measured once (not an A/B): random 3-D clouds, 40 and 80 points, degrees 0..2, Ripser and Cohomology engines: the rational
run took as long as the Z/17 run (26 against 37 ms, 78 against 78 ms, 41 against 68 ms, 174 against 186 ms), so no slowdown
is claimed or ruled out for larger complexes; coefficient growth was not looked at.

# Python package (2026-10-30)

`python/` is the `tda4j` wheel: JPype over `org.appliedtopology.tda4j.matlab.TDA4j`, NumPy in and out (`rules/python.md`).
Chosen over a Py4J gateway or a native binding because the facade already takes and returns only `double[][]`, `int[]` and
`String[]`, which JPype converts without copies on the way in and with one pass on the way out, and because a wheel that
only bundles the fat jar has nothing to compile and no platform tags (`py3-none-any`, about 30 MB). The project lead's
condition was "if it is easy to do and easy to maintain": the maintenance cost is the mirror rule (a facade method gets a
Python function), made mechanical by `tests/test_facade_coverage.py` (reflection over the public facade and result classes
against the calls the package makes; the reflection also sees Scala's `private[matlab]` members, which `INTERNAL` filters).

Conversions are in `_jvm.py`: `points_in` (`np.ascontiguousarray(..., float64)` then `JArray(JDouble, 2)`), `options_in`
(snake_case to camelCase; booleans `true`/`false`; numpy scalars through `int`/`float`, because `repr(np.float64(0.1))` is
`np.float64(0.1)` under NumPy 2; `inf` as `Infinity`), `matrix_out` (a Python loop over `double[][]` rows: fine to a few
hundred thousand bars, the only part that would need a flat accessor in the facade if a user hits it). Errors:
`IllegalArgumentException` is `ValueError`, so the facade's messages (which list the accepted options) reach the user
unchanged; the options list is lowercased there because `parseOptions` lowercases.

Found while testing: a frozen dataclass with NumPy fields has an `__eq__` that raises (`eq=False`); `Result.cells()` is in
the engine's order (VR layers: all edges, then all triangles), not globally sorted by filtration value, so the invariant
to test is "every face enters no later than its cell"; the degree-0 birth cell of an image is a corner vertex of the lowest
pixel, not the pixel; circular/toroidal coordinates refuse a symmetric lattice (a genuine tie), so a torus fixture needs
noise; ToMATo with an enormous `tau` still gives one cluster per connected component of the neighbour graph, so a
"merges everything" test needs a connected sample.

Checks: 75 tests in `python/tests` (unit square by hand, `∂∂ = 0` and face-before-coface on `boundary()`, a cycle's
boundary summing to zero, bar cells, Takens windows by index arithmetic, an Euler curve counted by hand, bottleneck and
Wasserstein of a square against its double by hand, relative modulo one point, the inclusion map's loop story, image
orientation, save/load round trip). Mutants caught: boundary rows and cols swapped, Takens delay and stride swapped, points
read column-major, a boolean option written `True`, the death cell of an essential bar left empty, a facade call renamed.
The wheel was built and the tests run against the installed copy from a clean venv on Python 3.10, 3.11 and 3.13 (the CI job
`python-package` does 3.13 and 3.10).

Not done, on purpose or for lack of a decision: PyPI publishing is wired (`release.yml`, Trusted Publishing, repository
variable `PUBLISH_TO_PYPI`) but needs the pending publisher created on pypi.org by an owner, and the name `tda4j` checked
for availability; `TDA4j.layout` (free-form boards) is not wrapped; the typed Scala API (streams, engines, cursors) and
`sset` are not reachable from Python, by design (the package is the facade's); free-threaded Python and several JVMs per
process are untested (JPype starts one JVM per process).

The limits this arc leaves (maps on cubical complexes, `PersistenceMap` computing its own diagrams, rational coefficient growth, PyPI setup, the unwrapped `layout`) are filed as #272; bar cells from the fast engines are #271.

## Products, Hom and duals (2026-10-30)

Asked for by Mikael with zigzag, Morse and the Laplacian ("I don't care much which order"). Order taken: this first, because
the Künneth theorem gives an oracle that uses the engines.

- Hand derivations that the specs then confirmed: `[I[a,b), I[c,d)] = I[max(c-a, d-b), d-a)` (nonzero maps `I[a,b) -> I[c-t, d-t)` iff
  `c-t <= a < d-t <= b`); Day convolution of intervals `[a+c, a+c+min(b-a,d-c))`; `Tor_1^{k[R≥0]}` of two finite intervals `[max(b+c,a+d), b+d)`.
- `Sum` filtration needs the Tor term, and circles alone cannot show it (a bar that never dies is free): the Tor class appears
  only between bars that die, the H_0 bars of Rips complexes among them. The two-segment product is the smallest case.
- Spec slips worth knowing: a `private val p` in a specs2 `Specification` collides with specs2's own `p` (Fragments); `hom` is graded
  (`q - p`), so comparing with `homDimension` needs `.filter(_.dim == 0)`.
- Not done: the product's bars through the facades (`ProductCell` has no vertex-array form). Facade `productBars` works on rows.

## Persistent Laplacian (2026-10-30)

- Formula used (Wang-Nguyen-Wei, Memoli-Wan-Wang): with the rows of `partial_{k+1}` of `K_t` split into those of `K_s` (`D`) and
  those of `K_t \ K_s` (`R`), `Delta = D (1 - R^+ R) D^T + A^T A`. Implemented as `Delta = X X^T` with `X = [A^T | D (1 - V_r V_r^T)]`,
  `V_r` the right singular vectors of `R` above the SVD tolerance (`DenseMatrix.svd`, one-sided Jacobi), so eigenvalues are squared
  singular values of `X` and the kernel is `n - rank X`: no squaring of the condition number.
- Oracles: kernel dimension = the engines' barcode count (`b <= s`, `t < d`) for every pair of critical values, three degrees, four
  point clouds; entries = the Schur complement `DD^T - D R^T (R R^T)^-1 R D^T + A^T A` solved by Gaussian elimination in the test on
  boundary matrices it builds itself (more than 100 instances compared, skipping those with a singular `R R^T`); the hollow
  triangle (0, 3, 3); and the three-vertex example in which projecting is the whole point (without it the kernel has the wrong size).
  5 mutants (no projection, no down part, `<` for `<=` at either value, rank off by one) all fail.
- Limits: dense, real coefficients only; a spectrum along a whole filtration is a loop over `(from, to)` that recomputes each time.


## Discrete Morse theory (2026-10-30)

- Matching: cells grouped by filtration value (`LinkedHashMap` on the `Double`), each level paired on its own. `Reduction` pairs a
  cell that has exactly one coface left in the level with it; `Coreduction` a cell with exactly one facet left; when nothing applies
  the free cell of highest (lowest) dimension is retired as critical. Acyclic because a gradient path never goes up in the filtration
  and inside a level the order of retirement decreases along it. A pair needs an incidence of +-1 (read from the `Double` boundary), which
  is a unit in every field, so the Morse boundary is computed in the coefficients asked for.
- The Morse boundary and the lift come from one flow (`MorseComplex.flow`): for a lower member `a` with partner `b`, subtract
  `(coefficient / ∂b[a]) ∂b` from the boundary being flowed and subtract the same factor times `b` from the lift. A first version of `lift`
  summed `(-V∂)^n sigma`, which never terminates when a face of `sigma` is paired (`V∂b` contains `b` itself). The first spec of lifted
  cycle representatives did not catch it: in those fixtures no representative had a paired face. What caught it is the identity
  `∂ lift(tau) = lift(∂^M tau)`, checked with the original complex's boundary alone over Z/3, Z/17 and the rationals, and a spec that some
  critical cell lifts to more than itself. Two mutants (sign of the lift term, no flow in the lift) had survived the representative spec and
  are killed by the identity.
- The first spec of persistence equality compared bars with `includeZeroLength = true` and failed on the 3x3 grid: the Morse complex has
  dropped the pairs that produce the zero-length bars. The comparison is of the bars of positive length.
- specs2 runs the examples of a specification concurrently, and a Vietoris-Rips stream caches its layers: with the fixtures a shared `val` one
  example saw a wrong Euler characteristic once. The fixtures are a `def`.
- The cohomology engine required a cell in every dimension below the top (`dimension-contiguous cell set`). A Morse complex can have no critical
  cell in a dimension and cells above it (random 8 points, `Coreduction`: critical cells of dimensions 0, 1, 3). The engine now requires that
  cells above a missing dimension have an empty boundary; the triangle with no edges is still refused (pinned in `DiscreteMorseSpec`).
- A sign flip in the flow's `chain` update makes the flow non-terminating, so that mutant is detected by a timeout, not by a failing example;
  not run again after the first hang left two sbt servers on the checkout (the second `sbt` waited for the first; kill stray `java` processes).
- Mutants killed: no requeue in the flow, all cells in one level, boundary not filtered to critical cells, pair stored the wrong way round for
  `Coreduction`, the engine guard accepting everything, the sign and the whole of the lift. Not killed and not killable by a spec of this kind:
  dividing by the incidence versus multiplying (the incidences are +-1).
- Limits: cocycles are not lifted (the other chain map, `Psi`, is not derived); the matching is greedy, not minimal; a user-supplied matching
  is not checked for acyclicity; cubical and alpha streams were not tried.
