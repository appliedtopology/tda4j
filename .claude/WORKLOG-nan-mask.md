# WORKLOG: NaN as the cubical mask (2026-10-12)

Decision (project lead, this session, settles #52): `NaN` is the mask of a cubical image. The point is that NaN has
no sign and compares to nothing, so it is a flag that means the same in a sublevel and a superlevel image and is
outside every order: a masked cell is NOT IN THE COMPLEX. `±Infinity` stay ordinary values ("always on or always off
depending on direction and sign").

## Before

Every engine refused a NaN pixel (`GridRanks.nanPixel`, checked in `CubicalImage.fromFlatArray`,
`CubicalGridStream.topCellValues` and `GridRanks`). The mask was `+Infinity` in grid values: `+Infinity` for a sublevel
image, `-Infinity` for a superlevel one (values negated on load). Perseus's `-1` was read as `+Infinity`, so a missing
cube ENTERED FIRST in a superlevel reading (`readCubicalToplex(path, sublevel = false)`): a latent bug.

## First attempt (commit 759d059, rejected by the project lead)

NaN was rewritten to `+Infinity` in grid values at every entry point (`CubicalGridStream.masked`). Direction-free, and
it reused the `+Infinity` path, but it conflated the mask with a value: masked cells still entered at `+Infinity`, so
with `includeZeroLength = true` masked regions showed `[∞, ∞)` bars, the complex at the end was the whole grid, and an
all-NaN image gave an H0 bar born at `+Infinity`. Not what a mask should mean.

## What is implemented

- `CubicalGridStream`: values stay NaN. `cellValues` takes the minimum over the UNMASKED top cells containing a cube
  (NaN-skipping, NaN padding at the grid edge), so a cube is NaN exactly when every top cell containing it is masked;
  `filtrationValue.isDefinedAt` and `iterateDimension` leave those out. The complex is the closure of the unmasked
  top cells, so faces of present cells are present and the generic engines (naive, chunks, `CellularCohomologyEngine`,
  the involution's `coboundariesOf`) need no change: they only ever see the stream's cells.
- `GridRanks`: NaN sorts after `+Infinity` under the sortable-key radix sort (`doubleToLongBits` canonicalizes NaN);
  masked pixels get rank `masked = distinct.length`, one past every real rank, and are not in `distinct`. A minimum
  over ranks then skips them, and a cell's rank is `masked` iff all its pixels are masked.
- `PackedCubicalCohomologyEngine`: `level(k)` and `foreachCofacet` skip rank-`masked` cells (facets of a present cell
  are always present).
- `FastCubicalHomologyEngine`, H0 (2-D): masked edges skipped; one essential bar per surviving component (none when
  everything is masked), in the stream's vertex order (rank ascending, vertex number descending, stable sort), as the
  eager reference lists them.
- `FastCubicalHomologyEngine`, dual (top degree): a masked pixel is in the complement from the start, like `∞`; so is
  a facet of rank `masked` (between two masked pixels, or a masked pixel and the outside), and the descending sweep
  takes those first, merging with no bar. Elder order (`seniority`): `∞`, then a component holding a masked pixel
  (whose root is a masked pixel, since roots are eldest members), then by birth; two masked components tie
  explicitly (the facet's lower side dies) rather than through `NaN <= NaN`. A masked component dying at a facet of
  value `v` is an essential bar `[v, ∞)`, never dropped as zero-length (`v` may be `+Infinity`: a ring of `+Infinity`
  pixels around a mask is a class born at `+Infinity`).
- `EagerFastCubicalReference`: the same rules in its own event-sorted shape (NaN events sort first under
  `java.lang.Double.compare` descending).
- Perseus: `-1` reads as NaN; the writer writes NaN as `-1` and refuses infinite values (the format has none; `-1`
  would come back as a mask). `GridRanks.nanPixel` removed (never in a release).

## Tests and mutation checks

- Masked images (2-D ties with NaN and `+Infinity` mixed, 3-D, degenerate shapes) added to the random generators of
  `FastRepresentativesSpec` and `PackedCubicalCohomologySpec`: every existing equality check (fast vs eager
  reference term for term, packed vs `CellularCohomologyEngine` bar/cocycle/cycle term for term, packed and ascending
  representatives, closed representatives over the reals) now covers masks.
- `FastRepresentativesSpec` "A NaN pixel is not in the complex...": all four engines agree, sublevel, superlevel and
  with zero-length bars; the loop around a masked pixel never dies in either direction; nothing is born at `+Infinity`
  even with zero-length bars, while the `+Infinity` image has such bars; finite bars equal the `+Infinity` image's;
  94 of 99 cells (counted by hand); ordering contract; all-NaN image has no bars under any engine.
- Mutation: dropping the masked seniority fails 3 examples (reference equality among them); not skipping masked
  cofacets in the packed engine fails 4 examples with `distinct(masked)` out of bounds (the intended loud failure).

## Addendum: masks off (`nanMask = false`)

Project lead, same session, after re-reading #52: not an option to ENABLE masks (#52's third choice), but one to
DISABLE them, under which a NaN is a loud, explained failure. Default `true` (masks on).

- `CubicalGridStream(..., nanMask = false)`: `topCellValues` calls `CubicalGridStream.requireNoNaN` after reading
  (so a function-built grid fails when an engine first reads it); `FlatCubicalGridStream` checks in its constructor
  (an array built by `fromFlatArray` fails at construction). One message: the pixel, that masks are off (naming the
  option in both its Scala/MATLAB and CLI spellings), and what to do.
- Threaded through `Image` (verb), `CubicalImage.fromFlatArray`/`fromGrayscale2D`/`fromVoxelGrid3D`,
  `Perseus.readCubicalToplex`, `Dipha.readCubicalGridStream`, MATLAB option `nanMask` (allowlisted), CLI `--nan-mask`.
  `fromBufferedImage`/`fromFile` have none: luma is never NaN.
- `nanMask` is the LAST parameter everywhere: several signatures already end in a `Boolean`
  (`parallelFiltrationValue`), and a new Boolean before it would silently rebind positional calls.
- Specs: `FastRepresentativesSpec` (every engine, both construction routes, superlevel, no change without NaN),
  `TDA4jSpec` (four engines, bad value), `PerseusSpec`, `CLISpec`. Mutation: dropping the function-built check fails
  the engine spec.

## Known cost (from #52)

A NaN produced by a bug upstream (0/0) silently masks pixels instead of failing, unless the caller turns masks off
(addendum above).

## Environment notes (this session)

Fresh cloud session: the SessionStart hook worked (`core.hooksPath`, native scalafmt in `~/.cache/tda4j/`). The sbt
cache was cold and the environment setup script does not run `install-sbt.sh`: seven paced attempts (~1 h of 429s)
before `sbt compile` succeeded. `install-sbt.sh` warms `compile` only: `sbt scalafmtAll` then still failed (the sbt
plugin downloads scalafmt-dynamic on first use); the native binary formatted instead. sbt 2's launcher rejects
`-batch`/`--batch`.
