---
layout: main
---

#### A faster engine for alpha complexes

```scala 3
val helix = HelixDelaunay(points)
val bars = FastAlphaHomologyContext[Double]().persistentHomology(helix) // H0 and H1, that's everything at 2D
```

For a point cloud built via `"helix"` (never `"DQP"` — it never builds an adjacency-aware triangulation at all,
so it can't supply what this engine needs), `FastAlphaHomologyEngine` computes the same barcode (with real
representatives) as the naive engine, via a dual-graph union-find rather than general `Chain` reduction, at any
ambient dimension `>= 2`. At 2D specifically the two union-finds (`H_0`/`H_1`) cover everything; at 3D and
beyond, the "middle" dimensions are handed to `PersistenceInChunksEngine` on a view that hides the real
top-dimensional simplices, the same hybrid `FastCubicalHomologyEngine` uses above. On a fraction of point
clouds — more likely at higher ambient dimension and point count (measured at roughly 1-in-18700 at ambient
dimension 2, but roughly 1-in-1666 at ambient dimension 3 with 20-30 points) — it throws
`FastAlphaTriangulationException` — a message written for you, not just for a developer: it says plainly that
this is not an error in your data, explains the `HelixDelaunay` limitation and the measured rates, and names
the fix (retry with `engine="naive"`/`"chunks"`/`"cohomology"`, none of which are affected). `matlab.TDA4j`'s
`engine="fast-alpha"` option (and the CLI's `--engine fast-alpha`) use this automatically for `complex=alpha`
with the default `alphaBackend=helix`, at any ambient dimension `>= 2`.

There's also a repair, not just a retry: `requireValidTriangulation=true` (MATLAB)/`--require-valid-triangulation
true` (CLI), off by default, fixes a facet-multiplicity violation before it can throw — nudging only the
near-tied points, re-running the same triangulation construction on the full point set, then recomputing every
simplex's circumsphere from your original coordinates so nothing about the result is contaminated by the fix
itself. Validated at ambient dimension 2 and 3; not yet at `d >= 4`.
