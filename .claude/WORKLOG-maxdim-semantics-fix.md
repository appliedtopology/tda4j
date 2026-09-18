# WORKLOG: `maxDimension`/`maxDim` semantics fix (top homological degree, not top simplex dimension)

Date: 2026-09-17. Point-in-time record — not retroactively edited (see [[tda4j-worklog-convention]]).

## Ask

Following the same-hardware Ripser comparison (`WORKLOG-ripser-comparison.md`), which found that
`RipserCohomologyContext`'s `maxDimension` constructor parameter meant "top simplex dimension built" rather
than "top homological degree reported" (already independently worked around at the MATLAB facade layer before
this session), the project lead asked to make this correct *everywhere*, not just at the one facade that had
patched around it.

Plan approved before implementation: `.claude/CLAUDE.md`'s edit history and `[[tda4j-ripser-paper-benchmark]]`
memory entry cover the discovery; this worklog covers the fix itself.

## Scope: exactly two classes had this ambiguity

An inventory pass (an Explore agent, grepping every constructor call site across `src/main` and `src/test`)
confirmed only two classes in `Homology.scala` have a `maxDim`/`maxDimension` constructor parameter with this
exact ambiguity — every other engine (`SimplicialHomologyContext`, `CellularHomologyContext`,
`SimplicialHomologyByDimensionContext`) takes no such parameter at all; the dimension cap for those lives
entirely in whatever stream the caller hands them:

1. **`RipserCohomologyContext`** (cohomology) — `coboundaryOf`/`zeroPivotCofacet` refused to look past
   `sigma.dim + 1 > maxDimension`, so `sigma.dim == maxDimension` always got a trivially-empty coboundary and
   came out essential by construction.
2. **`PersistenceInChunksContext`** (homology) — `allCells` and both `advanceAll` loops walked `0.to(maxDim)`,
   so no `(maxDim + 1)`-cell was ever considered that could kill a class born at `dim == maxDim`.

## The fix

**`RipserCohomologyContext`** (`Homology.scala`): two guard changes, nothing else.
- `coboundaryOf`: `sigma.dim + 1 > maxDimension` → `sigma.dim > maxDimension`.
- `zeroPivotCofacet`: same change, so the apparent-pairs shortcut also sees real tied cofacets at the top
  dimension.
- `sparseCofacets`'s own guard and the main loop's bounds (`for d <- 0 to maxDimension`, and
  `if d < maxDimension then currentLevel = ...`) were **deliberately left unchanged** — confirmed by tracing
  through exactly what each consumes: `sparseCofacets` only controls what gets materialized into
  `currentLevel` for the *next* loop iteration, and the loop never runs a `d == maxDimension + 1` iteration, so
  nothing above `maxDimension` needs its own `currentLevel` entry. The real `(maxDimension + 1)`-simplices
  needed to correctly resolve a dimension-`maxDimension` pairing are enumerated transiently inside
  `coboundaryOf`/`zeroPivotCofacet` per call (via `si.cofacetIterator`, using `SimplexIndexing`'s own
  unrestricted combinatorial-number-system iterators) and never separately reduced as their own column. This
  means `totalSimplexCount`'s value is genuinely unaffected by this fix — confirmed empirically afterward (see
  "Verification" below), not just reasoned through.

**`PersistenceInChunksContext`** (`Homology.scala`): the mirror-image fact for homology (a class born at
dimension `d` can only be killed by a `(d+1)`-dimensional cell's own boundary reducing to it) needed a
different mechanism, since this class doesn't build its own complex — it consumes whatever `StratifiedCellStream`
the caller hands it. Added `private val internalMaxDim: Int = maxDim + 1` and used it (instead of `maxDim`) in
three places: `allCells`'s construction (`0.to(internalMaxDim)`) and both loops inside `advanceAll` (local
reduction and global compress/reduce, both walked `internalMaxDim.to(0, -1)` instead of `maxDim.to(0, -1)`).
`diagramAt`'s essential-bar list is then filtered to `sigma.dim <= maxDim` before being returned — finite bars
need **no** equivalent filter, since `recordPair`'s `barDim = pivot.dim` and a pivot is always exactly one
dimension below its killer, so `barDim <= maxDim` automatically whenever the killer is at most
`maxDim + 1`-dimensional.

