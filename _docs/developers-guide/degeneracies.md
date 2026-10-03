---
layout: main
title: Degeneracy behaviors that look like bugs but aren't
---


A dedicated page so you don't "fix" correct-but-surprising behavior. Each of these has, at some point,
looked like a bug to someone reading the output for the first time.

## Alpha complexes in degenerate (cospherical) position are not Delaunay subcomplexes

In degenerate (cospherical) position, the alpha complex is **not** a subcomplex of some triangulation you
could point to. `k` cospherical sites sharing a Voronoi vertex contribute a `(k-1)`-simplex — a unit grid in
the plane produces 3-simplices (one per unit square, since the four corners of each square are
cospherical), not just triangles from an arbitrary triangulation choice; a unit grid in R³ produces
7-simplices (one per unit cube). **Truncating at the ambient dimension gives the wrong homotopy type, not
merely a truncated one.** Users coming from CGAL or GUDHI will not expect this, because those tools
typically report a triangulation (a choice among several valid ones), not the actual alpha complex in the
degenerate case — but it's correct here, not a bug. `AlphaValidationSpec`'s grid test exercises a mild
version of this directly: two near-collinear rows produce two "sliver" simplices with circumradius roughly
40-60x the point cloud's diameter, and both the DQP and Helix backends correctly include them. See
[Alpha complex: DQP vs Helix](alpha-complex.md) for the developer-facing detail on both backends.

## Zero-length bars: computed, then left out

A cell paired with a cell entering at the same filtration value gives a zero-length bar `[v, v)`: an edge tied with
the triangle that kills it, for example (Definition 3.2's *apparent pairs* are exactly such pairs). The reduction
computes these pairs like any others; every engine then leaves them out of its output unless asked
(`includeZeroLength = true`), the shared predicate being `PersistenceBar.isZeroLength`. The filter acts on the true
pairing, never on a truncated bar: a class born at `f` and alive there is reported by `diagramAt(f)` as `(dim, f, f)`
(closed at `f` in `barcodeAt`). Tests of the reduction itself (the pairing invariant: every cell opens or closes
exactly one bar) must ask for them.

## A Vietoris-Rips complex with `maxDimension ≥ 2` always has ties

Every simplex of dimension ≥ 2 ties in filtration value with its own longest edge, by construction (a
simplex's filtration value — maximum pairwise distance among its vertices — is always realized by some
edge face of it). This is the *normal* case for VR complexes, not evidence of a degenerate or adversarial
input, and any reduction code path that implicitly assumes "ties are rare" will misbehave on completely
ordinary data. This is precisely why
[Hard-won invariants #2 and #3](gotchas.md)
about tie-break consistency exist and matter in practice, not just in adversarially constructed test cases.

## A quotient simplicial set can collapse a cell to a *degenerate* point, not just merge it with a peer

`quotient`'s attaching map is `G => SSetElement[G]`, not `G => G`, specifically because some quotients need
a cell to crush down a dimension entirely rather than merge with a same-dimension peer — the standard
Δ-complex model of ℝP² glues two of a filled triangle's three edges into a loop, but the third collapses
entirely onto a degenerate point over a vertex. If you're building a new attaching map and find yourself
wanting to map a generator to a lower-dimensional target, that's expected, not a sign the API is being
misused.

## A cubical or Cech complex having "extra" simplices/cubes at a shared tie is not a bug

The same underlying fact as the alpha-complex case above shows up differently in other constructions: a
cubical grid's dense T-construction cell count (`prod_i (2*shape(i)+1)`) is much larger than the pixel/voxel
count itself — a 256x256 image is 263,169 cells, not 65,536 — because every lower-dimensional face of every
pixel is its own cell, most of them shared between adjacent pixels. This is the correct cell count for the
cubical complex, not evidence of a construction bug.
