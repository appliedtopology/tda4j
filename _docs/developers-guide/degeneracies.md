# Degeneracy behaviors that look like bugs but aren't

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

## Zero-persistence (zero-length) bars are real output, not noise to filter

`RipserCohomologyContext` emits these deliberately — e.g. an edge tied in filtration value with the
triangle that immediately kills it. Definition 3.2/Proposition 3.9's *apparent pairs* (see
[Persistence engines](persistence-engines.md))
**are** exactly the zero-persistence pairs — dropping them silently at the engine level would be wrong at
this stage of the pipeline, even though a downstream visualization might reasonably choose to filter them
before display. If you see a `[3.0, 3.0)`-style bar in test output, that's not evidence of a bug on its
own.

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
