# WORKLOG: code review, 2026-10-20 (checkpointed area by area; compute budget is limited)

Method: read the code of one area at a time, probe suspicions by running code (scratch specs, not committed), file an
issue per finding that is not fixed on the spot, fix the small and clearly correct ones in a draft PR. Evidence labels:
**[probe]** ran code, **[reading]** read, not executed. Docs are out of scope (a separate review). Open PRs on Helix and
alpha (#155) and the release workflow (#150, #151) were left alone.

## Area 1: algebra core (`FiniteField`, `Field`, `Chain`, `Simplex`) — read

- **[probe] `FiniteField(p)` with a composite `p` fails with "/ by zero"** at first use (and `FiniteField(4)` would
  otherwise be a ring with zero divisors). Fixed in the PR: the constructor requires a prime and says so.
- **[probe] A large prime exhausts memory.** The inverse table has `p` entries: `p = 1_000_000_007` OOMs at -Xmx2G, `p =
  2^31 - 1` fails at allocation; above `2^30` the unnormalised sums overflow `Int`. Issue #164 (needs a decision: bound
  or on-demand inverse, with an A/B).
- **[reading] ok:** `Field.toDouble`, `Chain.reduceLoop` (pivot-monotonicity guard), `PackedChain`, `Simplex`
  boundary signs (iterator-ordered, as the comment says). `HeapChain.collapseHead` merges cells by `Ordering.compare == 0`
  while `collapseAll` merges by `equals`/`hashCode`: identical for every cell type in the library, a trap for a user
  cell whose order ties unequal cells. Not filed (a note for the `Chain` doc if anyone extends `OrderedCell`).

## Area 3 (first part): coface streams — read `SimplexStream.scala`, `SimplexIndexing.scala`, `CechStream.scala`

- **[probe] `RipserCofaceSimplexStream` never updated `currentDimension` past dimension 0**, so dimension `d + 1` never
  reused the kept simplices of dimension `d`: it re-enumerated all `C(n, d + 1)` subsets and evaluated the filtration
  value on each. Cech, Witness, Dowker, DtmRips and SparseRips all run on this loop, so a sparse complex cost as much as a
  dense one (14 random points in R^3, threshold 0.3: dimension 3 made 364 filtration evaluations and kept 0; after the
  fix, 0). The complex was always correct (the rebuild is equivalent), which is why no spec noticed; the spec compared
  sizes only. Fixed with one line plus a spec that counts evaluations and compares cell for cell with the enumerating
  stream. Possibly part of #154 (Cech cost per simplex): not measured.
- **[reading] ok:** `NeighbourLists.within` caps and overflow handling, `SimplexIndexing.binomial` overflow guard,
  `decodeToArray` parity with `apply`.

## Not yet reviewed (in priority order)

`homology/` engines (Homology, PackedRipser, RipserCohomology, Involution), the remaining streams (witness, Dowker, DTM,
sparse Rips, edge collapse), `barcode/`, `alpha/` (not Helix), `io/`, `matlab`/`cli` facade, `sset`, `plot`, Scala.js seams.
