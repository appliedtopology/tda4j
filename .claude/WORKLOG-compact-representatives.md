# Compact representatives for the grid engines (2026-10-09)

Point-in-time snapshot. Follows `WORKLOG-cubical-performance.md`: after that work the cubical paths were within a few
times CubicalRipser's TIME but still 8-15x its peak RSS, and the representatives were most of the memory (at 2048²
noise, 18.5M facet terms as `Chain[Cube, C]` heap chains: a tuple, a `Vector1` cube and its array per term, ~70 B, plus
a `PriorityQueue` with a 16-slot array per chain). The project lead asked for compact storage that keeps a fully computed
representative for every bar (no skipping, no deferred computation): only the cube OBJECTS are made when read.

## Design

`Chain[CellT: Ordering, CoefficientT: Field]` is now a sealed abstract class with two `private[tda4j]` storages:
- `HeapChain`: the old representation, unchanged (a `PriorityQueue`, arithmetic deferred). `Chain(...)`, `Chain.from`
  and all arithmetic make one.
- `PackedChain`: `keys: Array[Int]` (doubled-grid indices), `coefficients: Array[AnyRef]` (shared boxes) and a
  `CellDecoder` (`GridCubes`: strides and the coordinate boxes only). Canonical: distinct keys, ascending under the
  chain's order, nonzero coefficients. Reads (`terms`, `cells`, `leadingTerm`, `boundary`, equality, `show`) decode
  afresh and cache nothing; arithmetic and reductions read it through an iterator and make `HeapChain`s.

`Chain` holds its `Ordering` and `Field` once (`javap`: `Chain` has the two fields, `HeapChain` only `entries`, so a heap
chain is the same 24 bytes as before); the subclasses take theirs as plain parameters, pass them up, and resolve both
through `Chain`'s protected givens (a context bound on a subclass would keep a second copy).

