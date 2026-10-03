---
layout: main
title: Which persistence engine?
---

## Which persistence engine?

All engines compute the same bars; they differ in speed, in what they accept, and in their representatives.

| engine | `Persistence.Engine` / MATLAB `engine=` | takes | representatives | use it for |
|---|---|---|---|---|
| chunks (`CellularPersistenceInChunksEngine`) | `Chunks` / `chunks` | any stream | cycles | the default: large complexes of any kind |
| naive (`CellularHomologyEngine`) | `Naive` / `naive` | any stream | cycles | stepping through a computation, reference results |
| cohomology (`CellularCohomologyEngine`) | `Cohomology` / `cohomology` | any stream | cocycles | cocycle representatives on any complex |
| Ripser (`PackedRipserCohomologyEngine`) | `Ripser` / `ripser` | a metric space | cocycles | Vietoris-Rips: the fastest there, and the MATLAB default |
| fast cubical (`FastCubicalHomologyEngine`) | MATLAB `fast-cubical` | a cubical grid, 2-D and up | cycles | large images ([details](fast-cubical.md)) |
| fast alpha (`FastAlphaHomologyEngine`) | MATLAB `fast-alpha` | a Helix alpha complex | cycles | large planar point clouds ([details](fast-alpha-complexes.md)) |

What each complex allows:

* **Ripser** needs the Vietoris-Rips complex of points or a metric space (including a lazy witness complex, through
  `WitnessMetricSpace`): its shortcuts rely on a filtration by diameter.
* **Chunks** takes any stream; the MATLAB facade does not offer it for alpha, DTM-alpha and the general witness complex.
* **Naive** and **cohomology** take every complex.

The naive and chunks engines take any stream and answer queries at intermediate scales (`diagramAt(f)`); the naive
engine also advances step by step (`advanceTo`, `advanceFor`). The cohomology and Ripser engines run to the end in one
call. Over a field, homology and cohomology have the same bars, so the choice between cycles and cocycles is only about
the representatives you need: cocycles for [circular coordinates](../circular-coordinates.md), cycles to see where a
hole is. The [scaling up](../../tutorials/scaling-up.md) tutorial compares the four general engines on one data set.
