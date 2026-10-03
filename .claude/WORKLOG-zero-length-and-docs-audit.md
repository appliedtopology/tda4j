# Zero-length bars off by default; documentation audit (2026-10-03)

Point-in-time snapshot. Rules that came out of this are in `CLAUDE.md` ("Zero-length bars", "Docs carry the
contract") and `rules/facade.md`, `rules/engines.md`, `rules/streams.md`, `rules/docs-and-tutorials.md`.

## The ask (project lead)

1. Drop all zero-length bars **by default**, everywhere. Seeing them is the opt-in. Filtering short bars must be
   trivially easy. Bar-vs-simplex counting had crept into tests and the API as a validation device; that is not
   idiomatic TDA.
2. A documentation audit (scaladoc, tutorials, user guide) toward the current design and Li Haoyi's "easy". Strip
   implementation history and "Claude-isms". Leave the Developer's Guide alone except for factual errors: a student
   is editing it.
3. The new-style context bounds stay.

## What changed

- `includeZeroLength: Boolean = false` on every bar-returning engine method, `Persistence(...)`, and the facade
  (`includeZeroLength` option, CLI `--include-zero-length true`). `PersistenceBar.dropZeroLength` is the shared filter.
- The filter acts on the **true pairing** (`lower != upper`), never after truncation:
  - the naive and chunks engines' `diagramAt(f)` caps a class alive at `f` as `[birth, f]` (ClosedEndpoint);
  - `PersistenceDiagram.at(f)` does the same;
  - otherwise a class born exactly at `f` would vanish from the snapshot at `f`.
- Fast cubical and fast alpha drop equal-value pairs when they are built. A pair at `(+Inf, +Inf)` cannot be told
  apart from an essential class after the fact.
- Short bars: `diagram.longerThan(x)` and `significant()`, plus the same two as extensions on
  `List[PersistenceBar[Double, A]]` in the `PersistenceBar` companion, so no import is needed.
- `Persistence.Engine` is now `Chunks | Naive | Cohomology | Ripser`. Ripser requires `VietorisRips` on points or a
  metric space, and decodes `DiameterIndex` back to simplices.
- `ExplicitStream` is now a `StratifiedCellStream`, so `Persistence(ExplicitStream.fromFacets(...))` works.

## Findings along the way

- **Near-zero bars that should have been exactly zero.** Once zero-length bars disappeared, two constructions were
  left with bars of length ~1e-16 to 1e-9, which now showed up as noise instead of being filtered:
  - **Cech**: Miniball returned radii a few ULPs above the facet radius when the added vertex lies strictly inside
    the facet's ball. Fix: in that case, reuse the facet's cached radius. `Miniball.support()` throws "Not
    implemented yet", so the test uses the ball's center.
  - **DQP alpha**: values agreeing with the largest facet value to within 1e-10 (relative) are snapped to it
    (`clampMonotone`).
  - `ZeroLengthBarsSpec` pins both.
- **Bar counting as an oracle.** About 19 specs used `#bars == #cells` (or something similar) to validate streams.
  Each was moved to one of:
  - the ordering contract: `HomologyFixtures.respectsOrderingContract`, which checks that faces are present, values
    are monotone, and buckets are sorted for dimension >= 1 (vertices are listed by index, so dimension 0 is exempt);
  - the actual barcode of a hand fixture;
  - an explicit `includeZeroLength = true`, where the pairing invariant is the point.
- **Dowker duality** now holds for the default barcodes, with no extra filtering step.

## Documentation

- Tutorials now use the plain package import plus the `Persistence` verb. They use a lab only where hand-written
  chain algebra is the point (`telling-spaces-apart`, `all-ways-to-call`).
- User guide rewritten, with new pages `vietoris-rips.md` and `witness-complexes.md`.
- Developer's Guide: only factual fixes, in `degeneracies.md` and `architecture.md`.
- Scaladoc across `src/main`: contracts only. Derivations, measurements and "used to" now live only in worklogs.
  `//` implementation comments that point to worklogs were left in place; they are for maintainers.
- User-facing strings (facade error messages, CLI `--help`) named `CLAUDE.md`, `.claude/...` files and private
  classes' docs. They now say what to do instead. One factual error fixed: the `CircularCoordinates` doc called F₂ the
  library default.

## Open (flagged to the project lead, not decided)

- The default `maxDimension` is inconsistent:
  - complexes (`VietorisRips` etc.): 2;
  - the verb: 1;
  - MATLAB/CLI: 2.
- Whether the engine specs should keep the opt-in pairing-invariant checks (`totalBarsAccountForAllCells` with
  `includeZeroLength = true`). They are a cheap cross-check, but they are exactly the habit the project lead wants
  out of the default path.