How it got there (the project lead's calls, in order):
1. First version: a field of type `PriorityQueue | PackedTerms`. Rejected before compiling: it changes the
   `private[tda4j]` constructor, public in bytecode, and MiMa checks 0.5.1 against 0.5.0.
2. Second: `PackedChain extends Chain(null)`, overriding every method that reads the null heap. Worked and tested; the
   project lead: binary compatibility is not a strong requirement before 1.0, avoiding null is a much stronger one.
3. Sealed abstract `Chain` without context bounds (each subclass declaring its own, to avoid duplicate evidence
   fields). The project lead: the ordering (leading term, reduction) and the field are fundamental to what a chain IS;
   a bound-free `Chain` misstates the type. Right: the bounds were dropped for an implementation reason.
4. Bounds on `Chain`, stored there once, subclasses pass theirs up (above).
5. Not sealed (the project lead asked what sealing gains): nothing here. Exhaustiveness checks would be its benefit, and
   every match on the storages ends in `case _` (the generic path); the storages are `private[tda4j]`, so no user can
   match on them, and adding one is no break for users either way; outside subclasses are already impossible, since
   no class outside the package can implement `Chain`'s `private[tda4j]` abstract members. Sealing would only have
   forced every storage into one file and turned off MiMa's hierarchy check.
6. Open (the project lead: the library should be a place where the community can experiment, and `Chain`'s machinery is
   one place where experiments are useful; `private[tda4j]` abstract members made it unsubclassable in practice).
   Final: `Chain` is abstract with a public constructor; a storage passes the order and field up and implements ONE
   member, `entryIterator` (its stored entries, a cell possibly repeated or with a zero total); `terms`, `isZero`,
   `leadingTerm`, `rawEntries` and the collapses default over it (merge, drop zeros, sort under the order) and may be
   overridden for speed; equality is `terms == other.terms`. Reductions, arithmetic, `boundary` and the engines touch
   nothing else (their fast paths for `HeapChain` match on it, the rest goes through `entryIterator`).
   `tda4juser/ChainStorageSpec` (outside the package) pins the contract with a list-backed storage written as a user
   would. The protected givens of step 4 went: a user subclass with its own context bounds would see two candidates;
   `cellOrdering`/`coefficientField` are plain accessors, and the library's storages take plain parameters and use them.
   MiMa break recorded with commented filters in `build.sbt`. (The measurements below ran on the step-4 build; the
   storages do the same operations in the final one, accessor calls where step 4 had given forwarders, and the final
   build was spot-checked, below.)

Producers:
- Fast engine: H₀ single vertices and the dual's region boundaries. `cubeOrdering` is lexicographic on the encoding,
  axis 0 first, which is the row-major order of the doubled-grid index (`encodingWeights`: axis 0 heaviest), so the
  integer sums are sorted as `(key << 32) | sum` longs, no cube decoded. Coefficient boxes for -2..2 made once per run.
  Grids whose doubled grid does not fit in an `Int` keep heap chains (`GridCubes.fits`).
- Packed cohomology engine: cocycles and cycles, cells sorted by `(cellRank << 32) | key`, reversed for cycles. Its
  order was an inner class reading `cellRank`, so every representative pinned the whole engine (logs and all); it is
  now a top-level `GridCellOrder(cellRank, weight)` with the same arithmetic, which keeps only the rank table.

## Equality: formal sums, by the field's equality

The open design first compared `terms == other.terms` (each chain's terms in its own order). Two failures:
- `HomologySpec`: chunks' and naive's representatives compared unequal. Chains made by arithmetic in `Homology.scala`
  carry the class-level ordering of wherever the `RingModule` given was summoned (the documented generic-given capture)
  while their heaps keep the operand's order; the old equality sorted by the heap's order and so hid the mismatch.
- `tda4juser/ChainStorageSpec`: one formal sum compared unequal to itself. `FiniteField`'s `Fp` has several `Int`
  representations of an element (`Fp(2)` stays `2`, a sum normalises to `-1`), and which one a coefficient has depends
  on whether it was summed: a heap chain collapses in place when read, so a later sum kept a raw `2` where the other
  side normalised to `-1`. A latent bug of the old equality as well (it compared coefficients with `==`).
Now two chains are equal iff they have the same cells, each with coefficients equal by `field.isEqual`: order- and
storage-blind, as a formal sum is. Equality then no longer checks a packed chain's order for free, so the engine specs
check the storage invariant itself: every packed representative's terms ascend under its own ordering.

## Gates

- `PackedChainSpec` (new): random terms over F₂, F₃, F₁₇ and the reals, integer cells under an order AND its reverse:
  terms in order, cells, isZero, leadingTerm, equality both ways, rawEntries, plus/scale/negate/minus, `reduceBy` with a
  packed basis column and with a packed reduced chain, the empty chain, reads leave it unchanged; decoder round trip and
  "key order = `cubeOrdering`" on a 3×2×4 grid; boundary of packed cube chains.
- Engine specs assert `isPacked` (else a silent fallback passes every equality test) and that every representative's
  terms ascend under its own ordering (equality is order-blind).
- Mutations: the fast engine's boundaries stored in reverse order, the packed engine's direction flipped, a wrong H₀
  vertex key: each fails its engine spec (2 failures each). A packed chain's leading term taken from the wrong end:
  `PackedChainSpec` HUNG (25-minute timeout): `Chain.reduceLoop` subtracted a column whose leading coefficient was wrong
  and never cleared the pivot, forever. Pre-existing hazard of the shared loop (the packed engine has `orderBug` for
  the same reason). Now the loop throws when the pivot moves back (impossible in a correct reduction) or stays put for
  64 steps (rounding over the reals can keep it for a step or two: `ChainSpec` runs with epsilon 1e-25); the mutation
  fails in 13 s with the column and pivot named.

## Measurements (sandbox, as in `WORKLOG-cubical-performance.md`; A = the commit before, B = packed)

### The heap path: no measurable cost

`Chain`'s hot paths now match on `HeapChain` once per call, and `reduceLoop` makes one ordering comparison per step
(the guard). VR on 150 torus points, degrees 0-1, F₂, `-Xms4g -Xmx4g`, median of 7 after 2 warm-ups, A and B
interleaved, two rounds:

| engine | round 1 A / B | round 2 A / B |
|---|---|---|
| chunks | 1.647 / 1.695 (+2.9%) | 1.669 / 1.642 (-1.6%) |
| naive | 1.003 / 0.968 (-3.5%) | 1.026 / 1.008 (-1.8%) |

The differences change sign between rounds and stay within ±3.5%: noise, no effect seen.

### Live data: 3.0x less, and the bars themselves now dominate

Class histogram (`jcmd GC.class_histogram`, after a full GC) of the driver holding one 2048² noise diagram, F₂:
A 1.94 GB in 79.2M objects, B 0.64 GB in 18.3M objects.
- A's top: `Object[]` 634 MB (cube vectors' arrays, queue arrays), `Tuple2` 456 MB, `Vector1` 304 MB: 19.0M terms.
- B's terms: `int[]` 99 MB + `Object[]` 99 MB (18.5M keys and coefficient references, 1.3M arrays of each).
- B's rest, per bar and independent of representatives: `PersistenceBar` 32 B, two endpoints 64 B, their two boxed
  `Double`s 48 B (the endpoints are generic), `Some` 16 B, `PackedChain` 32 B, the list cell 24 B: about 210 MB.
  The driver's own triples (`Tuple3`, two boxed `Double`s and a list cell each) add ~125 MB, the image 34 MB.
So the next memory lever is the barcode's own representation (boxed endpoint values, `Some`), not chains.

### The grid engines: A (heap representatives) against B (packed), same harness and flags

Pinned heap (`-Xms8g -Xmx8g`), warm median of 3 after one warm-up, F₂, A and B runs of one configuration a few minutes
apart; "read" adds reading every representative's `terms` once inside the timed region (the driver's `read=true`; both
sides read the same number of terms, 19,006,197 at 2048² noise).

