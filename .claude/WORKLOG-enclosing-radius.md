# The minimum enclosing radius: a cheap scan, and the verb's default radius for alpha (#209, 2026-10-24)

Point-in-time record. The rules that came out of it are in `rules/streams.md` and `rules/alpha.md`.

## Why

#209 measured 1.7-2.8 s for `minimumEnclosingRadius` alone on 10,000 uniform points in R^3, 39 % of the samples of
`Persistence(points, complex = AlphaShapes)`, and 95 % of the 24 GB allocated were `java.lang.Double` from the per-point
`elements.map(y => distance(x, y))`. Mikael's decisions (2026-10-24): keep it a `lazy val`, scan arrays with early stops; and
it is not only the scale of `significant()`: it is the radius the verb runs to by default, since the barcode does not change
after it.

## What was done

- `FiniteMetricSpace.scanEnclosingRadius` (protected, the hook behind the lazy val): `elements.toIndexedSeq`, two `while`
  loops over primitives, a point is dropped as soon as its running maximum reaches the best so far. If every point has an
  infinite distance (sparse spaces) a second pass takes the largest finite one, as before. An empty space throws
  `UnsupportedOperationException` (it did, from `empty.min`).
- `EuclideanMetricSpace` overrides the hook on squared distances. sqrt is monotone and correctly rounded, so min-max of squares
  then one sqrt is the same double as min-max of the roots, and `pointSqDistance` is symmetric exactly. It never touches the
  distance cache (n^2 doubles up to 8,192 points: 512 MB), which the generic scan would fill (~300 ms at 5,000 points).
- `AlphaShapes.fromPoints` without a radius calls the new `AlphaShapes.upToEnclosingRadius`: the triangulation the radius-free
  `Default` picks (BowyerWatson up to 4 dimensions, Helix above), cut at the radius. Not `Default` with the radius: `prefersDQP`
  says yes for any radius in 6 or more dimensions (`dqpNeighbourThreshold` is infinite there), and at this radius every point
  is a neighbour of every other. Cech and Vietoris-Rips already defaulted to it (Cech: every subset has a smaller enclosing ball
  than the whole cloud, so for Cech the default cuts nothing).
- The persistence claim: R = min over x of max over y of d(x,y) >= radius of the minimal enclosing ball. Balls of radius R
  around all points contain the centre point, so their union is star-shaped, hence contractible; the alpha and Cech complexes at
  R have its shape, so every positive-degree bar is dead by R and H_0 has its one essential bar. Edges with alpha = R would
  need a Gabriel edge of length 2R through the centre point, impossible.

## Measurements (A/B in one JVM, alternating, uniform random points in the unit cube in R^3, seed 1)

| n | old scan | new, Euclidean | generic scan on an uncached wrapper |
|---|---|---|---|
| 5,000 | 0.8-1.4 s (first 2.9 s) | 1-2 ms (first 8) | not measured |
| 10,000 | 2.8-3.0 s (first 3.8-4.0) | 2-5 ms (first 12) | 8-31 ms |

Before the Euclidean override, the early-stop generic scan at 5,000 points (cached space) took ~300 ms, nearly all of it the
fill of the n^2 distance cache the scan triggers: that is why the override exists.

End to end, `Persistence(10,000 points, complex = AlphaShapes)` (default maxDimension 2), three trials after the radius
change: 4.5-4.9 s to the radius against 5.5-6.0 s with `maxFiltrationValue = Infinity`. **Unconfirmed**: three trials in one JVM,
not one engine per JVM; the direction was the same in both warm trials. The old code did the 2.8 s scan on top of the
unbounded run.

## Afterwards

- (Done after Mikael's answer on #215: change it and note it in the tutorial.) The MATLAB/CLI `complex=alpha` first stayed whole
  because the tutorial table counted 325 simplices on both tabs (`AlphaShapes(points)`), 313 once cut. It now runs
  `AlphaShapes.upToEnclosingRadius` for every engine but `fast-alpha`; the tutorial counts 313 on both tabs and says that
  `Infinity` gives the 325 (`MatlabTabsSpec` pins both).
- `Input.scale` stays an eager `Some(...)` per diagram: the scan is milliseconds now, so it is not worth a by-name parameter.
