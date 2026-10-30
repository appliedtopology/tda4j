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
