# Cubical persistence: closing the gap to CubicalRipser and GUDHI (2026-10-09)

Point-in-time snapshot. The project lead's large benchmark run showed both of our cubical paths (`fastcubical`, the
default, and `cohomology`) at up to 100x the time and memory of CubicalRipser (`cripser`) and GUDHI. Question: can we
do better?

All numbers below are from this session's 4-core cloud sandbox, warm median of 3 (one warm-up), F₂, one JVM per point,
`-Xmx8g -Xms8g -XX:+UseParallelGC` (the sweep's flags), harness `ab.py` (scratch, a thin wrapper over
`bench/bench.py`'s `run_proc` and `bench/workers/py_worker.py`). cripser 0.0.37, GUDHI 3.13.0. Sandbox numbers, not
citable; the ratios are the point.

## Baseline (before any change)

2-D (seconds; `fast` and `cohomology` with cycle representatives, the `Persistence` default):

| image | fast | cohomology | cripser | gudhi | fast / cripser |
|---|---|---|---|---|---|
| noise 256² | 2.81 | 2.94 | 0.033 | 0.139 | 85x |
| noise 512² | 1.38 | 15.8 | 0.166 | 0.710 | 8.3x |
| noise 1024² | 8.17 | 82.9 | 0.697 | 3.81 | 11.7x |
| blob 256² | 0.438 | 4.90 | 0.029 | 0.113 | 15x |
| blob 512² | 1.06 | 38.9 | 0.131 | 0.586 | 8.1x |
| blob 1024² | 5.85 | -- | -- | -- | |

3-D:

| image | fast (hybrid) | cohomology | cripser | gudhi | fast / cripser |
|---|---|---|---|---|---|
| noise 16³ | 2.00 | 2.32 | 0.005 | 0.016 | 400x |
| noise 32³ | 2.47 | 3.44 | 0.061 | 0.167 | 40x |
| noise 48³ | 11.8 | 15.5 | 0.336 | 0.664 | 35x |
| blob 16³ | 0.390 | 0.583 | 0.002 | 0.012 | 195x |
| blob 32³ | 2.00 | 3.15 | 0.021 | 0.119 | 95x |
| blob 48³ | 6.98 | 13.7 | 0.106 | 0.453 | 66x |

- **noise 256² taking longer than 512² is JIT warm-up**, not the algorithm: the trials were 2.8, 2.7, 2.1 s and a fourth
  was 0.26 s. At 256² the work per trial is too small for C2 to finish compiling the allocation-heavy code within one
  warm-up. A user who calls once on a small image sees the 2.8 s. The 16³ figures are the same effect.
- **Memory as measured is mostly the harness.** With `-Xms` = `-Xmx` under ParallelGC, RSS is how much eden the
  allocator touched: 2 GB at 256², against cripser's 58 MB. Allocation does drive it, but report memory from a run
  without `-Xms` (or the smallest heap that completes) before comparing.

## Diagnosis

JFR, 1024² noise (`fast`), steady state:
- the H₀ pass's per-cell value computation (`cellValue`: a `Range.filter`, `zipWithIndex` and tuples per call) ~23%;
- index merge sorts with an indirect comparator (`SortIndices`) ~26%;
- representative assembly (`LongMap.updateWith`, a tuple per member, `Vector.tabulate` per cube, `Chain.from`) ~22%;
- reading pixels through the `topCellValue` closure (an `IndexedSeq` per pixel, three passes) ~7%;
- **GC pauses 2.6 s per computation, about 30% of wall time**, on top of the sampled CPU.

At 256² before C2 compiled it: `VectorBuilder.result` was 44% of samples (the closure and `cellValue` again).

3-D 48³ noise (`fast` hybrid): the middle degrees go to the generic `CellularCohomologyEngine` on `Vector[Int]` cubes,
and it is all of it: `nondegenerateAxes` and the `FiltrationOrdering` tie-break per comparison, `Chain`'s `TreeMap`,
`MurmurHash3.seqHash` of cubes as hash keys, `FiniteField.norm`. GC ~16%.

The 2-D gap is constant factors (the algorithm is already union-find on both sides, which is better than what
CubicalRipser does for H₁). The 3-D gap is the generic engine.

## Deliverable A: the fast cubical engine on ranks and flat arrays

What changed:
- **`CubicalGridStream.topCellValues`** (`private[tda4j]`, lazy, parallel when `parallelFiltrationValue`): every pixel
  read once into a row-major array. `CubicalImage.fromFlatArray` (so `Image`, `fromGrayscale2D`, `fromVoxelGrid3D`)
  builds a `FlatCubicalGridStream` that holds the array itself: no closure call per pixel at all. `cellValues` and the
  verb's `last` read it.
- **Ranks (`GridRanks`)**: the pixels' dense ranks under `java.lang.Double.compare`, by an LSD radix sort on
  order-preserving 64-bit keys. Every T-construction cell's value is some pixel's, so a cell's rank is the minimum rank
  of its containing pixels: H₀ computes vertex and edge ranks by separable minimum passes over the vertex grid; the
  dual takes facet ranks from the two pixels.
- **Orders by stable counting sort**, fed in the tie-break's order. H₀: edges generated in DESCENDING doubled-grid
  encoding, sorted by rank ascending (value ascending, encoding descending: the stream's order). Dual: facets generated
  in ASCENDING encoding, sorted by rank descending. A prefix odometer over the doubled grid (`DoubledGrid`) walks the last
  axis in a tight loop and skips prefixes with no cell of the wanted kind.
- **The dual's pixel events are gone**: a facet's value is the smaller of its pixels', so both pixels always precede it,
  and a component's birth is its root's value.
- **Comparisons stay on values.** The elder rules and zero-length tests compare `distinct(rank)` doubles with IEEE `<=`
  and `!=`, as before: the old code sorted by `Double.compare` (-0.0 before 0.0) but compared with IEEE (-0.0 == 0.0),
  and `sublevel = false` turns 0.0 into -0.0, so both zeros are reachable. Comparing ranks instead changes outputs:
  mutation-checked, see the gate.
- **Signs are integers** (`UnitSignedUnionFind`, bytes): every orientation and flip in the cubical dual is a product of
  boundary coefficients ±1, and integers map to any field by a ring homomorphism, so coefficients are mapped to the
  field once per output term. The generic `SignedUnionFind[C]` stays as it was for the alpha engine. The first rank
  version kept `SignedUnionFind[C]`: `FiniteField.norm` (boxed field arithmetic in `find`) was then 19% of samples.
- **Region boundaries** are summed as integers in an open-addressing table (`FacetSums`); cubes are built from boxes made
  once per grid (`CubeBoxes`): a `Vector[Int]` made directly at its size, not through `Vector.tabulate`'s builder (which
  left a 32-slot array and one `Integer` per coordinate behind each cube).
- **NaN pixels are refused** (`GridRanks`), with a message saying to use a number or `+Infinity` for a missing pixel.
  Behaviour change: before, `Math.min` carried NaN to every face of a NaN pixel, so faces entered after their cofaces
  (an invalid filtration) and the output was meaningless. Rank minima cannot reproduce that propagation anyway.

Gate: `FastRepresentativesSpec`'s equality with `EagerFastCubicalReference` (bars, order, representatives term for
term) plus new cases: shapes 1×1, 1×7, 7×1, 2×2, 2×9, 1×4×3, 3×1×4, 2×3×4, 2×2×2×2, values with ties, `+Infinity`, -0.0
and 0.0, all over F₂, F₃, F₁₇ with zero-length bars on and off; a NaN test. Mutation checks:
- comparing ranks in H₀'s elder rule: caught by the new shapes (the old cases have no signed zeros, they passed it);
- comparing ranks in the dual's elder rule: NOT caught by random signed-zero images (a ±0 merge away from `∞` is rare),
  so the spec has a crafted 5×5 image (`signedZeroMerge`: a 0.0 pixel whose first merge is with a lone -0.0 pixel to its
  right): caught.
