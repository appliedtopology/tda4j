# #51: the chunks engine on Vietoris-Rips input (started 2026-10-14)

Profile first, as the issue's "what done looks like" and CONTRIBUTING.md ask. Driver: `ChunksProfileDriver` (test
scope, next to `SingleEngineProfileDriver`), one engine per JVM, phases timed apart:
`java -cp $CP org.appliedtopology.tda4j.ChunksProfileDriver <n> <ambientDim> <maxDim> [seed] [trials] [prime] [skipReps]`.
The classpath comes from `sbt "export Test / fullClasspath"` (sbt 2 prints `${OUT}`/`${CSR_CACHE}` placeholders and
`>sha256-` suffixes: substitute `target/out` and `~/.cache/coursier/v1`, strip the suffixes).

## Baseline (n = 70, 3-dimensional uniform points, seed 42, maxDimension = 1, p = 17, one trial each)

Cells per dimension 70 / 1764 / 25091. Machine noise is large here (two runs of the same code: 3.9 s and 6.4 s for the
reduction), so only the large effects below are claimed.

| phase | ms |
|---|---|
| VR stream, every dimension enumerated | 440 - 950 |
| engine state (`allCells`, cell index) | 370 - 630 |
| reduction (`pairing`) | 5100 - 7600 |
| representatives (`barcodeAt`, 90 bars) | 3500 - 6000 |
| total | 9650 - 13200 |

The issue's "about 13 s for 70 points" reproduces. A 2-dimensional cloud of the same size is the same (13.2 s).

JFR (`settings=profile`, 707 execution samples), inclusive:

- `FiltrationOrdering.compare` 83% -> the stream's `filtrationValue` 60% (a `TrieMap.getOrElseUpdate` keyed by the
  `Simplex`, i.e. a `TreeSet`): hashing a `TreeSet` (`MurmurHash3.unorderedHash` over a red-black-tree iterator) and
  comparing keys on a hit (`TreeSet.equals`) allocate `RedBlackTree` iterators (self time: `TreeIterator.<init>` 23%,
  `INode.rec_lookup` 19%, `KeysIterator` 7%, `EqualsIterator` 6.5%). The cost is per comparison, not per reduction step.
- `Chain.reduceLoop` 75%, of which `updateMap` 64%: the `TreeMap` accumulator compares cells on every update.
- `advanceAll` 92% (inside `pairing` 52% AND inside `barcodeAt` 40%: see Step 1).

So the 13 s is mostly a constant factor (a hash of a `TreeSet` per comparison), plus repeated work; it is not an
intrinsically superlinear reduction.

## Step 1: `advanceAll` runs once

`barcodeAt` (and every `diagramAt`/`pairing`) calls `advanceAll()`. Its idempotency comment was true for cleared and
paired cells only: a top cell whose boundary reduces to zero is neither (it is a positive cell of the top dimension,
`essentialSimplices`), so `processCell` reduced it again on every call. A `private var advanced` guard, set when the
last pass finishes, removes the repeat. The stream is fixed at construction, so nothing can have changed.

- Measured (same cloud): `barcodeAt` 3500 - 6000 ms -> **56 ms**; total 12.0 s -> 5.4 - 7.4 s.
- Spec (`PersistenceInChunksSpec`, "The reduction runs once ..."): a counting `Fp` field; after the first `advanceAll`
  further `advanceAll`/`diagramAt`/`pairing` add no field operations. It FAILS without the guard (152 632 operations
  against 31 636: the repeat did four times the work of the first run) and passes with it.

## Step 2: compare cells by rank, not by the stream's ordering

After step 1 the profile is the same shape: `FiltrationOrdering.compare` ~86% inclusive. `FiltrationOrdering.canonical`
asks the stream's `filtrationValue` four times per comparison (`isDefinedAt` and `apply` on each side), and for a
Vietoris-Rips stream each is a `TrieMap` lookup keyed by the `Simplex`, a `TreeSet`: hashing it and comparing keys
walk a red-black tree through freshly allocated iterators. A reduction compares cells constantly (`Chain`'s `TreeMap`
accumulator).

Also visible: at `maxDimension = 1` the chunk windows do nothing. The top cells' pivots are edges, which sit in the first
chunks, so `stop` fires at the first step for every triangle outside them; they all land "in limbo" and the
reconciliation loop in `advanceAll` (72% of the samples) reduces them one by one, with no window. The engine is then plain
column reduction, and the cost is the comparison, not the schedule.

Change (`HomologyState`): the stream's cells are ranked once, by the stream's own ordering (`allCells.sorted`, TimSort on
runs that are already ordered; cells it calls equal share a rank), into an `IdentityHashMap`. `given Ordering[CellT]` compares
two ranks when both cells are such objects, and otherwise defers to the stream's ordering, so the answer is always the
stream's answer. `boundaryChain(sigma)` maps a cell's boundary faces (fresh objects from `boundary`) to the stream's own
objects through `cellIndex`, once per face instead of once per comparison.

