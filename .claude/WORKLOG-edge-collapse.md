# Worklog: flag-complex edge collapse (Boissonnat-Pritam / Glisse-Pritam)

2026-09-25, cloud session, continuing `.claude/WORKLOG-mainstream-feature-gap-analysis.md`. Executes that
worklog's item 5. Session transcript: `https://claude.ai/code/session_014m9j2FdkTDmh1PL2MA9rX6`.

## Network access and the verification workaround

The originating worklog cited two papers by title only (Boissonnat-Pritam SoCG 2020, Glisse-Pritam SoCG 2022).
`WebFetch`/`WebSearch` are both available in this session, but `WebFetch` to `arxiv.org`, `drops.dagstuhl.de`,
`gudhi.inria.fr`, and `www.semanticscholar.org` all returned `EGRESS_BLOCKED` -- this session's network policy
denies them, and `read_documentation` confirms the fix (broaden the environment's network access, or allowlist
the host) is the user's own environment setting, not something fixable from inside the session. Rather than
proceed from memory (this codebase's own stated policy: "a plausible wrong parser is worse than none," applied
here to "a plausible wrong algorithm"), used `add_repo`(`GUDHI/gudhi-devel`) -- public-repo anonymous git reads
are served directly by this session's git proxy regardless of network policy, confirmed by the tool's own
response -- and cloned it shallow to read the ACTUAL reference implementation
(`src/Collapse/include/gudhi/Flag_complex_edge_collapser.h`, `src/Collapse/doc/intro_edge_collapse.h`) directly.
GUDHI's edge-collapse module is co-authored by Pritam and Glisse themselves, so this is about as close to the
papers' own definitions as a non-paper source gets, and matches this codebase's established pattern of treating
a verified reference implementation (GUDHI, DREiMac, persim) as an acceptable substitute for a paper this
session cannot fetch, PROVIDED the actual source is read, not recalled.