`testFull` 958 tests, 0 failures; lint and MiMa (against 0.5.0) clean.

### Results (same session, same flags)

Every figure in the "after" column includes representatives, like every public path. "Bars" is the driver's
measurement mode (`reps=none` -> `private[tda4j] barsWithoutTopRepresentatives`), quoted only to show what the
representatives cost; it is not something a user can get, and comparisons should be quoted with representatives
(project lead, 2026-10-09: representatives everywhere matter more than being the fastest).

| image | before | after | (bars) | cripser | gudhi | after / cripser |
|---|---|---|---|---|---|---|
| noise 256² | 2.81 | 0.157 | 0.064 | 0.033 | 0.139 | 4.8x |
| noise 512² | 1.38 | 0.468 | 0.184 | 0.166 | 0.710 | 2.8x |
| noise 1024² | 8.17 | 2.36 | 0.96 | 0.697 | 3.81 | 3.4x |
| noise 2048² | -- | 11.5 | 4.36 | 3.10 | 20.1 | 3.7x |
| blob 256² | 0.438 | 0.128 | 0.099 | 0.029 | 0.113 | 4.4x |
| blob 512² | 1.06 | 0.362 | 0.189 | 0.131 | 0.586 | 2.8x |
| blob 1024² | 5.85 | 1.54 | 0.79 | -- | -- | |
| blob 2048² | -- | 10.5 | 3.24 | 2.89 | 14.1 | 3.6x |

