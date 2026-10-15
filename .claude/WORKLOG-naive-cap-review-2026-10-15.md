# Review of the capped naive engine, and the facade (2026-10-15)

Follows `WORKLOG-chunks-top-cells.md` Step 4. Workflow `wf_df913d27-ff8`: three reviewers (soundness, cursor and API, test
adequacy), two skeptics per candidate, 15 agents, none errored, about 23 minutes, on commit f610e031. Everything below is by
reading the code; the author then ran the mutations listed at the end.

## Result

| dimension | candidates | survived | refuted |
|---|---|---|---|
| soundness | 1 | 1 (2 of 2 skeptics could not refute) | 0 |
| cursor and API | 4 | 3 | 1 (`current` no longer tracks the last consumed cell: nothing reads it) |
| test adequacy | 1 | 1 (1 of 2) | 0 |

The reviewers could not construct a divergence from the uncapped cursor for a stream that meets the ordering contract: the
skip rule is exact there. All five findings are minor, and all were fixed:

1. **The skip turned off the contract check (reported twice).** `reduceCell` throws "reduction pivot ... was not a recorded
   open class" when a stream lists a cell before one of its faces (`rules/streams.md`: the usual symptom of an ordering
   bug). A skipped top cell never reached it: a hand-built non-monotone stream (vertices at 0, edges 01 and 12 at 1,
   triangle 012 at 2, edge 02 at 3, built with `ExplicitStreamBuilder.addOne`, which does not check) gave an essential class
   of degree 1 born at 3 where the uncapped cursor throws. Fix: `checkFacesSeen`, run for a skipped cell; every face must be
   an open class, a pivot or a negative cell, else the same exception (a face that cancels in the boundary is ignored). A
   second case the reviewers built, vertices 0, 1, 2 and a Simplex triangle whose three edges are not in the stream, is
   reachable (`ExplicitStream.iterator` lists its cells directly, whatever the gaps between dimensions) and is caught by the
   same check: a stream with no `k`-cell has no open class, so before the fix the triangle was skipped and three essential
   bars of degree 0 came back without an error.

   **"A triangle with no edges" is not malformed in itself** (project lead): it is one of the first examples of a simplicial
   set, the minimal 2-sphere `Delta^2 / boundary` (one vertex, one 2-simplex, all three edges degenerate). What the check
   looks at is the faces the cell's OWN `boundary` names: a `Simplex` always names its edges, so a missing edge is a
   missing cell; a simplicial-set generator names only its non-degenerate faces, here none. `NaiveCappedSpec` runs
   `SimplicialSet.sphere(2)` capped at 0, 1 and 2 (no error; the 2-simplex is not read at 0, is skipped at 1, is a class of
   degree 2 at 2) next to the edge-less `Simplex` triangle (an error, capped and not).
2. **"Not read" was wrong.** Every cell was enumerated, valued and sorted before the filter. Now filtered first and sorted
   after; the last cell of the whole stream comes from a maximum, not a sort. The scaladoc says "neither sorted nor reduced".
3. **`maxDimension = Int.MaxValue` dropped every cell** (`dim <= d + 1` overflows): an empty diagram where the uncapped engine
   and the old verb returned every bar. Now `dim - 1 <= d`.
4. **No example queried at a finite value**, so computing `lastCell` from the filtered sequence passed every example. Added a
   part-way query on a cursor that has not run to the end (three inputs, `k` in 0..1, the same two cursors queried in
   increasing order), and a filled triangle whose face enters at 5 while the cap is 0 (a class alive at 3 is cut off at 3,
   not essential).

## The facade

`PersistenceEngine.naive` and `naiveCocycles`, which MATLAB and the command line reach through `matlab.TDA4j.naiveFor`, now
cap at the stream's `homologyDegreeLimit` (when it is `Some(k)`, `k >= 0`). The facade hands them
`LimitedCofaceSimplexStream(.., requestedMaxDimension + 1)` or the equivalent, so the limit is the requested degree and the
facade's own `fromBars` filter is a no-op. A complete complex (`ExplicitStream`, cubical, simplicial sets) has no limit and is
read whole. Visible difference: no bars above the limit from these two methods (the cohomology engine through the same trait
already left them out). Specs: equal to the uncapped cursor up to the limit for cycles and cocycles over three inputs and `k`
in 0..2, none above it; a tetrahedron boundary (no limit) keeps its degree-2 class.

## Mutations, run (each fails at least one example of `NaiveCappedSpec`; the files were restored)

| mutation | examples failing |
|---|---|
| the skip condition replaced by `false` (always reduce) | 1 |
| ... by `true` (always skip) | 4 failures and 1 error |
| no `checkFacesSeen` | 1 |
| `lastCell` from the filtered sequence | 1 |
| the overflowing filter `dim <= d + 1` | 1 |
| `naiveState` ignoring the limit | 1 |

## Cost of the guard

Capped reduction, 70 points: 445 - 795 ms (R^3, noisy), 457 - 477 ms (R^2); 30 points at `maxDimension` 2: 432 - 439 ms.
Before the guard: 401 - 452, 382 - 424, 434 - 446 ms. The difference is inside the run-to-run spread.

## Not done

- The naive engine still orders cells by `stream.filtrationOrdering` (the rank trick of `WORKLOG-chunks-top-cells.md` Step 2).
- `Chain.reduceBy` builds a reduction log that `reduceTopCell` throws away.
- No MATLAB or command-line run: the facade is tested from Scala as always.
