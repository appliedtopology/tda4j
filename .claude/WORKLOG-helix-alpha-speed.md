# WORKLOG: why Helix was 25-65x slower than GUDHI in the alpha dimension sweep, and the fix (2026-10-20)

Question (project lead, after the n = 40 part of `bench/run.sh sweep-alpha`): (1) Cech and DQP are flat in the ambient
dimension, as expected? (2) Helix and fast-alpha are "10x slower than GUDHI" and stop two dimensions earlier: what does
GUDHI do differently, and can Helix reach dimension 10 in under an hour?

Everything below is n = 40 points uniform in the unit cube (`bench/sweep_alpha.py`'s generator, seed 0), degrees 0..2,
`FiniteField(17)`, one cold JVM per measurement on a 4-core cloud sandbox (`-Xmx13g`, ParallelGC) unless a column says
otherwise. GUDHI 3.13.0 (`pip install gudhi`) on the same machine: 0.042 / 0.37 / 0.98 / 3.7 / 13.4 / 56.3 s for
d = 5 / 6 / 7 / 8 / 9 / 10, against the sweep server's 0.040 / 0.205 / 0.93 / 3.9 / - / 64 s, so the two machines are
comparable for GUDHI. The sweep server was running the n = 60 sweep at the same time; its Helix times carry that load
(d = 8: 257 s there, 105 s here).

## The ratio was not 10x, and it was growing

From the sweep (seconds, `tda4j-helix` / `gudhi`): d = 5 0.99/0.040 = 25x, d = 6 7.4/0.205 = 36x, d = 7 55.8/0.935 = 60x,
d = 8 257/3.94 = 65x. The curves are both exponential in d, but Helix's was steeper, which is the signature of a
superlinear step in the number of simplices rather than a constant factor.

## What GUDHI does (read from `Alpha_complex.h`, gudhi-devel master; the installed 3.13.0 is the same code)

1. CGAL's incremental `Delaunay_triangulation` (dynamic dimension, `Epick_d`: filtered predicates). 0.18 s at d = 8, 1.4 s at d = 10.
2. Every finite cell goes into a `Simplex_tree` with `insert_simplex_and_subfaces(cell, NaN)`: the whole face lattice.
3. Top-down, d to 0: a simplex whose value is still NaN gets its squared circumradius; then each facet that is still NaN
   takes the simplex's value unless it is Gabriel against the opposite vertex (a facet that already has a value takes the min).
4. `make_filtration_non_decreasing()`, prune.

That is our `DelaunayAlphaShapes.built` step for step (top value, Gabriel against the opposite vertices, min over
cofaces). GUDHI does not exploit the 3-skeleton either: at d = 10 its triangulation is 1.4 s of 56 s and the simplex
tree is 53.5 s (6.4 million simplices). The gap was not algorithmic.

## Where Helix's time went (`AlphaProfileDriver`, new)

Phases of the original code, single cold runs, seconds:

| d | cells | simplices | triangulation | faces + values | sort | verb | total |
|---|---|---|---|---|---|---|---|
| 5 | 986 | 10,591 | 0.47 | 0.76 | 0.03 | 0.41 | 1.70 |
| 6 | 2,959 | 44,959 | 1.08 | 2.47 | 0.07 | 0.45 | 4.10 |
| 7 | 7,783 | 174,447 | 2.63 | **16.2** | 0.45 | 0.67 | 20.0 |
| 8 | 17,316 | 577,257 | 3.8 | | | | 105 |

"Faces + values" grows 3.2x then 6.6x while the cells grow 3.0x then 2.6x (the simplices 4.2x, 3.9x): from d = 6 to 7
that is close to the square of the cells (2.6^2 = 6.9), not their number. A
stack sampler on the main thread put 26% (d = 5), 45% (d = 6) and 74% (d = 7) of the samples in `Simplex.toSet`,
called from `HelixDelaunay.topValue`:

```scala
containing(s.toSeq.head).filter(ds => s.toSet.subsetOf(ds.simplex.toSet)).map(_.circumsphere.radius).min
```

For each top simplex it scanned every cell that contains the simplex's smallest vertex (about T * (d + 1) / n cells for
the low vertices) and built two hash sets per cell. O(T^2 d / n) set constructions; at d = 8 that is 100 s of 105.

## Changes (`alpha/AlphaShapes.scala`)

