# WORKLOG: NaN as the cubical mask (2026-10-12)

Decision (project lead, this session, settles #52): `NaN` is the mask of a cubical image, in either filtration
direction. `±Infinity` are left as ordinary extreme values ("always on or always off depending on direction and sign").

## Before

Every engine refused a NaN pixel (`GridRanks.nanPixel`, checked in `CubicalImage.fromFlatArray`,
`CubicalGridStream.topCellValues` and `GridRanks`). The mask was `+Infinity` in grid values, so `+Infinity` for a
sublevel image and `-Infinity` for a superlevel one (values negated on load). Perseus's `-1` was read as `+Infinity`,
which made a missing cube enter FIRST in a superlevel reading (`readCubicalToplex(path, sublevel = false)`): a latent bug
fixed by this change.

## What changed

- `CubicalGridStream.masked(values)`: NaN -> `+Infinity` (copy only when a NaN is present). Called by
  `CubicalGridStream.topCellValues` (grid from a function), `FlatCubicalGridStream.topCellValues` (direct
  construction), and `GridRanks.apply`. `CubicalImage.fromFlatArray` maps NaN to `+Infinity` in its own copy loop,
  AFTER deciding the sign, so the mask does not flip with `sublevel`.
- Mapping to `+Infinity` in grid units, rather than excluding cells from the complex, reuses the `+Infinity` path every
  engine already handles (the fast engine's `∞`-tie special case included). The masked pixel's faces take the minimum
  over the OTHER containing pixels, which is exactly the subcomplex without the masked cell, at every finite level.
- `GridRanks.nanPixel` removed (unreleased, never in a tag).
- Perseus: `-1` reads as NaN; NaN and `+Infinity` both write as `-1`.

## Where "enters at +Infinity" differs from "absent"

Only at the infinite level: a masked cell enters at `+Infinity`, so with `includeZeroLength = true` the masked region
shows `[∞, ∞)` bars, and an image that is entirely NaN reports one H0 bar born at `+Infinity` rather than an empty
diagram. Same as an all-`+Infinity` image did before. Not fixed here (it would also change what `+Infinity` does, which
the decision leaves alone).

## Known cost (from #52)

A NaN produced by a bug upstream (0/0) now silently masks pixels instead of failing.

## Tests

`FastRepresentativesSpec` ("A NaN pixel is masked..."): NaN diagrams equal `+Infinity` (sublevel) / `-Infinity`
(superlevel) diagrams bar for bar, representatives included, under fast, cohomology and chunks; the loop around a masked
pixel never dies in either direction; function-built grids under fast, packed and naive. `TDA4jSpec`: the ring with a
NaN centre under all four facade engines, both directions, plus the old Inf cases. `PerseusSpec`: `-1` as NaN, round
trip, superlevel ring.
