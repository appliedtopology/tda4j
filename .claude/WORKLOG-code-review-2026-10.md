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

## Area 2: engines — fuzzed, not read line by line

- **[probe] No disagreement found.** `Persistence` on random quantized point sets (ties on purpose): Ripser, Chunks, Naive
  and Cohomology agree bar for bar over F2/F3/F17, cycles and cocycles (360 runs), Cech and Vietoris-Rips with a
  threshold over F0/F2/F3/F5 with and without zero-length bars (about 160 runs), and 2-D/3-D images over FastCubical,
  Cohomology, Chunks and Naive (160 runs). Every cycle representative of every engine has zero boundary. The harness
  was a scratch `main` under `src/test` (not committed): compare `triples` after rounding, check `Chain.boundary`.
- Not covered by the fuzz: alpha and witness inputs, `includeZeroLength` for cubical images, dimension > 3.

## Area 4 (first part): `barcode/` distances and `io/`

- **[probe] Bottleneck and Wasserstein are cubic.** Correct (brute-force spec) but 1,500 bars take 22 s / 40 s. Issue #166.
- **[probe] File readers read the wrong format as a different space.** A full 3x3 matrix given to
  `CSV.readLowerTriangularDistanceMatrix` came back as a 4-point space with other distances; ragged rows, a ragged point
  cloud and Perseus values beyond the grid were accepted; a trailing blank line crashed `Ripser.readDistanceMatrix` with
  an `IndexOutOfBoundsException`; a header line gave a bare `NumberFormatException`. Fixed in the PR with row/line
  numbers in the messages (`MalformedInputSpec`, shared so it runs on Scala.js too).
- **[reading] ok:** `BarcodeDistance` conventions (diagonal distance for `LP`, essential bars), `DiagramPoint`.

## Not yet reviewed (in priority order)

the `homology/` engine sources (fuzzed above), the remaining streams (witness, Dowker, DTM,
sparse Rips, edge collapse), `barcode/` vectorizations and filters, `io/` Dipha/Gudhi, `alpha/` (not Helix), `matlab`/`cli` facade, `sset`, `plot`, Scala.js seams.
