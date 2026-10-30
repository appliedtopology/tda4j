# Cohomology on a Vietoris-Rips stream: the layer sort replaced by the generator (2026-10-29, #251)

Point-in-time snapshot. #251 (from the profile of #199, `WORKLOG-generic-engine-profile.md`) said that `Engine.Cohomology`
on a Vietoris-Rips stream spends 37 to 41% of its time in `LayerSorter` (the sort of each dimension of the default
`RipserCofaceSimplexStream`) and 27% in `PackedCells.matches` (the face lookups), and asked for an isolated A/B of the
stream's share. The A/B is `CohomologyStreamDriver` (test scope): `coface` is the default stream with `flatGenerator`
switched off (the coface loop and the sort), `generator` the stream as it is now.

## What was built

- `RipserCofaceSimplexStream.flatGenerator` gives the layered in-order generator (`LayeredInorderSimplexStream.Generator`)
  for the default filtration when the stream keeps every simplex and has at most `MaxGeneratorVertices` = 2^15 points (its
  two bit matrices take `n^2 / 4` bytes, 256 MB there, the cap of the coface loop's neighbour table). That is what
  `VietorisRips(...)` builds, so every consumer of its layers (`Cohomology`, `Chunks`, `Naive` through the cell listing)
  gets it.
- The stream no longer keeps the generator's raw layer of the top dimension (a second copy, 20 bytes a simplex). It keeps
  that layer's `root` (4 bytes a simplex) and rebuilds the generator's order from the flat layer by reversing the tied
  runs again (`reversedTies` is its own inverse). The first prototype kept the raw layer: on the 150-point sphere, under G1,
  it ran out of memory at 1 GB and finished at 1.5 GB (one run each; the thresholds below are for the final form).
- `sortIndices` (sorts the tied runs the generator emits out of order, and the edges): insertion-sorted blocks of 16, then a
  merge that moves only the overlap of two neighbouring blocks (two binary searches find it), `inline` so that the
  comparison is not a call through a function object. The runs are nearly in order, so the overlap is small.
- The generator's bit-row reads in `cofacets` go through two local vals instead of the `lazy val` accessors, which were
  19% of the samples of the lattice profile.

## Measurements (4-core sandbox, `-Xms8g -Xmx8g -XX:+UseParallelGC`, one variant per JVM, two JVMs each)

Layers only (every dimension up to 3, listed once; median of 9 trials, the first includes the JIT warm-up):

| input | cells | coface loop + sort | generator |
|---|---|---|---|
| 150 points on S^2 | 16.0M | 4.09 s, 4.65 s | 1.10 s, 1.22 s |
| 250 uniform points in [0,1]^3 | 44.5M | 11.6 s, 11.9 s | 2.93 s, 2.98 s |
| 100 uniform points in [0,1]^3 | 1.9M | 411 ms, 385 ms | 177 ms, 165 ms |
| 30 uniform points in [0,1]^3 | 14k | 9 ms, 8 ms | 4 ms, 6 ms |
| lattice 14^3 (2,744 points), threshold 2.3 | 2.1M | 450 ms, 421 ms | 485 ms, 480 ms |

The lattice (distances in a handful of values, the extreme of ties) is the one input where the generator is not ahead: the
medians are 10 to 15% behind, the trials after the warm-up overlap (coface 299 to 386 ms, generator 333 to 378 ms). Before
`sortIndices` was changed it was 1.5 times behind (486 ms against 320 ms): 7 tied runs held 2.0M of its 2.1M cells, 11 to
12% of the neighbours in a run were out of order, the displacement from the sorted place averaged 4 to 12 (at most 419) in
dimension 3 and 26 to 2,184 (at most 22,274) in dimension 2, and sorting them was 52 to 55% of the generator's samples.

`Persistence(stream, maxDimension = 2, engine = Cohomology, representatives = Cycles)`, the stream built before the clock
(median of 5 trials):

| input | coface loop + sort | generator | bars |
|---|---|---|---|
| 150 points on S^2 | 9.8 s, 10.3 s | 6.4 s, 6.6 s | 190 |
| 250 uniform points in [0,1]^3 | 23.0 s, 23.3 s | 11.8 s, 13.0 s | 375 |

The two variants give the same cell counts and bar counts in every run; `FlatLayersSpec` compares the layers bit for bit.

Memory: the smallest `-Xmx` at which the 150-point sphere finished under `-XX:+UseSerialGC` (50 MB steps, one run each) is
1,100 MB for the coface loop and 1,150 MB for the generator, which is the 4 bytes of `root` kept for each of the ~12M
tetrahedra. Under G1 and the parallel collector the thresholds were noisy (the coface loop finished at 950 MB and ran out
at 1 GB in the same series), so this is a coarse figure from one collector.

## Checks

- `FlatLayersSpec`: the Vietoris-Rips cases now run on two integer-distance matrices (four and two distinct values) besides
  the grid and the cloud; a direct comparison of the generator's layers with the coface loop's, vertices and value bits, on
  six inputs and three thresholds, up to dimension 4; the dimensions asked for in five other orders give the same cells;
  which streams get the generator (default value up to 2^15 points: yes; 2^15 + 1 points or a Cech stream: no).
- `SortIndicesSpec`: the stable order of the library's stable sort, for sizes around the block and the powers of two, on
  random, few-valued, constant, ascending, descending, locally disordered, displaced-block and interleaved inputs.
- Mutations, each caught: the rebuilt generator layer without reversing the tied runs (5 of 8 `FlatLayersSpec` examples
  fail), the right end of the merge overlap one too small, the left end one too large (both fail both `SortIndicesSpec`
  examples and 3 and 4 of 5 `FlagGeneratorSpec` examples).

## Left

- **The face lookups are now the cost.** A JFR profile of the generator variant on the 250-point cloud (one JVM, 4 trials)
  puts `PackedCells.face` (the open-addressing probe, `slotOf`) at 61% of the samples; they run on all cores, so that is
  less than 61% of the wall time. Each lookup reads `slots` and then the vertices of the cell it points to, two cache misses.
  An exact packed key (the vertices in one `Long` when `width * bits(n) + bits(cells) <= 63`, one miss, 8 instead of 4 bytes
  a slot) and probing a batch of faces before verifying are the candidates; neither is built or measured.
- The lattice stays 0 to 15% behind the coface loop in layers; the generator's `Generator.edgesWithin` boxes both vertices
  of each of the n(n-1)/2 distance calls (`FiniteMetricSpace[Int]` is generic), 8 to 10% of that profile.
- The branch of `generatorLayer` that rebuilds the layers from the edges is not reachable by the stream's own calls (the
  last layer built is always the one below the next); it is kept for a change that reorders them.
- Scala.js timings were not taken (the specs run there). `Persistence`'s `Auto` engine does not use this stream for
  Vietoris-Rips on points (it is Ripser there), so the verb's default is unchanged.

## Reproduce

```
java -Xms8g -Xmx8g -XX:+UseParallelGC -cp <test classpath> org.appliedtopology.tda4j.CohomologyStreamDriver \
  coface|generator layers|cohomology sphere:150|cloud:250:3|grid:14:3:2.3 Cycles <trials>
```
