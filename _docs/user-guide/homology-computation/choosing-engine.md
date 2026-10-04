---
layout: main
---

## Which persistence engine?

| Need | Engine (`engine=` for MATLAB/CLI) |
|---|---|
| Exploration, intermediate-filtration queries, representative cycles | `naive` (`CellularHomologyEngine`) |
| Fastest, most memory-efficient — the default for `complex=vr` | `ripser` (`PackedRipserCohomologyEngine`) |
| Large complex, want representatives for every bar including essential ones | `chunks` (`CellularPersistenceInChunksEngine`) |
| Cohomology (cocycle representatives) on `Cube`/`FiniteSimplicialSet`, or on Alpha/Cech/DTM/Sheehy/witness, where `ripser` doesn't apply | `cohomology` (`CellularCohomologyEngine`) |
| Alpha or Cech or DTM or Sheehy complexes, a Dowker complex, or a general (non-flag) witness complex | `naive` or `cohomology` (`chunks` also works for Cech, DTM-Rips, and Sheehy-Rips — not Alpha/DTM-Alpha/Dowker) |
| A lazy witness complex (the flag-complex variant) | `ripser` (`PackedRipserCohomologyEngine`, run directly on `WitnessMetricSpace`) or `naive`/`chunks`/`cohomology` |
| A cubical image, any ambient dimension `>= 2` — fastest option there | `fast-cubical` (`FastCubicalHomologyEngine`; H0/H1 only, no `Chain` reduction at all, in 2D specifically; a `chunks` hybrid for the residual middle dimensions at 3D+) |
| An alpha complex via `"helix"`, any ambient dimension `>= 2` — fastest option there | `fast-alpha` (`FastAlphaHomologyEngine`; H0/H1 only, no `Chain` reduction at all, in 2D specifically; a `chunks` hybrid for the residual middle dimensions at 3D+; `"DQP"` needs `naive`/`chunks`/`cohomology` instead; higher ambient dimension and point count make `FastAlphaTriangulationException` noticeably more likely — see `.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`) |

All engines are generic over the coefficient field (a prime finite field or floating point); `naive`,
`chunks`, and `cohomology` are also generic over the cell type (simplices, cubes, or simplicial-set
generators) — only `ripser`, `fast-cubical`, and `fast-alpha` are specialized (to Vietoris-Rips, to cubical
grids, and to `HelixDelaunay` triangulations, respectively). See the
[Developer's Guide's persistence-engines page](../../developers-guide/persistence-engines.md) for the full
detail.


