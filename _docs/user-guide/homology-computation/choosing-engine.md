---
layout: main
title: Which persistence engine?
---

## Which persistence engine?

All engines compute the same bars; they differ in speed, in what they accept, and in their representatives.
`Persistence` picks one for you (`Engine.Auto`): Ripser for the Vietoris-Rips complex of points or a metric space (its
[parallel version](#parallel-ripser) where there is more than one thread), the fast cubical engine for images of two or more dimensions, and the cohomology engine for everything else. Ripser and the
cohomology engine compute cohomology, which is much faster than homology in degree 2 (most cells of the top dimension
are cleared without work).

Every engine but the two fast ones gives **cycles** or **cocycles**, whichever `representatives` asks for. Each
computes one kind natively, and that kind is the faster one; the other is derived from the engine's pairing by one more
reduction (involuted persistent homology), which adds a little in degrees 0 and 1 and more in degree 2. The table
marks the native kind in bold. The fast cubical and fast alpha engines give cycles only; for cocycles of an image or
an alpha complex, `Persistence` uses the cohomology engine.

| engine | `Persistence.Engine` / MATLAB `engine=` | takes | representatives | use it for |
|---|---|---|---|---|
| chunks (`CellularPersistenceInChunksEngine`) | `Chunks` / `chunks` | any stream | **cycles**, cocycles | queries at intermediate scales (`diagramAt`); degrees 0 and 1 |
| naive (`CellularHomologyEngine`) | `Naive` / `naive` | any stream | **cycles**, cocycles | stepping through a computation, reference results |
| cohomology (`CellularCohomologyEngine`) | `Cohomology` / `cohomology` | any stream | cycles, **cocycles** | the default for everything but Vietoris-Rips and images |
| Ripser (`PackedRipserCohomologyEngine`) | `Ripser` / `ripser` | a metric space | cycles, **cocycles** | Vietoris-Rips: the fastest there, and the default |
| fast cubical (`FastCubicalHomologyEngine`) | `FastCubical` / `fast-cubical` | a cubical grid, 2-D and up | cycles | large images ([details](fast-cubical.md)) |
| fast alpha (`FastAlphaHomologyEngine`) | MATLAB `fast-alpha` | a Helix alpha complex | cycles | large planar point clouds ([details](fast-alpha-complexes.md)) |
| parallel Ripser | `ParallelRipser` | a metric space, `Z/p` | **cocycles**, cycles | Vietoris-Rips on several threads; what `Auto` picks there ([below](#parallel-ripser)) |

What each complex allows:

* **Ripser** needs the Vietoris-Rips complex of points or a metric space (including a lazy witness complex, through
  `WitnessMetricSpace`): its shortcuts rely on a filtration by diameter.
* **Chunks** takes any stream; the MATLAB facade does not offer it for alpha, DTM-alpha, the general witness complex and
  relations.
* **Naive** and **cohomology** take every complex.

The naive and chunks engines take any stream and answer queries at intermediate scales (`diagramAt(f)`); the naive
engine also advances step by step (`advanceTo`, `advanceFor`). The cohomology and Ripser engines run to the end in one
call. Over a field, homology and cohomology have the same bars, so the choice between cycles and cocycles is only about
the representatives you need (`representatives`, or MATLAB's `representativeType`): cocycles for [circular coordinates](../circular-coordinates.md), cycles to see where a
hole is. The [scaling up](../../tutorials/scaling-up.md) tutorial compares the four general engines on one data set.

### Parallel Ripser

`Engine.ParallelRipser` cuts Ripser's reduction into blocks that run on every processor. `Engine.Auto` chooses it
instead of `Ripser` for the Vietoris-Rips complex of points or a metric space whenever there is more than one thread
(a machine with one processor has one), and ask for it by name to use it on one thread too.
`Persistence(points, threads = 4)` limits one call to four threads (`threads = 1` is plain `Ripser` under `Auto`);
`-Djava.util.concurrent.ForkJoinPool.common.parallelism=N` sets the default for the whole program: `N` worker threads
beside the one that calls, so `0` is one thread. MATLAB and Java take the option `threads` and `engine=parallel-ripser`,
the command line `--threads` and `--engine parallel-ripser`, and an unset engine goes parallel there too ([MATLAB](../matlab.md#threads)).

* The bars are those of `Engine.Ripser`. The representatives are valid but need not be the same chains: simplices of
  equal diameter are ordered differently, so a cycle can differ from one machine to the next.
* Cocycles are what it computes natively and are much faster than `Ripser`'s; cycles take one more pass over the
  pairing, which also shares its work between the threads.
* Each representative is built before `Persistence` returns. With `computeAllRepresentatives = false` each is built when
  you first read it (`bar.representative.terms`), and the ones you never read cost nothing. Cycles are found all at once
  by the first one read.
* It works over `Z/p` for a prime `p` up to 46,000, drops zero-length bars (`includeZeroLength = true` is an error with
  the engine by name, and `Auto` uses `Ripser` for it), and its memory follows the number of edges within the
  threshold, not the square of the number of points. The distance of every pair is still computed once to find those
  edges, so a very large cloud wants a small `maxFiltrationValue`. `Auto` uses `Ripser` where it does not apply.
* JavaScript has one thread: `Auto` never chooses it there, and the engine by name runs on that one thread with the same bars.

### Equal edge lengths: `edgeTies`

When many distances are equal (points on a grid, a graph metric, integer distances), the order of equal edges decides
how many pairs Ripser finds as apparent pairs, which are paired without any reduction. Both Ripser engines take
`edgeTies` (`Persistence.EdgeTies`; MATLAB and Java `edgeTies`, the command line `--edge-ties`, spelled
`reverse-colexicographic` there) to order simplices by their last edge, the last in this order of its longest edges. An
edge is `{a, b}` with `a < b`, and its index is `C(b, 2) + a`:

| `edgeTies` | the order of the edges of one length | also known as |
|---|---|---|
| `Lexicographic` | by `a`, then by `b` | the default of `ParallelRipser` |
| `ReverseLexicographic` | by `a`, then by `b`, descending | |
| `Colexicographic` | by index, ascending | Bauer's `ripser-forest` and hybrid default, `--ties increasing` |
| `ReverseColexicographic` | by index, descending | `ripser.cpp` built with `EDGE_LEXICOGRAPHIC`, `--ties decreasing` |
| `Shuffled` | by a fixed hash of the index | a control with no structure, `--ties random` |

The bars never depend on it; the representatives can, and are valid either way. Unset, `ParallelRipser` uses
`Lexicographic` and `Ripser` orders simplices by diameter alone (the same as asking for no edge order at all). On inputs
without equal distances (random points) no setting changes anything. On a lattice the reverse orders leave fewer columns to reduce, and the sequential engine runs faster with them;
whether a different order is worth the change on your input is a question for a trial run, since the bars are the same.
The [developer's guide](../../developers-guide/persistence-engines.md) says what each order does.