# WORKLOG: extending FastCubicalHomologyContext to d >= 3 via a hybrid with chunks

Session: 2026-09-25, same session as `WORKLOG-alpha-dual-unionfind-matlab-wiring.md`. Follow-on to the project
lead's own mid-session correction: the fast dual-union-find engines' `d=2`-only limit was NOT "the technique
stops working above d=2" (my own wrong framing going in) -- it's "there are no middle dimensions to worry about
at d=2, and there are at d>=3," a genuinely different, much narrower gap. Design captured in
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`; this entry is the cubical implementation + validation
that design predicted. Alpha's own port is explicitly NOT part of this entry -- sequenced after, per the design
note's own "Sequencing" section (mirrors item 6 before item 7 originally).

## What changed

- `streams/CubicalStream.scala`: new `LimitedCubicalGridStream(stream, maxDim)` -- the `Cube` analogue of
  `LimitedCofaceSimplexStream`, needed because that class is hardcoded to `CofaceSimplexStream[Int, Double]`
  (`Simplex[Int]`) and doesn't fit `Cube`. Hides every cell of dimension `> maxDim`; delegates
  `filtrationOrdering`/`filtrationValue` to the wrapped stream unchanged.
- `homology/FastCubicalHomology.scala`: `persistentHomology` now branches on `stream.ambientDim`. `d=2` is
  BYTE-FOR-BYTE the old code path (`computeH0 ++ computeDualTopDimension`), zero new overhead. `d >= 3` adds
  `computeMiddleDimensions`, which runs `CellularPersistenceInChunksContext[Cube, C](maxDim = d - 2)` on
  `LimitedCubicalGridStream(stream, d - 1)` and takes its `barcodeAt(+Infinity)` directly (covers `H_0` through
  `H_{d-2}` -- `H_0` comes along for free from `chunks`'s own `unionFindDim01`, no separate call needed), then
  appends `computeDualTopDimension(stream)` (unchanged, called on the REAL untruncated stream) for `H_{d-1}`.
  `require(ambientDim >= 2, ...)` replaces the old `== 2` check -- see the design note for why there's no
  principled place to draw a new, smaller ceiling (chunks is already fully general over `d`).
- `matlab/TDA4j.scala`: `dispatchCubical`'s own `FastCubical` gate relaxed from `!= 2` (rejected) to `< 2`
  (rejected) -- a degenerate 1-axis "image" is the only remaining case FastCubicalHomologyContext can't handle
  at all. Doc comments (the `computeFromCubicalImage` entry-point doc, the `EngineKind.FastCubical` branch's own
  inline comment) updated to match; neither needed a CODE change beyond the gate itself, since `maxDimension`
  was already being passed through to `fromBars` unconditionally (it just never did real filtering work before,
  since a 2D grid's own natural top dimension was always `<= 1`).
- `cli/TDA4jConf.scala`: `--engine`'s own description string updated to match (dropped "only when the image is
  2-dimensional").
- Docs: `CLAUDE.md` (Cubical complexes section, MATLAB API section), `persistence-engines.md` (engine 6's own
  section, the streams-vs-engines table's footnote and "Which engine" table row), `architecture.md`,
  `class-diagrams.md`, `user-guide/README.md` (the "faster engine for cubical images" subsection, options
  table, engine-rejection prose, "Which persistence engine?" table) -- all had a "2D/exactly 2/3D needs
  naive-chunks-cohomology" framing to correct. `alpha-complex.md` got one cross-reference update noting engine 6
  is now ahead of engine 7 on this axis, not a claim about alpha itself (still `d=2`-only, unchanged).

## Why this needed almost no NEW algorithm code

Before writing anything, I re-read `FastCubicalHomologyContext`'s actual source rather than trusting its own
doc comment's framing. Both `computeH0` and `computeDualTopDimension` were ALREADY written generically in terms
of `stream.ambientDim` (`bars += new PersistenceBar(ambientDim - 1, ...)`, the facet-enumeration loop over
`(0 until ambientDim)`) -- neither hardcodes `2` anywhere. The ONLY thing gating either engine to `d=2` was the
single `require` at the top of `persistentHomology`. So the actual job was narrower than "generalize the dual
union-find to arbitrary d" (already done, just gated off) -- it was "add a THIRD piece for the dimensions the
two union-finds were never going to cover, and combine."

The middle-dimension piece turned out to reuse existing, already-validated machinery almost entirely:
`CellularPersistenceInChunksContext`'s own `maxDim` semantics ("walk `0..maxDim+1`, report `<= maxDim`") is
EXACTLY the truncation contract needed once the stream itself is limited to `0..d-1` -- no new "discard the
incomplete top bar" logic had to be written, because chunks already does that discarding as part of its normal
operation (the same mechanism `naive`/`cohomology` already lean on for their own `maxDim`). And chunks already
resolves `H_0` via raw union-find internally (`unionFindDim01`) before any general reduction runs, so invoking
it for the middle dimensions yields `H_0` as a free side effect -- no separate primal-union-find call needed on
the `d >= 3` path at all (unlike `d=2`, where `computeH0` remains the ONLY dimension-0 computation, since
invoking chunks there at all would be pure overhead on an empty middle-dimension range).

## The one thing that needed real reasoning, not just code: does the dual union-find still give the correct
## H_{d-1} barcode when middle dimensions are resolved by a totally separate computation?

Worth recording since it's the one place this design could have been subtly wrong. In the standard single-pass
algorithm, a `(d-1)`-cell already claimed as "negative" by its OWN `d_{d-1}` reduction (paired to a
`(d-2)`-cell) can never ALSO become a pivot for a `d`-cell -- "a paired cell must never become a pivot," this
codebase's own established chunks invariant. The dual union-find, as coded, treats every facet as a candidate
dual-graph edge with no reference to whether some OTHER, separately-run computation would have called it
"negative." That looked, on first read, like a possible double-use.

It isn't a problem, because the dual-graph technique isn't a re-derivation of the standard algorithm's own
bookkeeping -- it's an independent computation of the SAME invariant (persistent `H_{d-1}`) via Alexander
duality, a statement about the actual topology of `X` at each filtration value, not about which specific cell a
particular reduction algorithm happens to call negative. Two valid algorithms for the same persistence problem
can disagree completely about internal bookkeeping while agreeing on the resulting barcode (this codebase's own
docs already say as much about ties: "which tied cell dies at a tied time is order-dependent"). The already-
validated `d=2` case was already relying on exactly this: `computeH0`'s own dimension-0 union-find and
`computeDualTopDimension`'s own dimension-1 dual union-find already disagree, in general, with what a single
monolithic reduction would call "negative," and cross-validation against the naive engine already confirmed
exact barcode agreement regardless, on thousands of fixtures. This session's own validation (below) is the same
check one dimension further up, not a new kind of check.

## Validation

Extended `FastCubicalHomologySpec` (not a new file -- same spec, same cross-validation-against-naive
discipline the 2D fixtures already established):

- Two hand-derived 3D fixtures, direct analogues of the existing 2D ones: a single elevated interior voxel in a
  3x3x3 grid (a solid ball with a cubical cavity once the elevated voxel's threshold is crossed -- homotopy
  equivalent to `S^2`, so exactly one PERSISTENT (nonzero-persistence) `H_2` bar and zero persistent `H_1`
  bars) and two independent such voxels in a 5x5x5 grid (two persistent `H_2` bars). First draft of the
  single-voxel test asserted `bars.count(_._1 == 2) == 1` (total dim-2 bar count) and failed immediately (27,
  not 1) -- the mistake was conflating "total bars at dimension 2" with "PERSISTENT bars at dimension 2": most
  are zero-persistence (a 2-cell immediately killed by its own 3-cell coface at the same value), exactly the
  SAME pattern the existing 2D "single bright center pixel" fixture already has (9 total `H_1` bars, 8 of them
  zero-persistence) -- fixed by matching that existing fixture's own assertion style (count nonzero-persistence
  bars specifically, check the one real bar exists by exact value, then full naive agreement) rather than the
  wrong total-count shortcut.
- Genuine-cycle representative checks extended to `bars.filter(_.dim > 0)` (both `H_1`, from chunks, and `H_2`,
  from the dual union-find) on the 3D fixtures -- the 2D spec only ever had one dimension above 0 to check.
- Fp(3) sign-genericity re-run on the 3D fixtures.
- A random tie-heavy 3D property test (`TestImage3D`, axes up to 3 per side to bound `chunks`'s own cost, level
  4 = `+Infinity` sentinel exactly like the 2D generator), cross-validated against the naive engine on both
  bar values and representative cycles -- this is the test that actually exercises the hybrid path against
  fresh, non-hand-picked ties, not just the two derived fixtures.
- One minimal `d=4` smoke test (a tiny, entirely flat `2x2x2x2` grid) -- confirms the code path doesn't reject
  or crash at `d=4` and agrees with naive, but is explicitly NOT a claim of validated correctness at `d >= 4`
  the way the `d=3` fixtures are (no interesting topology, just confirms nothing d=2/d=3-specific was left
  baked in). The design note's own "no artificial ceiling" conclusion is a prediction backed by the argument
  above, not by exhaustive testing at every `d` -- this smoke test is the one piece of DIRECT evidence for it
  beyond `d=3`, and the docs/CLAUDE.md say so explicitly rather than overclaiming.
- Renamed the old "requires ambient dimension 2" test to "requires ambient dimension at least 2" (it already
  used a 1D fixture, so the assertion itself needed no change, just the now-inaccurate name).

All of `FastCubicalHomologySpec` (14 examples), the full suite (`sbt clean test`), `scalafmtCheck`/
`scalafmtSbtCheck`, and `laikaSite` were re-run green after these changes -- same validation discipline as the
matlab-wiring entry above, not narrated twice here.

## What is explicitly NOT done in this entry

- **Cost/benefit A/B measurement.** The design predicts a real, shrinking-with-`d` win (this codebase's own
  performance-claim discipline: "isolated A/B measurement... report unconfirmed effects as unconfirmed") --
  not measured yet. Should be a `CubicalBenchmarkSpec`-style addition before this is presented as a performance
  win rather than just a correctness-preserving capability extension.
- **`FastAlphaHomologyContext`'s own `d >= 3` port.** Deliberately sequenced after this entry, not concurrent
  with it (design note's own "Sequencing" section) -- alpha's dual-graph code is already exactly as
  dimension-generic as cubical's was before this session (same pattern, confirmed by reading it), so the PORT
  itself should be comparably small, but alpha ALSO carries its own, separate, likely-dimension-dependent risk
  (the facet-multiplicity precondition, `FastAlphaTriangulationException`) that needs its own fresh measurement
  at `d=3` before it can ship -- not something to assume carries over from the `d=2` rate.
- **Validation at `d >= 5`.** Only `d=3` (real fixtures) and `d=4` (one smoke test) were actually run.