**A real correction to the originating worklog's own framing, caught by reading the source**: the worklog calls
this "dominated-VERTEX removal on the VR 1-skeleton." That is a different construction (**strong collapse**,
Boissonnat-Pritam's own earlier paper, arXiv:1809.10945, vertex domination via `N[v] subseteq N[u]`). Edge
collapse removes DOMINATED EDGES (an edge whose link is a simplicial cone -- see `EdgeCollapseStream.scala`'s
own class doc for the exact definition, restated for a flag complex where it depends only on the graph);
vertices are never touched, confirmed directly from GUDHI's own doc ("The filtration value of vertices is
irrelevant to this function").

## What was built

`streams.EdgeCollapse.collapse(metricSpace, maxFiltrationValue)` -> `EdgeCollapsedMetricSpace` (a
`FiniteMetricSpace[Int]` over the SAME vertex ids, reifying the collapsed graph the same way `WitnessMetricSpace`
already reifies a non-metric weighted graph -- exactly the integration point the originating worklog specified,
"reuse existing plumbing rather than inventing a new representation"). Slots directly into
`EnumeratingCofaceSimplexStream`/`RipserCofaceSimplexStream` with no changes to either. `EdgeCollapsedMetricSpace`
also exposes `stats: EdgeCollapse.Stats` (`edgesBefore`/`edgesAfter`/`edgesRemoved`) and `validUpTo` (see the
enclosing-radius section below).

## Two real algorithm bugs, found and fixed via this codebase's own established discipline (cross-validate
against an independent computation, use tie-heavy and hand-built fixtures, don't trust a plausible design)

**First draft**: rather than port GUDHI's own single-pass, descending-filtration-value, live-mutating-state
algorithm, this session tried to design an "obviously correct by direct appeal to the definition" alternative:
iterate a per-edge "batch scan over candidate times" resolution to a whole-graph FIXED POINT across repeated
passes, reasoning that this sidesteps needing to trust a specific processing order this session could not
verify against the actual proof. Two distinct bugs surfaced, both caught by this class's own barcode-agreement
tests (`EdgeCollapseStreamSpec`), not by inspection:

1. **Gauss-Seidel-within-an-unordered-pass over-collapsed real topology.** The first version read the LIVE,
   progressively-mutating adjacency structure within a single pass, but iterated edges in an ARBITRARY order
   (ascending vertex-pair index, not sorted by filtration value at all). A minimal failing case
   (`n=5`, found by a targeted random search, not by further hand-derivation) showed a pentagon's worth of
   chords all judged "dominated forever" and removed, each using an EARLIER-in-the-SAME-unordered-pass sibling
   decision as (invalid) supporting evidence -- collapsing all five simultaneously silently turned a real,
   finite H^1 bar essential. Switching to Jacobi (freeze the whole graph at the start of each pass, apply every
   pass's decisions together) fixed the SPECIFIC 4-point hand fixture this session had been checking by hand,
   but not the general problem.
2. **Permanent removal without reconsideration, even under proper Jacobi passes, still over-collapsed.** A
   SECOND minimal failing case (`n=5`, again found by random search rather than by continued hand-tracing,
   which had already proven unreliable once) showed the deeper issue: once an edge is marked "dominated
   forever" and deleted, this design NEVER reconsiders it -- but a LATER pass, or a later edge in the SAME
   pass under Gauss-Seidel, can remove some OTHER edge that the FIRST edge's own "dominated forever" proof had
   relied on as a common-neighbor connection. The first edge's justification silently goes stale with no
   mechanism to correct it. Concretely: a pentagon's five chords, removed one at a time across a genuinely
   fixed-point-seeking iteration, still collectively erased an H^1 class that should have died at a finite time
   (`(1, 6.78, 7.35)` in the plain computation became `(1, 6.78, Infinity)` after collapse).

Both counterexamples are recorded because the SHAPE of the bug is informative beyond this one construction: an
"obviously correct, reads the definition directly" redesign of an algorithm whose PUBLISHED version uses a
specific processing order is not automatically safe just because each local step looks individually justified
-- the order itself can be load-bearing for the theorem, not an implementation convenience, and there is no
substitute for either reading the actual proof or faithfully porting verified code. **Fix**: abandoned the
redesign and ported GUDHI's `process_edges`/`common_neighbors`/`is_dominated_by` structure directly --
ONE pass, edges visited in strictly DESCENDING order of their own ORIGINAL filtration value, reading the live
(Gauss-Seidel) adjacency structure exactly as the reference does, each of the `n*(n-1)/2` original pairs decided
exactly once. The per-edge resolution logic itself (a batch scan over the finite set of candidate "join times"
of common neighbors, stopping at the first time domination fails, concluding "dominated forever" only if it
holds all the way to the last candidate) is NOT GUDHI's own incremental heap-based implementation -- it computes
the identical quantity for a FIXED snapshot (verified by a monotonicity argument: within one active-set window,
domination can only go false-to-true as the candidate time increases, never the reverse, so checking only at
join-time candidates is sufficient to find the first failure) -- but is now invoked under the SAME processing
order and SAME live-state-reading discipline the reference uses, which is what the two bugs above show is the
actually load-bearing part.

**A third, smaller bug, in a TEST rather than the implementation**: `EdgeCollapse.collapse(once)` (re-collapsing
an already-collapsed space) with an explicit `maxFiltrationValue = Some(Double.PositiveInfinity)` silently
re-admitted every already-removed (`+Infinity`) pair into the second collapse's own initial edge set, via
IEEE-754's `Infinity <= Infinity` -- the exact hazard `SheehyRipsSimplexStream`'s own `keptByThresholdAndCriterion`
already documents and fixes (CLAUDE.md's Sheehy section) for an unrelated construction. Confirmed this is a
GENERAL correctness requirement, not just a test-authoring mistake to avoid: fixed `EdgeCollapse.collapse` itself
(`d.isFinite && d <= bound`, not just `d <= bound`) so a caller passing an explicit `Some(Double.PositiveInfinity)`
bound against an ALREADY-collapsed input is safe by construction too, not just when using the default.

## Verification

`EdgeCollapseStreamSpec`, 11 examples, all passing:
- Two hand-built fixtures, BOTH carefully re-derived by hand against the FINAL (correct, GUDHI-ordered)
  algorithm -- not the first, wrong design -- after the bugs above were found: a 4-point cascade (three edges
  removed via a chain of earlier-removal-strips-a-later-edge's-common-neighbor reasoning, three left
  unchanged), and a genuine SHIFT example (5 points, found by search, then hand-verified term-by-term: an edge
  shifts from 6.802 to 8.257 -- exactly the original value of a DIFFERENT edge whose adjacency the shifted
  edge's own second candidate dominator needed and didn't have, because THAT edge had itself been removed
  earlier in the same descending pass).
- A generic (all-distinct-distance) triangle: removes exactly the longest edge -- unchanged from the first
  design, since this single-edge case has no cross-edge cascading to get wrong either way.
- Two disjoint edges (no common neighbor): collapses nothing.
- `EdgeCollapsedMetricSpace.minimumEnclosingRadius` equals the collapse's own bound, not the naive
  (potentially-`+Infinity`) formula computed against a graph with genuine infinite entries.
- Re-applying `collapse` to its own output: GUDHI's own doc states one pass is not necessarily minimal, so this
  does NOT assert idempotence (an earlier assumption that it should, left over from the first design's own
  fixed-point framing, was itself wrong and had to be corrected) -- only that a second round removes no MORE
  than the first left behind, and the barcode still agrees with plain VR afterward.
- **The real oracle throughout**: barcode agreement against plain, uncollapsed VR (`SimplicialHomologyContext`)
  -- a ScalaCheck property test across random point clouds (n=6-11, dims 2-3), a tie-heavy 3x3 integer grid
  (CLAUDE.md's own established discriminator for a new stream-adjacent construction), and the SAME comparison
  again under the DEFAULT (truncated) bound, not just the untruncated case. Zero-persistence (birth==death)
  bars are filtered out of BOTH sides before comparing -- `diagramAt` reports them literally, and edge collapse
  is SPECIFICALLY expected to eliminate exactly this kind of momentary flicker (confirmed directly: the
  originally-observed "disagreement" before this filter was added was entirely zero-persistence noise, not a
  real discrepancy -- the same class of spurious-looking failure `SheehyRipsStreamSpec`'s own comments warn
  about for an unrelated reason).
- Every representative on the collapsed complex's own reduction is a genuine cycle (`Chain.from(boundary).isZero()`),
  confirming the collapsed stream satisfies the ordering contract well enough for the reduction algorithm
  itself to behave correctly, not just that final bar values happen to agree.
- A dedicated 20000-trial script (not part of `sbt test`, deleted after use) swept `n=4..12` random point
  clouds specifically hunting for a barcode disagreement with the final implementation and found none, on top
  of the property test's own coverage.

Representatives: not independently re-verified as "still a cycle in the bigger complex" (that check is
tautological -- a `Chain`'s own boundary computation doesn't depend on which ambient complex it's considered to
live inside, only on its own cell structure, so if it's already a cycle in the smaller complex it's trivially
one in the bigger complex too). What actually matters -- that the collapsed complex is a literal subcomplex of
the original at every filtration level, so inclusion is a well-defined chain map -- is a structural fact about
the construction (edge collapse only ever removes cells or defers their entry to a later time, confirmed by
reading GUDHI's own doc, never adds or identifies anything), not something a runtime check on one fixture would
add confidence to beyond what the barcode-agreement suite already establishes.

`sbt test`: 536 examples (525 passed, 11 skipped benchmarks -- the 10 pre-existing plus this item's own
`EdgeCollapseBenchmarkSpec`), 0 failures -- clean before and after this item's work. `sbt scalafmtCheck`: the
same three pre-existing drift files noted in every prior worklog this session
(`DistanceToMeasure.scala`/`DtmRipsStream.scala`/`SheehyRipsStream.scala`), PLUS two more this run --
`homology.CircularCoordinates.scala`/`matlab.CircularCoordinatesResult.scala` (item 3's own new files, from an
earlier session today) -- confirming CLAUDE.md's own framing of this as an ongoing, growing-over-time
scalafmt-version drift rather than a fixed, closed set: neither file was touched by this item's own work
(confirmed by `git diff` showing zero content changes to either, only a reformat-then-revert cycle identical to
the other three), so both were reverted the same way, unrelated to this item.

## Enumeration cost: the worklog's own flagged question, answered from source

The originating worklog's own explicit instruction: "check whether `CofacetCursor` walks neighbours directly or
scans all `n` vertices with a threshold test... measure construction and reduction separately... before
claiming any speedup." Answered by reading source, not measuring first:

- **`SimplexIndexing.CofacetCursor`** (backs `PackedRipserCohomologyContext`'s own internal enumeration, NOT
  `RipserCofaceSimplexStream`): `step()` walks `j` from `vertexCount - 1` down to `0` unconditionally, testing
  membership via binary search -- a combinatorial, not neighbor-based, scan. Edge collapse never changes
  `vertexCount` (vertices are never removed -- see the correction above), so this scan's own cost is completely
  unaffected by how sparse the graph has become.
- **`EnumeratingCofaceSimplexStream.iterateDimension`** (the DEFAULT stream naive/chunks build from, per
  `matlab.TDA4j`'s own dispatch): for dimension `d >= 2`, walks EVERY combinatorial index in
  `0 until C(n, d+1)`, decodes it, and only THEN checks `keptByThresholdAndCriterion` -- again, `Theta(C(n,d+1))`
  regardless of sparsity.
- **`RipserCofaceSimplexStream.iterateDimension`**: genuinely different shape -- candidates at dimension `d`
  come from `(surviving (d-1)-simplices) x (vertices less than each one's own minimum)`, so a SMALLER
  `lastDimensionCache` (more lower-dimensional simplices pruned by the collapse) does reduce this stream's own
  enumeration work, proportionally.

**Conclusion, stated plainly rather than inferred**: edge collapse gives NO enumeration speedup for
`EnumeratingCofaceSimplexStream` or `PackedRipserCohomologyContext`'s own internal machinery -- both scan a
FIXED combinatorial range regardless of how sparse the resulting graph is. It gives a REAL, proportional
enumeration speedup for `RipserCofaceSimplexStream` specifically (not currently `matlab.TDA4j`'s own default
VR stream). REDUCTION cost is expected to improve for every engine (fewer real simplices means a smaller
`Chain` reduction problem), independent of which enumeration strategy built them -- this is the measurement
below.

## Measurement (`EdgeCollapseBenchmarkSpec`, `-DrunBenchmarks=true`)

Random point clouds, ambient dim 3, `maxDim=2`, 3 trials/size, median timings, construction
(`EnumeratingCofaceSimplexStream.iterator.toVector`) and reduction (`SimplicialHomologyContext`, the naive
engine) timed separately, exactly as the originating worklog asked:

| n | edges before | edges after | kept% | collapse(ms) | build plain (ms) | build collapsed (ms) | build speedup | reduce plain (ms) | reduce collapsed (ms) | reduce speedup |
|---|---|---|---|---|---|---|---|---|---|---|
| 30 | 903 | 244 | 27.0% | 12.7 | 89.6 | 51.6 | 1.74x | 1543.2 | 33.1 | **46.64x** |
| 50 | 2225 | 527 | 23.7% | 5.9 | 880.3 | 605.3 | 1.45x | 14424.0 | 337.9 | **42.69x** |

(A third size, `n=70`, was attempted and abandoned: the PLAIN/uncollapsed naive reduction exhausted the
default `-Xmx2G` heap — 60.7% of wall time in GC with 0.08GB free before the run was killed — itself a small
data point for the value of collapsing first, though not a controlled measurement, so not reported as one.)

**Cells removed is large and consistent** (73-76% of edges gone at both measured sizes) — a genuinely
different regime from the "probably small payoff... removes only O(n) cells" verdict the originating worklog
reached for the OTHER, MST-based collapse idea it considered for a different package (simplicial sets); this
is not that. **Reduction speedup is dramatic** (43-47x) — expected, since `Chain` reduction cost scales with
how many real (non-`+Infinity`) simplices exist to reduce, and three-quarters of the edges (hence everything
built on top of them) are gone. **Build speedup is real but far more modest** (1.45-1.74x) — confirms the
source-level analysis above with one refinement: `EnumeratingCofaceSimplexStream`'s own OUTER
combinatorial-index sweep (`0 until C(n,d+1)`) is exactly as expensive as predicted, unaffected by sparsity,
but `sortedByFiltration`'s downstream sort-and-cache pass runs only over the simplices that SURVIVE
`keptByThresholdAndCriterion` — and far fewer survive once their own filtration value has gone to `+Infinity`
via a collapsed edge, which is where the measured build speedup actually comes from. Both effects are real;
neither was inferred without checking.

## Four-surface integration decision

Wired into `matlab.TDA4j`/`cli.TDA4jCLI` as a new boolean option, `edgeCollapse` (default `false`,
`complex=vr` only — `require`d against every other `complex` value, matching the existing
`(ComplexKind, EngineKind)` refusal pattern this dispatch already uses for engine/complex incompatibilities).
Unlike circular coordinates, the vectorizations, and the boundary-matrix export (all deliberately NOT mirrored
on the CLI because their own output has a different SHAPE than a diagram), edge collapse changes nothing about
the output shape at all — it is a preprocessing toggle that still produces an ordinary barcode, fitting the
CLI's existing diagram-in-diagram-out model exactly, so this is the first of this session's five items to get
the full, unqualified 1:1 CLI mirror the rest of `matlab.TDA4j`'s compute options already have. Applies to
EVERY engine (`ripser`/`naive`/`chunks`/`cohomology`) uniformly by construction (the same `metricSpace` swap
feeds all four, exactly the "wire once, benefits every engine" shape the boundary-matrix work already
established for a different property of the complex) — including `ripser`, even though the measurement above
used the naive engine specifically and the source-level analysis found `PackedRipserCohomologyContext`'s own
internal enumeration (via `CofacetCursor`, not `EnumeratingCofaceSimplexStream`) is not expected to benefit
from a sparser graph the way construction does elsewhere; its reduction phase should still benefit from fewer
real simplices the same way `naive`'s does, and refusing the combination outright would need its own
disproof, not just an absence of a measurement in its favor — documented as an open, unconfirmed question for
`ripser` specifically (see the doc comment on the new option) rather than either claimed or blocked.

## Status

Item 5 of the recommended execution order is complete: `streams.EdgeCollapse`/`EdgeCollapsedMetricSpace`
(Scala API), `matlab.TDA4j`'s `edgeCollapse` option + `cli.TDA4jCLI --edge-collapse` (full 1:1 mirror, the CLI
exception among this session's five items), `EdgeCollapseBenchmarkSpec` (durable, rerunnable measurement,
matching `ApparentPairsBenchmarkSpec`'s own convention), `src/docs/developers-guide/architecture.md` and
`src/docs/user-guide/README.md` updated, full suite green, scalafmt/Laika clean. Two real algorithm bugs
found and fixed along the way (see above) — the eventual implementation is a faithful port of GUDHI's own
verified algorithm, not the independently-designed alternative this session started with. Next per the
recommended execution order: item 6, dual union-find cubical engine (Flash Cubical) Phase 1+2.