Both fixes follow the same design principle: extend what's *considered* internally by exactly one dimension,
never extend what's *reported*, and do the reporting-side filter at the one place bars actually leave the class
(not scattered through the reduction logic).

## A real edge case caught along the way, fixed at its actual source

Extending `RipserCohomologySpec.scala`'s own `naiveBars` test helper to build one dimension higher
(`LimitedCofaceSimplexStream(stream, maxDim + 1)`, matching the engine's own new semantics so the two sides of
the comparison stay apples-to-apples) crashed with `scala.MatchError: 3` on a 3-point calibration fixture.
Root cause, in `LimitedCofaceSimplexStream` (`SimplexStream.scala`) — a genuine, general latent bug, not
specific to this session's change: its own `iterateDimension` guard was `case d: Int if d >= 0 && d <= maxDim
=> stream.iterateDimension(d)`, which calls the WRAPPED stream directly rather than through `applyOrElse` —
correct only as long as `maxDim` never exceeds what the wrapped stream can itself provide
(`EnumeratingCofaceSimplexStream`'s own bound is `d < metricSpace.size`, since a d-simplex needs `d + 1`
distinct vertices). For a 3-point cloud requested at `maxDim = 3` (i.e. `naiveBars`'s own `maxDim + 1` for an
underlying test at `maxDim = 2`), the wrapped stream has no case matching `d = 3` at all, and calling `.apply`
directly (not `.applyOrElse`) threw a raw `MatchError` instead of correctly reporting "undefined here." Fixed
by adding `&& stream.iterateDimension.isDefinedAt(d)` to the guard. This had simply never been exercised before
— every existing caller happened to choose `maxDim` values comfortably within the wrapped stream's own natural
bound.

## Test suite changes, beyond the two production classes

- `HomologyFixtures.totalBarsAccountForAllCells` gained an optional `topDimension: Int = Int.MaxValue`
  parameter: a finite bar born exactly at the reporting boundary now consumes only 1 cell for this invariant's
  purposes (same as essential), not 2, since its death cell was never itself one of the counted
  `totalSimplexCount`/`cells` entries. `RipserCohomologySpec.scala`'s two structural-invariant tests pass
  `topDimension = maxDim`; every other existing caller (`HomologySpec`, `SimplicialHomologyByDimensionSpec`,
  `AlphaComplexSpec`) is unaffected by the default.
- `RipserCohomologySpec.scala`'s `naiveBars` helper now builds to `maxDim + 1` and filters its own output to
  `dim <= maxDim`, mirroring the MATLAB facade's already-established pattern — needed because `cohomologyBars`
  (calling the now-fixed engine directly) and `naiveBars` (still going through
  `LimitedCofaceSimplexStream(stream, maxDim)`, unaffected by this session's fix) would otherwise silently stop
  agreeing at `dim == maxDim`, for reasons having nothing to do with a reduction bug.
- One hand-verified fixture's *expected value* changed, correctly: `threePointLine` (3 colinear points at
  0/1/3) at requested `maxDimension = 1`. Before the fix, this genuinely could not build the triangle
  `{0,1,2}`, so the 3-cycle stayed essential (`(1, 3.0, Infinity)`). Under the fixed semantics, correctly
  resolving H^1 at *any* requested degree needs the real dimension-2 coboundary — and for exactly 3 points, the
  triangle unavoidably exists (born at the same value, 3.0, as its own longest edge {0,2}) the instant all three
  edges do. There is no threshold or `maxDimension` choice that gives this fixture edges but not the triangle;
  the mathematically correct answer is a genuine zero-persistence bar, `(1, 3.0, 3.0)` — exactly what real
  `ripser --dim 1` would also report on this same cloud, since Ripser always builds one dimension higher
  internally too. Retitled and re-commented rather than silently changed, so a future reader sees *why* the
  expected value moved. (`PersistenceInChunksSpec.scala`'s own same-named test was **not** affected: it feeds
  `PersistenceInChunksContext` a `LimitedCofaceSimplexStream(..., 1)`-wrapped stream that itself has no
  dimension-2 cells at all — a genuinely different scenario, since that class consumes an externally-built
  stream rather than enumerating its own complex from a metric space, so a caller CAN construct a truly
  triangle-free complex for it, unlike for `RipserCohomologyContext`.)
- `matlab/Tda4jSpec.scala`'s "top-dimension truncation-artifact fix" test asserted that a direct
  `RipserCohomologyContext(ms, 2)` call disagreed with the facade's (then-corrected) output — the artifact's
  own discriminating regression. Once the engine itself absorbed the fix, the facade's `engine="ripser"` path
  became a byte-for-byte passthrough of `RipserCohomologyContext(ms, requestedMaxDimension)`, so this premise
  is now false by construction. Rewritten as a regression pin for the fix itself: `RipserCohomologyContext(ms,
  2)` must agree with `RipserCohomologyContext(ms, 3)` filtered to `dim <= 2` — the exact discriminating check
  that originally caught the bug, now asserting it stays fixed rather than demonstrating it was broken.

## Call sites updated to drop now-redundant `+1`-and-filter workarounds

`matlab/Tda4j.scala` (`engine="ripser"` and `engine="chunks"` cases — `engine="naive"` still needs its own
workaround, since `SimplicialHomologyContext` has no `maxDimension` of its own), `RipserPaperBenchmarkSpec.scala`,
`DimensionCeilingBenchmarkSpec.scala`'s `ripserAttempt` (its `vrEnumNaiveAttempt` sibling keeps the `+1`, for
the same `SimplicialHomologyContext` reason). Each now passes the real requested degree directly; the
`fromBars`/`fromDiagram` post-filters in the facade are harmless no-ops for these two engines now, left in
place rather than removed (defensive, and consistent with how `RipserPaperBenchmarkSpec` was written before
this fix landed).

## Verification

- `RipserCohomologySpec`: 17/17, including the `totalBarsAccountForAllCells` invariant at both the
  unthresholded and thresholded property tests (200 random trials each), and the "essential representatives are
  genuine cocycles" check now exercised at `dim == maxDim` too (previously excluded as vacuous).
- `matlab.Tda4jSpec`: 12/12, including the rewritten regression pin.
- `PersistenceInChunksSpec`: 7/7, including its own "3-cycle graph, no filled triangle" test — confirmed by
  hand that this one is NOT the same edge case as `RipserCohomologyContext`'s (see above), so its unchanged
  expected value (`(1, 3.0, Infinity)`, essential) is still correct.
- `HomologySpec`, `VietorisRipsSpec`, `AlphaComplexSpec`, `DimensionCeilingBenchmarkSpec`: all clean, no changes
  needed beyond `DimensionCeilingBenchmarkSpec`'s own `ripserAttempt`/doc updates.
- `RipserPaperBenchmarkSpec` (temporarily un-skipped, short timeout): sphere3 ladder's smallest case
  (n=48) still matches real `ripser.cpp`'s own per-dimension bar counts exactly after removing the `+1`
  workaround, and timing stayed in the same range as before the fix (no regression, modulo ordinary
  single-trial run-to-run noise — this spec doesn't do median-of-several).
- Full `sbt test`: **165 examples, 0 failures, 0 errors, 160 passed, 5 skipped, 1 pending** — run three times
  across this arc (once per production-class fix plus once final), identical every time.

## What's still open

- The packed-`(Double, Long)` parallel engine (Phase 2 of the approved plan) — a separate, larger effort,
  deliberately sequenced after this fix so it can implement the correct semantics from the start rather than
  copying the bug into a new engine. Not started in this worklog's arc.
- `matlab/Tda4jSpec.scala`'s "explicitly refuse... for engine=chunks" and "reject engine=chunks combined with
  complex=alpha" tests were re-run but not specifically re-derived against the `PersistenceInChunksContext` fix
  — they test option-parsing/error-handling behavior unrelated to `maxDim` semantics, so no change was expected
  or needed there, but it's worth naming explicitly that "chunks" doesn't yet have a dedicated
  facade-level agreement test the way "naive" does (`"engine=naive, through the facade" should "agree with the
  default engine=ripser"`) — a reasonable follow-up test to add, not attempted here since it wasn't part of
  what broke or what was asked.
