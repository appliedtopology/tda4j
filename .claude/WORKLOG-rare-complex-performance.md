# Worklog: Cech, DTM-Rips, SparseRips and Dowker against GUDHI (#181)

Started 2026-10-23, cloud session, 4 cores, JVM `-Xmx3g`. Point-in-time notes; the result is summarized in
`rules/filtered-complexes.md`. The slow rows are `.claude/DESIGN-rare-complex-benchmarks.md` (update section).

## Data

`bench/bench.py prepare_rare` regenerated with numpy (`default_rng(20261021)`: `unif3d_80`, `circle_outliers_600`,
`relation_300x100` are the real bench files). `sphere3_96` is the first 96 rows of Ripser's `sphere_3_192_points.dat`
(downloaded from the ripser-benchmark repository), so also the real file.

## Method

`PaperBenchmarkDriver task=... warmup=0 trials=1 p=2` under `-XX:StartFlightRecording=settings=profile`, one JVM per
row, baseline classes of `96ec81c` plus the #182 deletion copied aside for the A/B. The leaf-frame table of
`jfr-summarize.sh` shows `TreeSet` iteration and `TrieMap` lookups and nothing else, so an inclusive-frame table was
written (`jfr print --events jdk.ExecutionSample --stack-depth 96`, count samples that have a given tda4j method
anywhere in the stack).

## Findings (baseline, three rows running at once, so slower than the issue's single-row times)

| row | time | in `RipserCofaceSimplexStream.iterateDimension` (the stream) | in the reduction |
|---|---|---|---|
| Dowker 300x100, dim 1 | 41.6 s | 89.8 % | ~10 % |
| DTM-Rips, 600 pts, dim 1, 0.6 | 43.7 s | 89.0 % | ~10 % |
| Sparse Rips, 96 pts, dim 2, eps 0.3 | 57.0 s | 87.3 % | ~12 % |

**None of these is the reduction.** The time is in generating the complex, in the coface loop all five rarer complexes
share. Three things in it:

1. **The candidate list is every `(simplex, i < min)` pair**, whether or not the edges to `i` exist. Dimension `d` is
   `|layer d-1| * n / (d+1)` candidates, each a new `TreeSet` (`Simplex` is a `SortedSet[Int]`), each looked up in a
   `TrieMap` whose key hash iterates the tree. For a flag-like complex that is far larger than the complex itself
   (Dowker: 300 vertices, DTM: 600, sparse: a sparse complex). The complex is closed under faces, so `i` has to be
   adjacent to every vertex of the simplex: a bitset of the kept edges prunes the list before any filtration value is
   computed.
2. **`sortedByFiltration` looks every cell up in two `HashMap[Simplex]`s (value, index) and a third time in the
   filtration function's own `TrieMap`**, hashing a `TreeSet` each time (`MurmurHash3.setHash`, 4-6 % alone,
   `TreeSet.equals` 8 %).
3. The filtration function itself: Dowker's `min_w max_x R(x,w)` is 100 witnesses times the size, Sheehy's recomputes
   all edge births of a simplex from the metric space.