Faster than GUDHI from 512² on, with representatives.

Memory without a pinned heap (`-Xmx8g`, default `-Xms`), noise 1024², peak RSS: before 5.7 GB, after 2.5 GB with
representatives; cripser 0.32 GB, GUDHI 0.39 GB. The rest of the gap is mostly the representatives' objects (below).

### What is left in 2-D: the representatives themselves

At 2048² noise the representatives hold 18.5M facet terms (837,583 H₁ bars: mean 22, median 6, max 263,096; H₀ one
vertex each). As `Chain[Cube, C]` that is about 70 bytes a term (a tuple, a `Vector1`, its array) plus ~300 bytes a bar
(the bar, endpoints, `Some`, `Chain`, its `PriorityQueue` and backing array): ~1.5 GB of live output for a 32 MB image,
which ParallelGC copies through the survivor spaces: JFR puts ~2.4 s of a ~10 s run in GC pauses (before `CubeBoxes`,
~7 s of ~16 s). CubicalRipser returns ~94 MB of numbers.

The representatives cannot get much smaller (they are the boundaries of the dying regions), so the lever is the cost
of each term: objects shared between representatives (a facet lies on ~2.2 representatives on average at 2048²), not
fewer representatives. Deferring them until read (a lazily filled `Chain`; the merge forest that defines them is ~9
bytes a pixel and frozen at the end of the run) would keep one for every bar, but it is the project lead's call and is
not done: eager representatives everywhere come first.

## Deliverable B: a packed grid cohomology engine (the 3-D middle degrees, `Engine.Cohomology` on images)

