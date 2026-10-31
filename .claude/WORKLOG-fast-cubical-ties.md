# FastCubical representatives on tilings: equal births, the smaller region dies (#290), 2026-10-31

Point-in-time snapshot. The rule that came out of it is in `rules/cubical.md` ("Equal births: the smaller component dies").
Background: `WORKLOG-truchet-tiles.md` (where the cost was found), `WORKLOG-fast-cubical-representatives.md` (the signed
union-find and why a representative costs its region).

## The problem

The framed Truchet distance image through `FastCubical` with cycles: 256² 0.3 to 0.9 s, 512² 4 to 5 s, 1024² 80 to 91 s,
1200² 262 s: about 100x for 16x the pixels. JFR: `packedRegionBoundary` and `IntFacetSums`, the walk over the members of each
dying region.

## What was measured first (counters in the engine, JVM, F17, `-Xms8g -Xmx8g`, ParallelGC)

Cells walked for representatives (the sum of the sizes of the dying regions of the reported bars), and how many of those
bars were an equal-births merge (`birthOf(ra) == birthOf(rb)`), with the cells they walked, and what the smaller side of each
of those merges would have walked:

| image | walked | equal-births merges | cells walked by them | cells of their smaller side |
|---|---|---|---|---|
| 258² | 1.51 M | 355 | 1.43 M | 77 k |
| 386² | 5.83 M | 770 | 5.66 M | 166 k |
| 514² | 14.85 M | 1,380 | 14.55 M | 323 k |

So 95 to 98 % of the walk was a few hundred merges between components born at the same value (one local maximum at every
lattice corner of the tiling, all equal), and the lower side of the facet died whether it was large or small, which after
the first few merges means the large accumulated region. The smaller side walks 19 to 45 times less, and stays linear in the
pixels (77 k, 166 k, 323 k for 66 k, 149 k, 264 k pixels).

## The change

Of two components born at the same value (IEEE `==` on the values, so -0.0 equals 0.0) the smaller dies, the facet's lower
side on equal sizes; two masked regions, both there from the start, are treated the same way (they were "the lower side
dies" before). `UnitSignedUnionFind` counts the elements of each root (`sizeOf`, 4 bytes per top cell, on about 14 for the
union-find's own arrays and 8 for the sorted facets). Nothing else changes: every bar's (birth, death) is determined by the
elder rule on values alone, so the bars and their order are the same for either choice (the list is in facet order, and each
merge's bar is (v, birth of the dying root), equal on a tie); only the cycle of a tied bar is the boundary of the other
component, equally valid (born at the bar's birth, a boundary at its death). `EagerFastCubicalReference` carries the same
rule (a component's size is its chain map's), so `FastRepresentativesSpec`'s equality still compares like with like;
`representativeCellsWalked` (`private[tda4j]`, one `Long` per engine instance) is the measurement the new spec reads.

## Results (A/B: baseline classes from HEAD against the change, one engine per JVM, interleaved, `FastCubicalProfileDriver`
and a copy of its Truchet generator)

| case | baseline | change |
|---|---|---|
| Truchet 514², median of 5 trials, 2 JVMs | 4.66 s, 4.47 s | 0.80 s, 0.70 s |
| Truchet 1026² (64 x 64 tiles), 2 JVMs on the change | 85.8 and 87.6 s (2 trials, 1 JVM) | median 4.0 s and 3.8 s (trials 2.7 to 5.7 s) |
| noise 1024², median of 4 trials, 2 JVMs | 5.08 s, 3.43 s | 3.79 s, 4.85 s |
| blob 1024², median of 4 trials, 2 JVMs | 4.71 s, 5.31 s | 3.62 s, 4.62 s |

The bars are identical on the Truchet cases (the same digest of (dimension, birth, death) over all bars). On the noise and
blob images, which have no equal births, the effect of the size array and the extra comparison is not resolved: the same
code varies from 3.4 to 5.1 s between JVMs on this machine, more than any difference between the two. Reported as
unconfirmed, as the project's rule asks. The 87 s against 4 s is not noise.

## Checks

- `FastCubicalTieBreakSpec`: a row of three equal peaks and one lone peak with a saddle between them (the lone pixel's
  boundary is the cycle of the new bar; 6 cells walked, 9 with the old rule), and a comb of equal peaks (walks less than
  10 cells per peak at 15, 30 and 60 peaks; the old rule walks 239 at 15). Mutation: the old rule (`then true`) fails the
  spec's three tie examples and both `FastRepresentativesSpec` equality examples (against the updated reference).
- `FastRepresentativesSpec` (equality with the reference and its validity examples), `FastCubicalHomologySpec`,
  `RelativeSpec`, `ImagesSpec` unchanged otherwise.

## What is left

- **A large region that dies as the younger by value is still walked whole**: a plateau at 3 with spikes whose heights rise
  in the order of the dual pass walks (pixels x spikes) / 2 cells (125 M at 512², 34.6 s; the same image with falling or
  random heights walks 65 k and 149 k at 256²). The cost is the sum of the dying regions' areas, where the cycle itself is
  the boundary; the way out is boundary sets merged small into large, which costs a hash insert on every merge and memory.
  Not done, no image of that shape was seen; `FastCubicalProfileDriver ... spikes` is the repro. #293.
- `FastAlphaHomologyEngine` keeps the old rule; whether equal alpha values matter is unmeasured. #294.
- Lazy representatives (the third idea of #290) were not needed and would change `PersistenceBar.annotation`.

## Traps

- The cost is only visible with representatives; `barsWithoutTopRepresentatives` hides it.
- A tie is a tie of VALUES, not of ranks: ranks under `Double.compare` separate -0.0 from 0.0, whose values are equal and
  whose births tie for the elder rule.
- A copy of the main classes is the baseline for an A/B (`git stash push -- src/main`, `sbt tda4j/compile`, copy
  `target/out/.../classes`, `git stash pop`); sbt's disk cache answers the second compile in seconds.