| case | A | B | B / A |
|---|---|---|---|
| 2-D noise 1024² | 2.02 | 1.68 | 0.83 |
| 2-D noise 2048² | 13.6 | 6.75 | 0.50 |
| 2-D blob 1024² | 1.56 | 1.42 | 0.91 |
| 2-D blob 2048² | 9.50 | 6.40 | 0.67 |
| 2-D noise 1024², read | 5.61 | 1.35 | 0.24 |
| 2-D noise 2048², read | 37.0 | 8.03 | 0.22 |
| 2-D blob 1024², read | 2.92 | 1.50 | 0.51 |
| 2-D blob 2048², read | 18.2 | 7.30 | 0.40 |
| 3-D noise 64³: fast / cohomology / cocycles | 3.58 / 3.81 / 4.00 | 3.64 / 3.62 / 3.55 | 1.02 / 0.95 / 0.89 |
| 3-D noise 96³: fast / cohomology / cocycles | 17.6 / 18.4 / 19.0 | 14.2 / 15.4 / 16.3 | 0.81 / 0.84 / 0.86 |
| 3-D blob 64³: fast / cohomology / cocycles | 1.83 / 2.19 / 1.90 | 1.73 / 2.01 / 1.85 | 0.94 / 0.92 / 0.97 |
| 3-D blob 96³: fast / cohomology / cocycles | 6.63 / 7.98 / 6.89 | 6.26 / 7.59 / 6.17 | 0.95 / 0.95 / 0.90 |
| 3-D noise 96³, read | 24.2 | 14.4 | 0.59 |
| `Engine.Cohomology` 2-D noise 1024²: cycles / cocycles | 7.28 / 9.85 | 6.11 / 6.65 | 0.84 / 0.68 |

Peak RSS without a pinned heap (`-Xmx8g`): 2-D noise 2048² 8.25 -> 5.40 GB (times 13.4 -> 6.9 s; one A trial took
27 s, near the heap's limit), 3-D noise 96³ 6.49 -> 4.20 GB.

- The 2-D default path is 1.1-2x faster computing; with every representative READ, 2-5x: a heap chain's `terms`
  collapses and sorts its heap (A spends ~24 s of 37 reading at 2048² noise), a packed one decodes in order (~1.3 s).
- In 3-D the representatives are a smaller share: within noise at 64³, 0.81-0.95 at 96³.
- A's 2048² noise median (13.6 s) is above the 11.0 s of the previous worklog's final run of the same code: run-to-run
  noise at that size, so the 2048² ratios are good to perhaps ±20%; the read rows' gap is far outside it.

### Spot check of the final build (open `Chain`, field-aware equality)

- Generic engines, a third round: chunks 1.682 / 1.703 (+1.2%), naive 1.023 / 1.094 (+6.9%). Naive never compares
  chains with `==` (its loop uses `isZero()`, unchanged), so four more naive rounds: B / A = 1.002, 0.997, 1.032,
  1.005. Round 3 was noise; no effect seen.
- 2-D noise 2048²: 7.21 s computing, 7.85 s with every representative read (the step-4 build: 6.75, 8.03): the same.
- Peak RSS without a pinned heap, 2-D noise 1024²: 1.58 GB (2.50 GB for the same point before packing,
  `WORKLOG-cubical-performance.md`'s final runs).
- References, peak RSS without a pinned heap, 3 trials: 2-D noise 2048² cripser 3.04 s / 1.02 GB, GUDHI 18.4 s /
  1.42 GB; 3-D noise 96³ cripser 3.30 s / 0.40 GB, GUDHI 7.89 s / 0.51 GB.

## Where the gap stands (with a representative for every bar)

- 2-D noise 2048²: 6.8-7.2 s, 2.2-2.4x CubicalRipser and 2.6x faster than GUDHI; peak RSS 5.40 GB, 5.3x CubicalRipser
  and 3.8x GUDHI (before packing 8.25 GB: 8.1x and 5.8x).
- 3-D noise 96³: 14.2 s, 4.3x CubicalRipser and 1.8x GUDHI; peak RSS 4.20 GB, 10x and 8.3x (before 6.49 GB: 16x, 13x).
- Live data of a held diagram: the packed terms (~8 B a term) are now smaller than the barcode's own objects (~160 B a
  bar: the bar, two generic endpoints boxing their values, `Some`). Peak RSS also holds the engines' transient working
  arrays, untouched here. Next levers, not started: the barcode's representation, then the engines' working sets.