1. **`topValue` is a hash probe.** `HelixDelaunay.smallestCircumradii(cells, top)`: one entry per `(top + 1)`-subset of
   each cell with the smallest radius of the cells that contain it. Same meaning as before (a simplex several cells claim
   keeps the smallest; a larger cell gives its radius to its subsets).
2. **`circumsphere` without boxing.** The edge vectors and the right-hand side were built with `Array.tabulate`, `map`
   and `sum`, which box a Double per coordinate; this runs once per face. Plain loops, the sums in the same order
   (bit-identical). Faces + values at d = 8, after change 1: 9.5 s, after change 2: 2.8 s.
3. **Faces and values per dimension, on demand.** `built` stops at the flat tables (a `FaceTable` per dimension and the
   values). The `Simplex` objects, the sorted order and the value map used to be made for every dimension, millions of
   objects the verb never reads (it cuts the stream at `maxDimension + 1`). Now `simplicesOfDimension(k)` and
   `sortedDimension(k)` make one dimension when it is first asked for, and `filtrationValue` probes the face table
   (`FaceTable.indexOf`) instead of a `HashMap[Simplex, Double]` (hashing a red-black-tree set per lookup). The public
   `simplicesMap`, `simplicesSortedMap`, `simplicesInDimension`, `simplices()` and `iterateDimension` keep their meaning;
   `simplicesInDimension` outside `0..highest` and `filtrationValue` of a simplex that is not in the complex still raise
   `NoSuchElementException`.

## Result (cold single runs, verb path: construction + the `Persistence` verb, `AlphaProfileDriver verb=only`)

| d | original | now | GUDHI (same machine) | now / GUDHI |
|---|---|---|---|---|
| 5 | 1.70 | 1.0 | 0.042 | 24x (cold JVM; the sweep reports warm medians) |
| 6 | 4.1 | 2.3 | 0.37 | 6.3x |
| 7 | 20.0 | 3.8-4.1 | 0.98 | 4x |
| 8 | 105 | 7.2 | 3.7 | 2.0x |
| 9 | not run (the quadratic term alone puts it near 7 minutes) | 28.7-32.2 | 13.4 | 2.2x |
| 10 | not run here (the quadratic term alone: about 40 minutes); timed out after 3600 s on the sweep server | 70.7 (triangulation 38.6, faces and reduction 32.9) | 56.3 | 1.3x |

Live heap at d = 10 is about 1.2 GB (GC log), so the sweep's `rss` column (41 GB at d = 8) was the 64 GB ParallelGC heap
filling with garbage, not data.

Barcodes against GUDHI (bottleneck distance, degrees 0..2, zero-length bars dropped): 5.6e-16 at d = 7, 1.0e-13 at d = 9
(GUDHI reports one more degree-2 bar there, at bottleneck cost 1e-13: a rounding-level tie), 7.8e-16 at d = 10.

## Interleaved warm A/B (the protocol of `_docs/developers-guide/performance.md`)

Original and new classes built apart (`git checkout 389a102 -- AlphaShapes.scala`, compile, copy the class directory;
then the PR's file), `PaperBenchmarkDriver task=alpha backend=helix`, one variant per JVM, one warm-up then three timed
computations, three JVMs per variant run in the order orig, new, orig, new, ...; median of the three per-JVM medians:

| d | original | new | factor | per-JVM medians (orig / new) |
|---|---|---|---|---|
| 5 | 0.407 s | 0.278 s | 1.5x | 0.41, 0.33, 0.44 / 0.28, 0.36, 0.25 |
| 6 | 2.415 s | 0.507 s | 4.8x | 2.42, 2.24, 2.52 / 0.54, 0.46, 0.51 |
| 7 | 14.9 s | 1.51 s | 9.9x | 14.8, 15.4, 14.9 / 1.66, 1.49, 1.51 |

Warm, the d = 7 run is 1.5 s against GUDHI's 0.98 s on the same machine. d = 8 and above were not repeated (one cold run
of the original takes 105 s); the cold numbers above are single runs.

## The 11-sphere workload (#134) is untouched

40 points of Tadasets' `dsphere(40, 11)` (R^12, all cospherical, seed 1), `AlphaProfileDriver verb=only` on the new code:
killed at 420 s, still in the triangulation phase (nothing after the header line). So the slow branch of #134 is in the
walk on cospherical input (`handleCosphericalPoints` and the repair), not in `topValue` or the faces; this PR does not
speed it up. Not profiled.

## Verification

