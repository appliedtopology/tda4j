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
