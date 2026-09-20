# Autonomous multi-task session (2026-09-19, evening)

Given a standing mandate: work through three deferred items in order (#1 → #2 → #3, moving on only once out of
things to do on the current one), use `advisor()` check-ins liberally instead of stalling for user feedback,
commit after each numbered task, keep this worklog updated as I go (not just at the end).

1. Root-cause the 3D cubical naive-engine scaling problem (per-cell cost grows with `n` in 3D, flat in 2D,
   documented but never explained; sharpened by the same-day finding that the chunks engine does NOT have this
   problem on the same inputs).
2. Build a real, validated raw-UnionFind fast path for dimension 0/1 and wire it into the actual production
   engines (`CellularHomologyContext`/`PersistenceInChunksContext`), not just the correctness-only
   `SimplicialHomologyByDimensionContext`.
3. Simplicial set quotients/attaching maps.

This file is updated incrementally as each task progresses, not just at the end.

---

## Task #1: 3D cubical scaling root cause

### Hypothesis and methodology

The prior session's benchmark found 3D naive-engine per-cell cost growing with `n` (190 -> 340 -> 850 us/cell,
n=8 to 32) while the chunks engine on the SAME `CubicalGridStream` stayed flat (123 -> 90 -> 114 us/cell) —
meaning whatever the cause was, it was invisible to chunks but not to naive, which rules out a bug in the
stream's own cell enumeration or a fixed per-cell cost shared identically by both consumers.

Built `CubicalProfileDriver.scala` (`src/test/scala/.../homology/`, a new, kept driver mirroring
`SingleEngineProfileDriver`'s single-JVM-process convention, avoiding the sbt-hosted benchmark harness's own
documented timeout/daemon-thread contamination risk) to time three phases separately, each on a FRESH stream
instance:

1. **phase1**: `stream.iterator.size` — `CubicalGridStream.iterateDimension`'s own per-dimension sort
   (dominated by `containingTopCells` during each bucket's own sort).
2. **phase2**: `persistentHomology(stream2)` — `HomologyState`'s construction, which triggers its SECOND,
   GLOBAL sort (`processingOrder`, across all dimensions combined — see `Homology.scala`'s own comment on why
   this exists, a separate concern from phase1's per-dimension sort).
3. **phase3**: `state.diagramAt(Double.PositiveInfinity)` — the actual `advanceAll` reduction loop.

### What the measurement found

Before any fix, dims=3:

| n  | cells   | phase1 us/cell | phase2 us/cell | phase3 us/cell | total us/cell |
|----|---------|----------------|-----------------|-----------------|----------------|
| 8  | 4913    | 64.169         | 36.283          | 231.366         | 331.818        |
| 16 | 35937   | 18.630         | 16.070          | 438.595         | 473.295        |
| 24 | 117649  | 16.178         | 18.344          | 678.497         | 713.019        |

phase1/phase2 are FLAT or shrinking with `n` — ruling out both of the stream's own up-front sorting passes.
ALL of the previously-documented growth lives in phase3 (the actual reduction) alone: a real ~3x factor over
this range.

An initial JFR allocation/execution-sample pass (before phase separation) was mildly misleading on its own:
leaf frames prominently featured `CubicalGridStream.cartesianProduct`/`containingTopCells`-adjacent costs, which
are real but are a small, ambient-dimension-bound CONSTANT per call (max `2^3 = 8` for 3D), not something that
scales with `n` by itself. The phase-separated timing is what actually isolated WHERE the n-dependent growth
lived (phase3 specifically), not the profile alone.

### Root cause

`CubicalGridStream.filtrationValue` (`CubicalStream.scala`) was a completely UNCACHED `PartialFunction`.
`CellularHomologyContext.HomologyState` (and, checked separately, `CellularPersistenceInChunksContext.
HomologyState` too) bakes `stream.filtrationOrdering` directly into `Chain`'s `SortedMap`/`PriorityQueue`
pivot-selection machinery — every chain-arithmetic comparison during REDUCTION consults this ordering, which
calls `filtrationValue`, not just once per cell during the stream's own up-front sorts. Every single one of
those comparisons recomputed `containingTopCells(c)` (O(2^(ambientDim - dim(c))) per call) completely from
scratch.

In 2D, that per-call constant is small enough (max `2^2 = 4`) to stay hidden in ordinary noise. In 3D (max
`2^3 = 8`) it was large enough that, multiplied by however many comparisons a reduction of growing size
actually performs, it looked exactly like `n`-dependent growth in the earlier session's timing table — even
though `containingTopCells` itself has no dependence on `n` at all.

This explains the earlier "chunks doesn't have this problem" finding too, but not in the way originally
sharpened: it isn't that chunks' algorithm is structurally immune to this cost. Chunks pays the SAME uncached
`filtrationValue` cost per comparison — it's just that chunks' own baseline per-cell cost (multiple chunked
passes, `compress`/`globalReduce`) was apparently large enough already that the uncached cost's growth was
harder to see as a distinct trend in the previous session's noise floor, not that it was absent. (Confirmed
below: the fix speeds up BOTH engines substantially, not just naive.)

### Fix

A per-instance `mutable.HashMap[Cube, Double]` memoization cache on `filtrationValue`:

```scala
private val filtrationValueCache = mutable.HashMap.empty[Cube, Double]

override val filtrationValue: PartialFunction[Cube, Double] = new PartialFunction[Cube, Double]:
  def isDefinedAt(c: Cube): Boolean = inGrid(c)
  def apply(c: Cube): Double =
    filtrationValueCache.getOrElseUpdate(c, containingTopCells(c).map(topCellValue).min)
```

**Memory-frugality check, done explicitly rather than assumed** (this codebase has an established precedent,
`RipserCohomologyContext`'s deliberately opt-in `memoizeFiltrationValue`, defaulting to `false` specifically
because a global cache runs against Ripser's own memory-frugality design goal on potentially-huge VR
complexes): that precedent does NOT apply here, on two independently-checked grounds.

1. `CellularHomologyContext.HomologyState.CellIterator` is `stream.iterator.toVector.sorted(using
   processingOrder).iterator.buffered` — the ENTIRE stream is already eagerly materialized into one in-memory
   `Vector` before any reduction starts. A cache bounded by that same already-resident cell count adds nothing
   new.
2. Checked directly in `Homology.scala` (not assumed by analogy) that `CellularPersistenceInChunksContext.
   HomologyState.allCells` does the exact same thing: `0.to(internalMaxDim).iterator.flatMap(d => stream.
   iterateDimension.applyOrElse(d, ...)).toVector` — also a full eager materialization up front. So the
   "no new memory-frugality concern" argument holds for the chunks engine too, not just naive — this was flagged
   as a specific gap to check by `advisor()` (see below) and confirmed rather than left as an unverified
   extrapolation from the naive-engine case.

Separately checked: no caller in this codebase (`CubicalImage.scala`'s two `CubicalGridStream(...)` call sites,
every test) holds a stream across multiple `persistentHomology`/`diagramAt` runs, or constructs one without ever
consuming it. This wouldn't matter even if one did, though — the cache's ceiling is the stream's own fixed,
finite `totalCellCount`, independent of caller behavior, unlike VR's genuinely unbounded-in-practice complex
sizes.

### Measured after the fix

Phase-separated (3D, same driver):

| n  | cells   | phase1 us/cell | phase2 us/cell | phase3 us/cell | total us/cell |
|----|---------|----------------|-----------------|-----------------|----------------|
| 8  | 4913    | 18.763         | 20.627          | 60.988          | 100.379        |
| 16 | 35937   | 9.099          | 5.341           | 67.980          | 82.420         |
| 24 | 117649  | 5.392          | 4.525           | 91.408          | 101.325        |

Phase3's own per-cell cost is now 61 -> 68 -> 91 us/cell — much flatter than before. The previous ~3x factor
over this range is now ~1.5x, which is consistent with ordinary `O(log N)` reduction-accumulator behavior
(`Chain.reduceLoop`'s own `TreeMap`, growing chain sizes as reduction proceeds), not eliminated to perfectly
flat, but no longer a distinct pathology on top of that. Total time at n=24 dropped from 83886.0ms to 11920.8ms
(~7x faster) on the synthetic driver.

Confirmed on the REAL, established benchmark (`CubicalBenchmarkSpec.scala`, not just the custom driver), since
both engines share the same fixed `CubicalGridStream`:

- **2D** (`-DminN=8 -DmaxN=256 -DstepMultiplier=2`): naive dropped from ~75-150us/cell to a roughly flat
  21-45us/cell; chunks dropped from ~42-66us/cell to ~12-23us/cell. Full sweep time dropped from ~70s to 16.7s.
- **3D** (`-Ddims=3 -DminN=8 -DmaxN=32 -DstepMultiplier=2 -DtimeoutSeconds=180`): naive dropped from the
  previously-documented 284/349/891 us/cell (n=8/16/32) to **83.953/47.837/109.137 us/cell** — an 8x wall-clock
  improvement at n=32 (244777.9ms -> 29971.6ms), and the old clear growth trend is gone (aside from ordinary
  run-to-run noise); chunks dropped from 123/90/114 to **25.269/18.113/23.299 us/cell**.

Full `sbt test`: 249 examples, 244 passed, 0 failed, 5 skipped, 1 pending — bit-for-bit identical to the
pre-fix baseline, confirming this is a pure performance change with no behavior difference.

### Advisor check-in

Consulted before closing out the task. Verdict: fix is sound, measurement is clean, commit it. Two concrete
items came back and were both acted on before committing:

1. **Verify the memory-frugality argument holds for the chunks engine too, not just naive** (the doc comment
   as first written only argued it for `CellularHomologyContext`). Checked directly (see above) —
   `CellularPersistenceInChunksContext.HomologyState.allCells` also eagerly materializes everything up front,
   so the argument holds for both; the doc comment references both classes now.
2. **State the residual phase3 growth honestly** — "much flatter, with a residual ~1.5x over the same range
   that the previous measurement's 3x was hiding," not "the growth is gone." Applied verbatim in both this
   worklog and the `CLAUDE.md` update.

Also flagged: this fix changes the earlier same-day "naive vs. chunks is a *structural*, not constant-factor,
3D difference" finding (`eef2dc1`) — that framing is now wrong, since the growth was a shared stream-level bug,
not an algorithmic property of either engine. `CLAUDE.md`'s cubical section was revised in place (not merely
appended to) to reflect this, per the advisor's explicit instruction not to leave two contradictory accounts of
the same measurement in the file.

### Decisions

- **`CubicalProfileDriver.scala` kept permanently**, alongside `CubicalBenchmarkSpec.scala`, mirroring
  `SingleEngineProfileDriver`'s established precedent (a real, reusable single-JVM profiling tool, not
  throwaway scratch) — it may be useful again if cubical performance work continues (e.g. task #2's UnionFind
  fast path, or a future CubicalRipser-style engine).
- **No further investigation of the residual ~1.5x phase3 growth** — it's consistent with ordinary
  `Chain.reduceLoop` accumulator behavior already characterized elsewhere in this codebase
  (`.claude/WORKLOG-ripser-profiling.md`'s `Chain.reduceLoop` sections), not a new, unexplained pathology
  worth a dedicated investigation of its own.
- Task #1 is DONE.
