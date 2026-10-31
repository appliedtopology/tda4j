# Truchet tiles: curves of a random tiling by homology relative to the boundary, 2026-10-31

Point-in-time snapshot. The rules that came out of it are in `rules/cubical.md` ("Relative to the boundary"); the tutorial
is `_docs/tutorials/truchet-tiles.md`, its spec `tutorial/TruchetTilesSpec`.

## The ask and the reading

Mikael: random Truchet tiles in a grid, white curves on black, homology relative to the boundary; "dominant cycles should
be closed curves in the pattern". Read as an example, not a library feature. The coordinator's worry was that arcs ending on
the boundary also give relative H_1 classes. They do, and that is what "closed relative to the boundary" means: a curve from
edge to edge is a cycle of the quotient. So the claim holds with "closed" read in the quotient, and fails if it means the
closed loops only.

## What is true (all of it checked, see the spec)

For a Truchet pattern of `n x n` tiles (quarter-circle arcs about opposite corners, joining side midpoints), the curves are
loops and arcs from boundary to boundary; there are always `2n` of the latter (4n border midpoints, two ends per curve).

- X_0 = the curves, A_0 = small intervals at the curve ends. H_1(X_0, A_0) has one class per curve (loop or arc): the faces of
  the arrangement are `1 + #curves`, and by duality `rank H_1(X, A) = #components of the complement in the square - 1`
  (Alexander duality on X with the outside disc glued on). H_0(X, A) = the components that never touch the boundary = the
  loops. H_2 = the picture, born where the last pixel enters.
- Binary image (curve 0, rest 1): exactly the bars [0,1) of the curves, [0,1) of the loops in degree 0, and [1, inf) in
  degree 2. Right counts, no ranking.
- Distance image (pixel = distance to the nearest arc of its tile, less 1): the curve bars last 6.29 to 6.51 pixels (side 16),
  then a gap to 4.90, then bars under a pixel. The 4.90 bars are the lattice corners: each corner is a local maximum of the
  distance (exactly half a tile from a curve in every tile around it), so `(n+1)^2 - 1` bars are longer than a pixel in
  all. Loops and arcs are NOT separated by persistence (same range). Verified on 8 seeds at 16 tiles, 24 patterns for the
  framed image, and the page's seeds.
- What separates them: the representative. The ordinary boundary of a loop's relative cycle is zero; an arc's is a nonzero
  chain on the boundary (the connecting map to H_0(A)). The page uses the simpler "an edge of the cycle ends on the
  boundary"; the spec checks that against the boundary chain bar by bar. (The chain has more than two points: a stroke
  is two pixels wide, so an end is a few vertices summing to +1 or -1.)
- Local distance suffices: the distance to the arcs of the pixel's own tile gives the same bars as the nearest of the 3 x 3
  neighbouring tiles' arcs (checked at 16 tiles).

## The framed-image identity (the way to scale)

`Relative.ofBoundary(grid)` runs the general cohomology engine: 3 s at 128², 18.6 s at 256². `FastCubical` takes plain
images only, but for a 2-D image: bars of the pair in degrees 0 and 1 = bars of the image in a frame of one pixel below
every value, minus the frame's essential component and the one H_1 bar born at the frame's value; the pair's H_2 is born
where that bar dies (never with a masked hole). Proof: the pair's chain complex is the quotient by the frame subcomplex, the
long exact sequence gives H_k(P, Z) = H_k(P) / (image of the frame's class), and a submodule that is an interval born at the
minimum of the index set is injective, so it splits off. Checked bar for bar in `RelativeSpec` (40 random images with ties
and NaN masks, p = 2 and 3) and on the page's pattern; the mutation "boundary = upper faces only" fails four specs.

## Cost (the limit found)

Framed image through `FastCubical` with cycles, 16 px tiles: 256² 0.3 to 0.9 s, 384² 1.4 to 1.7 s, 512² 4 s, 1024² 80 to
91 s, 1200² (100 tiles of 12 px) 262 s. JFR: the time is `packedRegionBoundary` / `IntFacetSums`, the walk over the members
of each dying region. Many equal local maxima make ties decide deaths, so the region that dies is often the large one.
Filed as #290. The page therefore goes to 24 x 24 tiles (384²).

## Open (issues)

- #290: representative cost of `FastCubical` on tilings.
- #291: `Engine.Auto` for `Relative.ofBoundary` on 2-D grids (18.6 s against 0.7 s at 256²), and the facades (MATLAB, CLI,
  Python, JS) for an image relative to its boundary.
- Not done: a benchmark case. The tiling would make a good `FastCubical` representative stress case, but a server run decides
  what goes in the paper (Mikael), so it was left out.

## Traps

- `pkill -f 'pattern'` inside a Bash command kills the shell itself (the pattern is in the command line): use `pkill -f
  '[p]attern'`.
- Tutorial specs live in `src/test/scalajvm/.../tutorial/`, not in `src/test/scala`; the generated `...Script` objects are
  compiled on Scala.js too (no `java.io` in a page script), but the specs run on the JVM only.
- A relative cycle's ambient boundary is spread over a few boundary vertices, not two; assert emptiness and support on the
  boundary, not a count.