- Every simplex with its value (hex doubles) and the emission order of every dimension, before and after, byte-identical on
  21 (input, backend) pairs: Helix and Bowyer-Watson, random clouds 2-D to 6-D, 6x6, 4x4x4 and 3^4 grids, 12 cospherical
  points plus the centre, a cloud with repeated points. The first version of this check missed the stream order; the dump
  now writes it.
- The existing alpha specs (`*Alpha* *Helix* *BowyerWatson* *Delaunay* *FastRepresentativesSpec* *Hyperplane* *Dispatch*`,
  82 examples) pass. New `DelaunayFaceTablesSpec` (7 examples): the stream of a dimension does not depend on the order in
  which dimensions are asked for; every dimension is in `filtrationOrdering` order; values do not decrease to a coface; a
  top simplex has the smallest radius of the cells containing it; `smallestCircumradii` on hand-built cells (claimed
  twice, larger cell); repeated points; the error and bounds cases.
- Not exercised by any input: the walk never returned a cell larger than `top + 1` or a simplex with two claimants on
  the 11 degenerate inputs tried (grids to 3^4 and 2^6, cube corners with centre), so `smallestCircumradii`'s min and
  subset branches are covered by the hand-built cells only.

## What is left (and what it says about the other columns)

- **Helix's triangulation is now 40-55% of its time** (38.6 s of 70.7 at d = 10; CGAL 1.4 s). Per frontier facet the walk
  does a Jacobi SVD (`Hyperplane.from`), a Breeze LU for the facet's circumcentre, and an O(n d) pass, with Breeze vectors
  and sorted-set simplices allocated around them. Replacing the SVD and LU by a Householder QR on primitive arrays would
  be the next step; it changes round-off in `Hyperplane.normal`, so it needs the degenerate-input specs and the dumps.
- The faces step still computes a Householder QR per face of every dimension; GUDHI does the same with a per-dimension
  cache of the facets' spheres.
- **Cech is flat by construction**: with no radius it is the same C(40, <= 4) = 102,000 candidate simplices at every
  ambient dimension, each a minimum enclosing ball of at most four points (its centre lives in a 3-D affine hull), so the
  ambient dimension only enters as O(d) arithmetic: 11.85 s at d = 5, 12.5 at d = 7, 13.4 at d = 25 (+6.5% from d = 6 to
  25, sweep server). The 12 s is a per-simplex constant of about 120 us (allocation around `MinimumEnclosingBall`), not geometry.
- **DQP is not flat**: 0.76 s (d = 5), 1.09, 1.78, 2.57, 4.52 (d = 10), 5.82, 6.30 (d = 15), then 6.1-6.3 to d = 25. It
  pays per simplex of the 3-skeleton that is present, and that skeleton grows with d until it is complete: tetrahedra
  3,818 (d = 5), 36,197 (d = 8), 84,006 (d = 12), 91,390 = C(40, 4) from about d = 15. After that the flat tail is the
  complete skeleton. (Share of simplices that are not Gabriel, n = 40: tetrahedra 99% at d = 5, 95% at 10, 82% at 15, 70%
  at 25, so the QP is still needed for most simplices at d = 25.) I did not instrument the QP's iteration counts.
- **Why DQP and Cech win from d = 7 and Helix/GUDHI cannot follow**: a triangulation pays for the whole Delaunay complex
  (577k simplices at d = 8, 1.9M at d = 9, 6.4M at d = 10 for n = 40), the barcode up to degree 2 needs the 3-skeleton
  (at most 102k). DQP at n = 40 takes 4.5 s at d = 10 against GUDHI's 56 s, and 6 s at d = 25. The triangulation wins when
  n is large and d small (the skeleton has C(n, 4) candidates), which is what `AlphaShapes.prefersDQP` models.

## Filed

#152 (Helix's walk, 40-55% of the time above 7-D), #153 (should `Default` without a radius pick DQP for few points: a
decision for the project lead), #154 (Cech's 120 us per simplex). Related and still open: #124, #135.

## Reproduce

```
sbt "export tda4j/Test/fullClasspath"           # then a plain classpath from target/out/jvm/.../{classes,test-classes} and the coursier jars
java -Xmx13g -cp $CP org.appliedtopology.tda4j.AlphaProfileDriver points.txt [backend=helix|bowyer-watson|dqp] [verb=only] [sample=true] [dump=file]
python3 -c "import sys; sys.path.insert(0,'bench'); import sweep_alpha as s, numpy as np; np.savetxt('p.txt', s.points('interior', 40, 8, 0))"
```
