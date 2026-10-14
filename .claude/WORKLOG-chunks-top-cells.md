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