Why this is safe, stated as the invariant: for every pair of cells the new ordering returns the sign the stream's ordering
returns. The ranks are a sort by that ordering; the fallback is that ordering. Pinned by the full pairing/diagram/
representative comparison against the naive engine (which keeps the stream's ordering) on tie-heavy input, and by a spec
whose stream builds fresh equal cells on every iteration, defeating the identity lookup (`PersistenceInChunksSpec`).

### A/B (cold JVM per trial, 5 trials per configuration, F_17, medians; A = step 1, B = step 1 + step 2)

| input | A reduce | B reduce | A total | B total |
|---|---|---|---|---|
| 70 points, R^3, maxDimension 1 (25 091 triangles) | 4133 ms | 1033 ms | 5216 ms | 1872 ms |
| 70 points, R^2, maxDimension 1 (29 279 triangles) | 4790 ms | 1189 ms | 5740 ms | 2096 ms |
| 30 points, R^3, maxDimension 2 | 1249 ms | 412 ms | 1699 ms | 816 ms |

Totals include the stream (250 - 510 ms), the engine state (110 - 340 ms) and the representatives (30 - 45 ms). Run-to-run
spread is large (A on 70/R^2: 5159 - 8600 ms total); the effect (3 - 4x on the reduction) is well outside it. Pair counts
and bar counts are identical in A and B (1764 pairs, 90 bars; 1884 pairs, 83 bars).

Against the issue's starting point (about 12 - 13 s for 70 points at maxDimension 1, same driver): about 2 s, i.e. ~6x.

## Step 3 (2026-10-15, after 0.6.0): what shipped, `BarcodeRegressionSpec`, and what is not done

Steps 1 and 2 were merged (#84, #86) and are in v0.6.0 (`git merge-base --is-ancestor` says so); the branch was restarted
from `scala` (0.6.1-SNAPSHOT). 0.6.0's changelog had no line for them; added now.

**The review did not happen.** The adversarial review workflow (three reviewers: ordering equivalence, run-once guard, test
adequacy; two skeptics per finding) returned `confirmed: []` because every reviewer died on the account's usage limit
before reading anything ("You've hit your weekly limit"). An empty result there means NOT REVIEWED, not "no findings".
What stands behind steps 1 and 2 instead: the specs named in Steps 1 and 2 (the first fails without the guard), the full
suite on the branch (1138 tests at the time of step 1; re-run at the end of this step), and my own reading of the
invariant (the ranks are a sort by the stream's ordering and the fallback is that ordering). Re-run it with
`Workflow({scriptPath, resumeFromRunId: "wf_9ed42f81-46d"})`, or with `/code-review`, when the limit allows.

### `BarcodeRegressionSpec`: the generator range, measured

Sweep (`RegressionRangeDriver`, chunks engine, degrees 0..3, `diagramAt(5.0)`, uniform points, 2 GB heap, one case per JVM,
90 s timeout; "oom" = `OutOfMemoryError`), milliseconds:

| case | ambient | 25 pts | 40 | 60 | 100 | 150 |
|---|---|---|---|---|---|---|
| VR (unthresholded, to dim 4) | 2 | 1145 | 13080 | >90 s | oom | oom |
| VR | 6 | 1821 | 12421 | >90 s | oom | oom |
| VR | 10 | 1062 | 16862 | >90 s | oom | oom |
| Alpha Helix | 2 | 328 | 298 | 330 | 372 | 447 |
| Alpha Helix | 6 | 1687 | 4323 | 12191 | 42535 | >90 s |
| Alpha Helix | 10 | 30961 | >90 s | >90 s | >90 s | >90 s |
| Alpha DQP | 2 | 324 | 366 | 419 | 733 | 1444 |
| Alpha DQP | 6 | 1327 | 4586 | 7207 | 19482 | 60998 |
| Alpha DQP | 10 | 21366 | oom | oom | >90 s | >90 s |

The limit is the size of what the constructions enumerate (C(60, 5) = 5.4 million simplices for the VR case), not the
engine: the original range (25 - 150 points, 2 - 10 dimensions) cannot run in CI whatever the engine does. New range:
25 - 30 points, 2 - 6 dimensions. Corner (30 points, 6 dimensions, two seeds): VR 3.5 and 6.8 s, Helix 1.9 and 2.1 s,
DQP 1.9 and 3.1 s; the spec itself: 2 s of test time (3 examples), passing three times in a row.

### Not done (#51 stays open)

- **The naive engine** (`CellularHomologyEngine`) has the same ordering cost (`given Ordering = stream.filtrationOrdering`,
  `processingOrder` sorts with it) and is untouched. It is a cursor, so it has no repeated-reduction problem. Same rank
  trick expected to apply; not measured.
- **Every top cell is still reduced.** At `maxDimension = 1` the chunk windows never fire for triangles (their pivots are
  edges, all in the first chunks), so everything runs in the reconciliation loop: plain column reduction, ~44 us per
  column after step 2. Most columns (23k of 25k) reduce to zero. A sound way to skip them would need the cohomology
  direction (Ripser reduces the positive `maxDimension` cells' coboundaries, never the top cells), which would make
  Chunks no longer an independent homology engine; or an exact criterion for "no class of degree `maxDimension` is
  left alive", which exists for the default Vietoris-Rips threshold (the enclosing-radius complex is a cone, so
  beta_d = 0 for d >= 1) but not in general. Not attempted; project lead's call.

## Step 4 (2026-10-15): the naive engine capped at a degree

Request from the project lead: get the naive engine to track only the top-dimension chains that bound a cycle one degree
down ("we shouldn't need to look very much at C_d for any calculation capped at d"). Issues filed from the review first:
#88 (rank ordering vs a partial `filtrationValue`, by reading only) and #89 (the fallback's sign is not pinned; the
mutation check was RUN: `-streamOrdering.compare(x, y)` passes all 18 examples of `PersistenceInChunksSpec`).

**What it does.** `CellularHomologyEngine.persistentHomology(stream, k)` (new overload; the one-argument form is the
uncapped reference, unchanged) caps the cursor at homological degree `k`; `Persistence` calls it for `Engine.Naive`.
Cells above dimension `k + 1` are not read. A cell of dimension `k + 1` (`reduceTopCell`):

- is not reduced while no class of degree `k` is open (`openAtDegree == 0`);
- otherwise is reduced, and if its reduced boundary is zero it leaves NOTHING (a class of degree `k + 1`, which nobody
  asked for: no `positives` entry, no V-column);
- if it is nonzero it records `boundaries(pivot)` (later top cells reduce by it) and the pair (barcode entry, `pairing`),
  and nothing else: no `generators`, no `negativeVCols`, no V-column fold.

**Why it is exact.** The skip rule: the boundary of a `k + 1` cell is a `k`-cycle; reduction against `boundaries` and, past
the negative `k`-cells, `negativeVCols` ends at a cell that is in neither. Every `k`-cell is positive or negative; a
positive one is either open (in `positives`) or closed (a key of `boundaries`), so that cell is OPEN, which is the
invariant `reduceCell` already checks by throwing. With no open class of degree `k` the reduction can only end at zero, and
a zero result leaves nothing. The dropped bookkeeping is read only by cells of dimension `k + 2`, which are not read, and by
the V-columns of other `k + 1` cells, which are dropped too. The representatives of degree-`k` bars are the V-columns of
`k`-cells, computed as before.

**What it does not do.** It still reduces every top cell that arrives while a class is open (a nonzero reduction must be
done to know which class it closes). It is `maxDimension`-aware only through the explicit overload: the uncapped cursor, and
`PersistenceEngine.naive`/`naiveCocycles` (so the MATLAB and CLI facade) are as before.

**Binary compatibility.** The cap is a new trailing constructor parameter of `HomologyState`; the old six-argument
constructor stays as an auxiliary one. `mimaReportBinaryIssues` against 0.6.0 passes with no filter.

### Specs (`NaiveCappedSpec`)

1. For F_2, F_3, F_17 x three inputs (two random clouds, a tie-heavy grid) x `k` in 0..2 x a stream built to `k + 1` and to
   `k + 2`: the capped diagram (zero-length bars included), the pairing up to degree `k` and every representative equal the
   uncapped engine's; no bar above `k`. (More than 1000 bars compared.)
2. The exact count: on the unit square (4 vertices, 6 edges, 4 triangles) the uncapped engine calls `boundary` 14 times, the
   capped one 13 (the fourth triangle finds no open class), through a counting `OrderedCell`; the bars are the same.
3. A counting field: the capped run does strictly fewer field operations.

Mutation checks, run: `if openAtDegree > 0` replaced by `if true` fails (14 != 13); by `if false` fails (10 != 13 and the
bars differ).

### A/B (cold JVM per run, 3 runs per cell, F_17; ALL uncapped runs and then ALL capped runs, not interleaved)

| input | uncapped reduce | capped reduce | uncapped total | capped total |
|---|---|---|---|---|
| 70 points, R^3, `maxDimension` 1 (25 091 triangles) | 5014 ms (4887 - 5204) | 450 ms (401 - 452) | 6229 ms | 1295 ms |
| 70 points, R^2, `maxDimension` 1 (29 279 triangles) | 5173 ms (4820 - 8065) | 418 ms (382 - 424) | 6032 ms | 1636 ms |
| 30 points, R^3, `maxDimension` 2 (5 270 tetrahedra) | 1114 ms (1094 - 1233) | 440 ms (434 - 446) | 1500 ms | 852 ms |

Pairs, essentials and bars up to degree `k` are identical in every pair of runs (1764 / 1 / 90; 1884 / 1 / 83; 1641 / 1 /
35). The two effects (not reducing late top cells, not building V-columns for the others) are not separated. The cost that
is left in a capped run: the stream (0.4 - 1.1 s), the cursor's sort (0.35 - 0.6 s), the reduction (0.4 s).

### Not done

- The naive engine still orders cells by `stream.filtrationOrdering` (the four `TreeSet`-keyed lookups of Step 2); the rank
  trick would apply to it as it did to Chunks.
- `Chain.reduceBy` still builds a reduction log that `reduceTopCell` discards.
- The facade path (see above).
