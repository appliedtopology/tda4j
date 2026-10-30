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
