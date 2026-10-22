# Two-word simplex indices (2026-10-22)

The inventory of where a `Long` index is assumed, written before any of this, is `WORKLOG-wide-indices.md` (2026-10-04);
its item 4 (the packed Ripser engine) is what this worklog implements, items 1 to 3 (the VR streams' tie-break and
enumeration by index, `SimplexIndexing`'s own arithmetic beyond the new `WideSimplexIndexing`) are still `Long`.

Question from the project lead: "If we're already running into index size issues in our benchmarks, then we do need
something that fixes that. Can we adapt to pick higher bit counts when needed? ... Ideally leveraged once we have
reason to believe our current words are insufficient", then: "I would like it if the packed engines knew to switch
when they encounter potentially huge dimension layers." This is the derivation; the rules are in
`rules/engines.md`, the user-visible lines in `CHANGELOG.md`.

## Where a Long ran out

- `PackedRipserCohomologyEngine` (`Engine.Ripser`, `Auto` for point clouds and metric spaces): one `Long` combinatorial
  index per simplex. `SimplexIndexing` decodes by binary search over `binomial(d + s, s)` for `s` up to the number of
  vertices, so the bound is C(n + maxDimension + 2, maxDimension + 2) <= 2^63 - 1 (`SimplexIndexing.fitsLong`), a little
  stricter than "C(n, maxDimension + 2) fits": about 16,000 points to degree 3, 122,000 to degree 2. Beyond it
  `SimplexIndexing.binomial` threw `IllegalArgumentException` from inside the run.
- `CellularCohomologyEngine`'s packed path numbers cells by position in the list: no limit (it holds every cell).
- `ParallelPackedCohomology` (experiment): the key packs the edge block (1 + rank of the first longest edge) above
  `mask - index`, so it needed bits(max C(n, q), q <= maxDim + 2) + bits(edges + 1) <= 63. o3_4096 to degree 3 needs
  54 + 18 = 72. Fixed first (commit dd90552): the key is `mask - index` and the block rides beside it, ordered by
  (block, key), when the sum passes 63 (`Space.wide`; `forceWide` for tests). **Still one Long for the index itself**: at
  300 points to degree 11 it throws in `SimplexIndexing.binomial`. #194 keeps that.

## What was built

- `WideSimplexIndexing` (`streams/`): indices of up to 126 bits as two non-negative words, `low` the lowest 63 bits and
  `high` the rest, so index order is the order of (high, low). Binomial rows by Pascal's rule in two-word arithmetic,
  decoding by binary search, the cofacet, sparse-cofacet and facet cursors mirroring `SimplexIndexing`'s (same cofacets
  in the same order). Checked against `BigInt` past 2^63 (`WideSimplexIndexingSpec`); dropping a carry or a borrow fails
  two examples each.
- `PackedRipserCohomologyEngine`: the width is chosen in the constructor (`wideIndices = None`: wide exactly when
  `!fitsLong(n, maxDimension + 2)`), so a run that fits is the one-word code. `DiameterIndex(diameter, index, high = 0)`.
  Carried through: `eachCofacet` (the inlined cofacet loop gives index and high word), the heap working column
  (`hi` array only when wide; ties order by high word first), `Column.highs`, `Level.highs`, the pairing's `birthH` /
  `deathH`, the facet search of the apparent-pair test, `pushBoundary`, `boundaryOf`, the cycles (`involutedCycles`).
  The maps keyed by index (`basis`, `rowOfDeath`, `logs`, the cleared set, the involution's per-dimension maps) are keyed
  by a dense `Long` number of the two-word index: `WideKeys` (an open-addressing table over three primitive arrays),
  `newKey` numbers a cell, `keyOf` looks it up and gives `WideKeys.Absent` for a cell never numbered.
- `vertices(cell, size)` decodes either width; `Persistence` and the MATLAB facade call it in place of
  `si.decodeToArray(cell.index, ...)`.
- Beyond 126 bits (C(n, maxDimension + 2) > 2^126, hundreds of millions of points) the constructor refuses: "N points
  are too many to number the simplices of dimension d; lower maxDimension". The code review's suggestion (`Auto` falls
  back to `Cohomology` on predicted overflow) is **not taken**: `Cohomology` on a Vietoris-Rips stream is no way out
  at that scale. The default stream (`EnumeratingCofaceSimplexStream`) enumerates every index of a dimension
  (`SimplexIndexing.binomialInt` refuses past `Int.MaxValue`: C(20000, 5) is 2.6e19, C(20000, 4) already 6.7e15) and
  breaks ties by a `Long` index, and the engine holds every cell. Below 126 bits the packed engine itself now runs.

## Checks

- The 174 specs that touch the engine (`PackedRipserCohomologySpec`, `NeighbourListsSpec`, `PackedWorkingColumnSpec`,
  `InvolutionSpec`, `ZeroLengthBarsSpec`, `CircularCoordinatesSpec`, `RipserCohomologySpec`, `SimplexIndexingSpec`,
  `TDA4jSpec`) pass on the one-word path unchanged.
- `WideIndicesSpec`: forced two-word equals one-word term for term (pairs, cocycles, cycles, apparent pairs, simplex
  count; F_3 and F_17, neighbour lists on and off, apparent pairs on and off, generic, grid and asymmetric metrics); the
  width choice at the boundary; the refusal; `WideKeys` and `DiameterIndex` order and equality on cells that share their
  low word.
- `WideIndicesPastLongSpec` (JVM): runs the one-word engine cannot do. 300 points in 23 clusters of 13 to degree 11:
  the pairs are the clusters' together (each cluster run alone with one-word indices; a disjoint union pairs as its
  parts do, and an order-preserving relabelling keeps the colexicographic tie-breaks), also with the apparent pairs off
  (every column through the working column), simplex counts per dimension are binomial coefficients, apparent-pair and
  total counts add up. The boundary of the 12-dimensional cross-polytope among 276 far points (an 11-sphere, 531,441
  faces, indices of 70 bits): one essential bar in degree 11 from sqrt(2), and its cocycle pairs non-zero with its cycle.
- Deliberate breaks of the engine and which example catches them: heap order ignoring the high word (the no-apparent-pairs
  example), the facet search dropping it (the pair oracle), boundary pushes dropping it (everything), `DiameterIndex`
  equality and `WideKeys` comparing the low word only (the unit examples). **Not caught end to end**: the working
  column merging equal cells while ignoring the high word. It differs from the real thing only when two simplices of one
  degree share their low 63 bits, which no cloud here produces; it has no unit test of its own (the class is private).

## Measurements

(See the A/B section below once filled in.)

## Not done

- The streams and generic engines that still index by one Long: #198.

- The parallel experiment's own index (one Long, #194), and `BlockParallelReduction` (explicit matrices; dropped from the
  benchmark on the project lead's word, 2026-10-22: `tda4j-parallel-mt` is the same idea on implicit columns; the class
  stays because the parallel experiment's specs use its helpers).
- A cost measurement of the two-word path on a real run at the sizes where it is needed (a 16,000-point H3 run is
  hours): only the forced two-word path on runs that also fit one word, below.
