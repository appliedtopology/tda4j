# Layered in-order generator for every flag filtration (#190, 2026-10-29)

Request: #190 asked whether `LayeredInorderSimplexStream.Generator` (PR #186) could take a weighted edge list, since its
three-case theorem only uses that a simplex's value is the maximum over its edges, and be tried under DTM-Rips, sparse
Rips and the edge-collapsed complex, each checked cell for cell against its current stream.

## What was built

- `FlagGenerator(n, threshold, candidates, edges, deadline)`: the old `Generator` body, reading the edge list instead of a
  distance. `Generator(n, distance, threshold)` is a thin subclass over `edgesWithin`, so the Vietoris-Rips stream is
  untouched. `FlagGenerator.ofEdges` checks and builds the edges (ends in range, no loop, no repeat, no NaN) and drops the
  ones past the threshold or past the deadline of an end.
- `RipserCofaceSimplexStream.flatGenerator` (a hook, `None` by default): when a stream gives one, `keepCriterion` keeps
  everything and the vertices are `0 until n`, the layers from dimension 1 on come from the generator (`generatedLayer`:
  its tied runs are colexicographically descending, a flat layer's are ascending, so each equal-value run is reversed).
  Dimension 0 and the stream's own `flatFiltration` stay as they were; the generator must give the same cells and values.
- DTM-Rips: the graph of `reified.distance`. No deadline: a vertex is born at `2 f(v)`, and no edge at it is below that,
  so a vertex's own value never matters from dimension 1 on.
- Sparse Rips: the finite edge births, plus one deadline per vertex (`vanishDoubled`). A simplex of value `a` needs every
  vertex alive at `a`, and keeps the faces it had, so the deadline has to be checked where a vertex is added (`emit`),
  not only on the edges: a triangle can have all three edges alive and a third vertex dead at the largest edge's value.
- Edge collapse: the default `flatGenerator` of the base stream, for an `EdgeCollapsedMetricSpace` with the default
  filtration, built from `edges` (O(m)); the coface loop scans all n(n-1)/2 pairs for a distance.

## Checks

- `FlagGeneratorSpec`: a listing of every vertex subset as oracle (pairs all edges, largest weight, deadlines), three-valued
  weights so that ties are the rule, against the distance-based generator on the complete graph with the edges listed in any
  order and orientation, and against `EnumeratingCofaceSimplexStream` on the metric with infinity for a missing edge.
- `FlatLayersSpec` gets the edge-collapsed complex (grid and cloud, two thresholds, keep-all and a user criterion) and a
  sparse Rips case on `cloud(12, 2, 2L)` with epsilon 0.15 and 0.3, found by searching 216 clouds and epsilons for inputs
  where deadlines change the complex (169 did; the old sparse cases, an 11-point 3-D cloud and a grid, were not among them).
- Mutations, each caught: Sheehy edges without the deadlines (4 of 5 `FlatLayersSpec` examples fail on the new case; the
  old cases passed it, which is why it was added), the edge-collapse path with the original distances in place of the
  postponed values (4 of 5 fail).

## Measurements (indicative, not a controlled benchmark)

`FlatLayersTimingDriver` (test scope), layers to dimension 3 (`Truncated(.., 2)`), one JVM per run, median of three trials
(the first includes the JIT warm-up), 4-core cloud sandbox, `-Xmx4g` to `6g`. The distance to measure, the greedy
permutation and the collapse are computed before the clock. `coface` is the previous path, `generator` the new one; the
cell counts of the two agree in every trial.

| complex | input | coface | generator | cells (3 trials) |
|---|---|---|---|---|
| DTM-Rips, k = 10, p = 1 | 100 points in 3-D | 311 ms | 127 ms | 2.4M, 1.6M, 1.5M |
| DTM-Rips | 160 points | 2,528 ms | 1,062 ms | 14.9M, 12.7M, 10.1M |
| sparse Rips, epsilon 0.3 | 300 points in 2-D | 873 ms | 403 ms | 5.2M, 4.2M, 4.5M |
| sparse Rips | 600 points | 3,110 ms | 1,422 ms | 15.0M, 15.2M, 15.6M |
| edge-collapsed Rips, radius 0.2 | 2,500 points in 3-D | 364 ms | 72 ms | 188k, 188k, 200k |
| edge-collapsed Rips, radius 0.15 | 4,000 points | 765 ms | 102 ms | 194k, 200k, 200k |
| edge-collapsed Rips at the enclosing radius | 300 points | 17 ms | 27 ms | 39k, 18k, 16k |

Reading: 2.2 to 2.5 times for DTM and sparse; 5 to 7.5 times for the collapsed complex, which grows with n because the old
path scans every pair. A collapsed complex this small (300 points, 16k to 39k cells) is slower with the generator, by about
10 ms: its fixed cost (bitset table of n²/4 bytes, the edge sort) is not repaid by a few thousand cells. Not measured: the
reduction after the layers (for these complexes it was about 10% of the run, `WORKLOG-rare-complex-performance.md`), the
end-to-end `Persistence` call, memory, Scala.js, DTM beyond 160 points (300 points ran out of memory at `-Xmx4g` on both
paths).

The collapse itself is the cost of the edge-collapsed pipeline at the enclosing radius: 1,200 points took over four
minutes in `EdgeCollapse.collapse` (a TreeMap per vertex and a domination test per edge) before any layer was built. That
is the larger problem for that pipeline (#256).

## Left

- Cech and the general witness complex are not flag complexes of a graph (their values are not maxima over edges).
- The Dowker complex is a flag-like complex of a relation, not of a graph on its rows; not tried.
- Generating block by block (#188) and the cost of the collapse itself are separate.
