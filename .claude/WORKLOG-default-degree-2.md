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
