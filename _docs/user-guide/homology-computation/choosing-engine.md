---
layout: main
title: Which persistence engine?
---

## Which persistence engine?

All engines compute the same bars; they differ in speed, in what they accept, and in their representatives.
`Persistence` picks one for you (`Engine.Auto`): Ripser for the Vietoris-Rips complex of points or a metric space, the
fast cubical engine for images of two or more dimensions, and the cohomology engine for everything else. Ripser and the
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
