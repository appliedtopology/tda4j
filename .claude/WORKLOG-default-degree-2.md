# Default homological degree 2 for `Persistence(...)`: cost probe (2026-10-03)

Point-in-time snapshot.

## The ask

Project lead: by default compute H0, H1 and H2, which means cells up to dimension 3. MATLAB and the CLI already
default to `maxDimension = 2` (with Ripser for Vietoris-Rips); the Scala verb defaulted to 1.

## What happened when the verb's default was flipped to 2

The tutorial specs (which run every page's code) went from about 90 s to out-of-memory: the 2 GB test JVM spent 99%
of its time in GC, and six tutorial specs never finished. The default was put back to 1 pending a decision.

## Cost probe

Setup:
- one JVM per run, `-Xmx2G`, 180 s cap;
- `Persistence(points, maxDimension, engine, complex)` on the tutorial data;
- "tets" is the number of 3-simplices of the Vietoris-Rips complex up to the minimum enclosing radius `r` (the
  default truncation).

| data | n | tets ≤ r | VR Ripser, deg 2 | VR chunks, deg 2 | Cech chunks, deg 1 | Cech chunks, deg 2 | alpha chunks, deg 1 / 2 |
|---|---|---|---|---|---|---|---|
| noisy-circle (R²) | 60 | 231,961 | 0.37 s | 112.8 s | 9.9 s | >180 s | 0.42 s / 0.42 s |
| figure-eight | 70 | 149,610 | 0.44 s | 45.9 s | | | |
| circle-with-outliers | 95 | 2,239,976 | 0.80 s | >180 s | | | |
| flat-torus (R⁴) | 120 | 6,523,416 | 1.59 s | OOM, then >180 s | >180 s | >180 s | 42.9 s / 47.5 s |

Readings:
- At degree 2, Vietoris-Rips is affordable only through Ripser (2-3 orders of magnitude faster).
- Cech has no Ripser path and is slow even at degree 1.
- Alpha costs the same at degree 2 as at degree 1 in the plane; in R⁴ it builds the full complex either way.

## Found along the way: truncated streams and the verb

`Persistence(VietorisRips(pts, maxDimension = 1), maxDimension = 2)` reported every unfilled triangle as an essential
H2 class. Fix: `StratifiedCellStream.homologyDegreeLimit`, set by every truncating wrapper. The verb defaults to it and
refuses a larger degree; its `maxDimension` is now `Optional[Int]`, so it can tell "not given" from "given". Complete
complexes (the octahedron from `fromFacets`) still report H2. Tests are in `PersistenceVerbSpec`.

## Open

Which default makes degree 2 affordable (put to the project lead):
- Ripser by default for Vietoris-Rips, giving cocycle representatives;
- or a hybrid: chunks with cycles in degrees 0-1, plus Ripser for degree 2;
- and what Cech should default to.

## Decision and second probe (same day)

Project lead: default to Ripser and report cocycles, with the docs clear about the choice. Implemented:
- `Engine.Auto` (the default): Ripser for Vietoris-Rips on points or a metric space, `Cohomology` otherwise;
- `DefaultMaxDimension = 2`.

Where the time goes (noisy-circle, 60 points, Vietoris-Rips, degree 2: 257k cells in all, 231,961 of them
tetrahedra):

| step | time |
|---|---|
| enumerating every cell | 3.7-5.5 s |
| chunks | 112.8 s |
| naive | 115.7 s |
| `CellularCohomologyEngine` | 23.6 s |
| Ripser | 0.37 s |

- The cost is the reduction, not the construction.
- Homology must reduce one column per tetrahedron, and nearly all of them reduce to zero. Cohomology clears them.
- Chunks with clearing is no faster than naive here.

Other complexes, chunks against cohomology (both build the complex themselves):

| input | chunks | cohomology |
|---|---|---|
| Cech, 60 points, degree 2 (34,220 triangles, 487,635 tetrahedra; construction 16.2 s) | >180 s | 58.1 s |
| alpha, noisy-circle, degree 2 | 0.47 s | 0.44 s |
| alpha, flat-torus (R⁴), degree 2 | 51.0 s | 42.8 s |
| 200x200 synthetic image | 34.9 s | 4.6 s |

Cohomology is never slower, which is why `Auto` uses it everywhere Ripser does not apply.

Doc changes:
- find-a-loop, quickstart, the landing page, the Vietoris-Rips and Cech pages and the engine guide explain cocycles
  (the default) against cycles (`Engine.Chunks` with `maxDimension = 1`);
- noise-and-outliers asks for cycles explicitly (it needs to know which points are on each loop);
- scaling-up gained a "Degree 2" section.

Found while re-deriving the numbers: the quickstart's `bettiNumbers // Vector(1, 1)` was already wrong (it was
`Vector(1, 0)`: the loop is filled in before the enclosing radius); it is now `Vector(1, 0, 0)`.

## Profiling candidates (not started)

- `CellularCohomologyEngine`: 64x behind Ripser on the same VR input, and the only route for Cech, alpha, witness,
  Dowker and images.
- Chunks on images: 35 s for 200x200 against 4.6 s for cohomology; something there is not clearing.
- Cech construction: 16 s for 520k cells, one Miniball per simplex.