`PackedCubicalCohomologyEngine` (`private[tda4j]`, `homology/PackedCubicalCohomology.scala`): Ripser's reduction on the
grid's own index arithmetic, built to give exactly what `CellularCohomologyEngine` gives on the same cells, so it
replaces it wherever the input is a grid without changing any output.
- **Cells are doubled-grid indices**; a cell's rank (smallest pixel rank, separable passes like `CubicalGridStream`'s
  values) and index pack into one `Long`, `(rank << 32) | index`, which orders exactly like the generic engine within a
  dimension (value ascending, then the encoding ascending; NOT the stream's tie-break, which is the reverse).
- **Coboundaries and boundaries by index arithmetic**, with `cubeIsOrderedCell`'s signs (the rank of the axis among the
  cube's nondegenerate axes).
- Cells of a dimension in order by a stable counting sort by rank of the cells taken in ascending index.
- **Clearing** as in the generic engine (a `BitSet` over all cells: indices are unique across dimensions).
- **Apparent pairs** (a cell whose oldest same-value cofacet has it as its youngest same-value facet): paired without a
  reduction, column rebuilt when another column hits its pivot, as the packed Ripser engine does. Changes no output
  (the spec runs with them on and off). Why the rebuild is safe: when pivot τ of an apparent pair (σ', τ) shows up in
  σ's column, every cell processed so far that has τ in its coboundary is a facet of τ younger than σ', and there is
  none, so σ is older than σ' and σ' was processed (as an apparent pair) already. A cleared σ' cannot be apparent: it
  is a death, and apparent pairs are persistence pairs.
- **Cocycles** (V-columns) recorded as seed and log, expanded only for reported bars, accumulated as the generic engine
  does (`acc(k) -= c * v`) in a primitive table. The first version used `Chain` arithmetic as the packed Ripser engine
  does: cocycles then cost 3-7x the cycles (48³ noise: 11.5 s against 1.7 s); now they cost the same.
- **Cycles** by the packed involution (death columns youngest-first, oldest death first per dimension; apparent rows'
  columns rebuilt, not stored; V-columns only for essential bars).
- **Stored columns are combined on drain** (each cell once, zeros dropped). The first version kept the packed Ripser
  engine's convention, a drained column holding its other terms "as they lie in the heap", uncombined: every later
  reduction that used a stored column pushed its repeats and cancelling pairs again, and the 3-D times grew
  superlinearly (64³ noise 40 s, 48³ 4.0 s). Combined: 64³ 3.9 s, 48³ 1.6 s.
- **A reduction step must move the pivot on**: otherwise the column and the order disagree and the loop never ends
  (`orderBug` throws). Found by mutation testing: reversing the index tie-break made the spec hang instead of fail;
  with the check it fails in 155 ms.

Wired in: `FastCubicalHomologyEngine.computeMiddleDimensions` (cells up to `d - 1`, degrees `<= d - 2`, the `GridRanks`
of the dual pass reused), and `Persistence`'s `Engine.Cohomology` whenever the stream is a `CubicalGridStream` (cycles
or cocycles; `Representatives.Cocycles` on an image goes there too). `LimitedCubicalGridStream` inputs still go to the
generic engine.

Gate: `PackedCubicalCohomologySpec`, equality with `CellularCohomologyEngine` (`==` on the bar lists: bars, order,
cocycles and cycles term for term) on whole grids and on cells up to each lower dimension, shapes 1×1, 1×6, 5×1, 4×5,
7×6, 3×4×3, 1×3×4, 2×2×2×2 with tied values, `+Infinity` and signed zeros, plus 6×5 and 3×3×4 noise; F₂, F₃, F₁₇;
zero-length bars on and off; apparent pairs on and off. Plus closedness of every cycle and every essential cocycle on a
7×8×6 tied volume over F₃ (a check the reference cannot share a bug with). `FastRepresentativesSpec`'s 3-D and 4-D
cases now compare the packed middle degrees with the generic engine's (the eager reference still uses it). Mutations
caught: a wrong boundary sign, the apparent check without its facet side, the reversed tie-break.

Also routed to it: `PersistenceEngine.cohomology`/`cohomologyCycles` (what `matlab.TDA4j` and the CLI call for
`engine=cohomology`) when the stream is a `CubicalGridStream`, with the same output.

**Behaviour change (both deliverables):** an image with a NaN pixel is now refused by the fast engine and by cohomology
on a grid, with a message saying what to use instead; before, it gave a meaningless barcode (see deliverable A).

### 3-D results (warm medians, with representatives; same flags; before = the baseline above)

`fast` is the default (`Auto`) path: union-find H₀/H₂ plus the packed engine for H₁, cycles. `cohomology` is
`Engine.Cohomology` on the whole grid, cycles and cocycles.

| image | fast before | fast after | cohomology before | cycles after | cocycles after | cripser | gudhi | fast / cripser |
|---|---|---|---|---|---|---|---|---|
| noise 16³ | 2.00 | 0.068 | 2.32 | 0.063 | 0.059 | 0.0054 | 0.016 | 13x (was 370x) |
| noise 32³ | 2.47 | 0.388 | 3.44 | 0.404 | 0.395 | 0.061 | 0.167 | 6.4x (41x) |
| noise 48³ | 11.8 | 1.57 | 15.5 | 1.70 | 1.68 | 0.336 | 0.664 | 4.7x (35x) |
| noise 64³ | -- | 3.88 | -- | 4.37 | 4.18 | 0.879 | 2.01 | 4.4x |
| noise 96³ | -- | 16.9 | -- | 19.3 | 18.4 | 3.45 | 8.39 | 4.9x |
| blob 16³ | 0.390 | 0.061 | 0.583 | 0.050 | 0.045 | 0.0022 | 0.012 | 28x (177x) |
| blob 32³ | 2.00 | 0.322 | 3.15 | 0.237 | 0.200 | 0.021 | 0.119 | 15x (97x) |
| blob 48³ | 6.97 | 0.913 | 13.7 | 0.934 | 0.670 | 0.106 | 0.453 | 8.6x (66x) |
| blob 64³ | -- | 2.10 | -- | 2.52 | 1.92 | 0.341 | 1.13 | 6.2x |
| blob 96³ | -- | 7.43 | -- | 9.09 | 6.99 | 1.41 | 5.44 | 5.3x |

- From 48³ on: 4.4-5x CubicalRipser on noise, 5-9x on the blob, and about 2x GUDHI (with a representative per bar;
  neither of them returns one).
- On the blob, cohomology with cocycles is now as fast as the default path, or faster.
- These were measured before the apparent-pair shortcut in the involution; see "Final measurements" below.

### Tried and reverted: sharing a facet's representative term between representatives

A facet lies on ~2.2 representatives on average at 2048² noise, so the first commit of deliverable B built each
facet's `(cube, ±1)` term once per run and shared it (two reference arrays the size of the doubled grid). Measured
against the same 2-D code without it (pinned heap, warm medians): noise 2048² 11.5 -> 14.1 s, noise 1024² 2.36 ->
2.54 s, blob 2048² 10.5 -> 11.1 s, blob 1024² 1.54 -> 1.63 s: slower everywhere (A3 and B4 are different runs of the
same session; the 2-D path differs only by the cache). Not profiled; an unconfirmed guess is GC card scanning: two large
old-generation reference arrays written with young objects, which every young collection must scan. The arrays
also cost transient memory proportional to the doubled grid (~1 GB at 256³). Reverted in the follow-up commit.

## Final measurements: the classes of commit 335dcb0

Everything in this section ran on the classes compiled from 335dcb0: deliverables A and B, the involution's
apparent-pair shortcut (an apparent pair's death column is neither reduced nor stored), the shared-term cache reverted.
The commit after it only adds O(n) NaN scans (`fromFlatArray`, `topCellValues`) and was not re-measured. Same machine and
session as above, F₂, a representative for every bar (cycles unless marked). cripser and GUDHI figures come from this
session's earlier runs (their code did not change) unless a row says otherwise. These supersede the "after" columns of
the 3-D table above, which predate the apparent-pair shortcut. All three tools report the same number of bars in every
degree at every size measured (128³ noise: 79,516 / 384,642 / 285,633 in degrees 0 / 1 / 2).

### 2-D, the default path (fast cubical), pinned heap, warm median of 3 after one warm-up

| image | trials (s) | median | cripser | gudhi | / cripser | / gudhi |
|---|---|---|---|---|---|---|
| noise 1024² | 7.29, 2.24, 1.43 | 2.24 | 0.697 | 3.81 | 3.2x | 0.59x |
| noise 2048² | 10.96, 10.71, 12.25 | 11.0 | 3.10 | 20.1 | 3.5x | 0.55x |
| blob 1024² | 2.98, 1.37, 1.27 | 1.37 | 0.643 | 2.61 | 2.1x | 0.53x |
| blob 2048² | 10.00, 10.18, 9.22 | 10.0 | 2.89 | 14.1 | 3.5x | 0.71x |

Below 1 in the last column: faster than GUDHI. At 1024² one warm-up is not enough: the first trial is 2-5x the others,
so a median of three is good to about ±50% there; the 2048² trials agree within 15%. The medians are within 11% of (and
below) the A3 run of the same 2-D code before the cache (2.36, 11.5, 1.54, 10.5).

### 3-D, pinned heap, warm median of 3

| image | fast (default) | cohomology, cycles | cohomology, cocycles | cripser | gudhi | fast / cripser | fast / gudhi |
|---|---|---|---|---|---|---|---|
| noise 48³ | 1.70 | 2.00 | 1.71 | 0.336 | 0.664 | 5.1x | 2.6x |
| noise 64³ | 3.55 | 3.68 | 3.99 | 0.879 | 2.01 | 4.0x | 1.8x |
| noise 96³ | 15.7 | 17.1 | 17.9 | 3.45 | 8.39 | 4.5x | 1.9x |
| blob 48³ | 0.667 | 0.829 | 0.718 | 0.106 | 0.453 | 6.3x | 1.5x |
| blob 64³ | 2.08 | 2.08 | 1.87 | 0.341 | 1.13 | 6.1x | 1.8x |
| blob 96³ | 6.62 | 7.89 | 6.40 | 1.41 | 5.44 | 4.7x | 1.2x |

Against the baseline at 48³: fast 11.8 s (10.6 re-measured, below) -> 1.70 s (noise), 6.98 -> 0.667 s (blob);
cohomology 15.5 -> 2.00 s, 13.7 -> 0.829 s. The noise 48³ and 64³ fast medians have one slow trial each (2.8 s, 7.2 s):
same caveat as 1024².

### `Engine.Cohomology` on 2-D images (the packed engine), pinned heap, warm median of 3

| image | before (generic engine, cycles) | cycles | cocycles |
|---|---|---|---|
| noise 512² | 15.8 | 1.64 | 1.61 |
| noise 1024² | 82.9 | 6.52 | 8.58 |
| blob 512² | 38.9 | 1.32 | 1.31 |
| blob 1024² | -- | 5.36 | 5.51 |

10-30x faster than the generic engine where both were measured. The noise 1024² cocycle trials spread from 7.5 to 9.4 s.

### Memory: peak RSS, heap not pinned (`-Xmx8g`, default `-Xms`)

| input | before | after | cripser | gudhi |
|---|---|---|---|---|
| noise 1024² (fast) | 5.7 GB | 2.50 GB | 0.32 GB | 0.39 GB |
| noise 64³ (fast) | 4.52 GB | 2.16 GB | 0.15 GB | 0.17 GB |
| noise 128³ (fast, `-Xmx10g`) | -- | 5.53 GB | 0.65 GB | 1.01 GB |
| blob 128³ (fast, `-Xmx10g`) | -- | 3.37 GB | 0.43 GB | 0.87 GB |

"Before" at 64³ is one call (33.2 s); "after" at 1024² and 64³ is a warm-up plus three calls in one JVM (peak over all
of them), at 128³ one call. The gap that is left is memory: 8-15x CubicalRipser's peak RSS, 4-13x GUDHI's.

### One call in a fresh JVM (what a one-call or MATLAB user sees), heap not pinned

| input | before | after | cripser | gudhi |
|---|---|---|---|---|
| noise 256² | 1.07 s, 347 MB | 0.378 s, 138 MB | 0.033 s | 0.139 s |
| noise 16³ | 1.26 s, 224 MB | 0.271 s, 104 MB | 0.0054 s | 0.016 s |
| noise 128³ | -- | 45.7 s | 9.37 s | 22.7 s |
| blob 128³ | -- | 20.0 s | 4.27 s | 14.3 s |

The Python tools have no warm-up to speak of, so their warm figures are their first-call figures. Ours at 256² is
0.378 s cold against 0.157 s warm: on small inputs the remaining first-call gap is mostly JIT compilation.

### Small inputs: two baseline rows did not reproduce

Re-measured with the pre-change build under the baseline's own protocol (pinned heap, one warm-up, median of 3), every
baseline row reproduces within 10% (noise 1024² 7.89 s against 8.17, noise 48³ 10.6 against 11.8, noise 32³ 2.28
against 2.47, blob 256² 0.443 against 0.438, blob 16³ 0.407 against 0.390, blob 32³ 1.80 against 2.00) except the
first point of each sweep: noise 256² (0.518 s against 2.81) and noise 16³ (0.441 against 2.00). Cause not found
(something on the machine during those first runs; not profiled). So the baseline section's JIT explanation for those
two rows is wrong as stated, and every ratio built on them above (85x and 400x in the baseline, "was 370x" in the 3-D
table, the 256² "before" of deliverable A) is void. Corrected, pinned heap, warm median of 3 (after: the A3 run for
256², the code of this section re-run alongside for 3-D):

| image | before | after | cripser | before / cripser | after / cripser |
|---|---|---|---|---|---|
| noise 256² | 0.518 | 0.157 | 0.033 | 16x | 4.8x |
| blob 256² | 0.443 | 0.128 | 0.029 | 15x | 4.4x |
| noise 16³ | 0.441 | 0.062 | 0.0054 | 82x | 11x |
| noise 32³ | 2.28 | 0.381 | 0.061 | 37x | 6.2x |
| blob 16³ | 0.407 | 0.047 | 0.0022 | 185x | 21x |
| blob 32³ | 1.80 | 0.230 | 0.021 | 86x | 11x |

At these sizes the ratios measure fixed costs (16³ blob: 47 ms against 2.2 ms).

The titles of commits 06d5cb8 ("2.5-18x faster in 2-D") and ceefe80 ("7-30x faster" in 3-D) took their upper ends from
those two rows. Without them the default path is 2.9-4.3x faster in 2-D (256² to 1024²) and 6-10x in 3-D (16³ to 48³);
`Engine.Cohomology` 8-16x in 3-D (32³, 48³) and 10-30x in 2-D (512², 1024²). Re-measured "before" figures: the 256²
rows, noise 1024², and the default path at 16³-48³ except blob 48³. The rest (512², blob 1024², blob 48³, every
cohomology baseline, the 1024² memory) are the original run's; every non-first point that was re-checked reproduced
within 10%, so they are probably right, but they were not re-run. The 32³ cohomology "after" is the B3 run's.

The pinned heap does cost something while eden is fresh: the pre-change build at noise 256², heap pinned, no warm-up,
took 1.12, 0.56, 0.52, 0.45 s; with `-XX:+AlwaysPreTouch` (the heap touched at JVM start, outside the timed region)
1.06, 0.32, 0.29, 0.28 s. About 0.2 s a call at that size is first-touch page faults under the sweep's flags; the first
call's ~1.1 s is JIT either way. A sweep that pins the heap could add `-XX:+AlwaysPreTouch` to its timing runs (RSS
then reports the whole heap, so memory wants its own unpinned run).

### NaN, follow-up: refused by every engine, mask advice in the user's units

The refusal above lived in `GridRanks`, so only the fast engine and grid cohomology refused NaN; `Engine.Chunks`/`Naive`
on the same image still returned a meaningless barcode, and the docs' "NaN is refused" was false for them. Now
`CubicalImage.fromFlatArray` (every array image: `Image`, the MATLAB facade, the DIPHA/Perseus readers) checks when it
builds the image, and `CubicalGridStream.topCellValues` when a grid built from a function is first read; `GridRanks`
still checks. One message (`GridRanks.nanPixel`), naming the pixel. Behaviour change in public constructors: iterating a
function-built grid with a NaN value now throws.

The first version of the message advised `+Infinity` for a missing pixel everywhere. Wrong for superlevel images:
`fromFlatArray` negates the values first, so a user's `+Infinity` becomes `-Infinity` and enters FIRST (the brightest
pixel), the opposite of a mask, and following the advice gives a silently wrong barcode. The pre-existing user-guide line
("a pixel with value `Infinity` never enters") had the same flaw. The message and every doc in user units now say
`-Infinity` for a superlevel image; `TDA4jSpec` checks a ring around a masked pixel in both directions, and that `+Inf`
in a superlevel image leaves no loop.

### What is left

- **Memory** is the real remaining gap (table above), and the representatives are most of it: at 2048² noise they are
  18.5M facet terms held as `Chain[Cube, C]` objects (~70 bytes a term). A compact backing for grid representatives
  (cells as primitive indices and coefficients, cubes made when read) would keep a representative for every bar, eager,
  while cutting the objects and the GC that copies them. It changes what a `Chain` is underneath, so it is the project
  lead's call; not started.
- **3-D time**: the default path is within 10-20% of whole-grid cohomology from 64³ on, so most of it is presumably the
  packed reduction of the middle degree (inferred from those timings; the final code was not profiled). Cycles and
  cocycles cost about the same (cocycles / cycles 0.81-1.08 in 3-D, 0.98-1.32 in 2-D): the involution is no longer the
  lever.
- **First calls** on small inputs pay JIT warm-up (above). A class-data-sharing or AOT archive for the CLI/MATLAB jar
  would be the lever; not tried.
- **Not run:** 4096² (2048² already peaks near 7 GB with a pinned 8 GB heap); 3-D beyond 128³.
