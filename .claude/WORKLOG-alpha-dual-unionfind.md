# WORKLOG: dual union-find for alpha complexes (HelixDelaunay)

Session: 2026-09-25 (continuation of the same session that shipped item 6, the cubical dual union-find
engine — commits `ae6ffe8`/`a8642b0`). Item 7 of `.claude/WORKLOG-mainstream-feature-gap-analysis.md`'s
recommended execution order: "write this up as its own design note follow-on once the cubical version is
built and validated — not concurrent work, and specifically scoped to `HelixDelaunay` at `d ∈ {2,3}`."

## Why this item was treated as higher-risk than item 6, and what changed that assessment

The source worklog's own framing for this item was noticeably more tentative than item 6's unambiguous "YES"
— "plausible follow-on," explicit three-bullet risk list including "cospherical/degenerate input breaks the
precondition." Before writing any design document, I re-read `AlphaCrossValidationSpec`'s own doc comment
(pre-existing, not new to this session) and found it says more than the source worklog's own risk bullet
implied: `HelixDelaunay` has a *measured* ~1-in-600 assertion-failure rate (across ambient dimension 2-5
combined) and, separately, a *silent* incompleteness bug (a whole connected sub-chain of genuinely-Delaunay
faces missing, no exception raised) — found via fuzzing against `AlphaComplexDQP`, not previously connected to
this specific dual-graph precondition ("every facet has ≤2 cofaces").

Given a silent, un-signaled bug in the underlying triangulation is exactly the failure mode that could corrupt
this construction's own dual graph without any indication, I measured the actual precondition directly (not
the historical, dimension-mixed proxy) before committing further design effort — see the design note's own
"new finding" section for the full methodology and numbers. Headline results: 4000 trials on well-behaved
`[-1,1]`-uniform points at `d ∈ {2,3}`, zero violations; 3000 trials on `Gen.double` (the historical
methodology's own generator) at `d ∈ {2,3}`, one violation (~1-in-3000, landing at `d=3`); 50000 trials
isolating `d=2` specifically, one violation (~1-in-18700). Conclusion: the risk is real, not hypothetical, but
genuinely rare at the dimension this design actually targets — real, actionable evidence beyond what the
source worklog had, not a reason to abandon the approach.

## Design and hand verification

`.claude/DESIGN-alpha-dual-unionfind.md` has the full derivation. Two points worth restating here since they
were genuine, non-obvious adaptations, not a mechanical port:

1. **A facet's own dual-edge VALUE must come from `helix.filtrationValue(facet)` directly, never recomputed as
   `min` over its containing top simplices** — unlike a cubical grid, where those two quantities are the same
   by construction. `HelixDelaunay.computeFVal`'s own `edgeIsDelaunay` shortcut can give a genuinely SMALLER
   value than either containing triangle's own circumradius (standard alpha-shape theory, not a quirk).
   Confirmed this actually matters, not just in principle, via the hand-verified example below: two of its own
   facets take their value from this shortcut, strictly less than either containing triangle's value, and using
   the wrong formula would have silently shifted two bars' own birth values. Caught by hand-tracing before any
   code was written.
2. **Facet-to-top-simplex adjacency is brute-force** (one pass building a `Map[Simplex[Int], Vector[Int]]` from
   every top simplex's own facets), not coordinate-derived the way `CubicalGridStream`'s is — a Delaunay
   triangulation has no regular-grid structure to exploit.

**Hand-verified worked example**: 5 points (a unit square's 4 corners plus one off-center interior point,
chosen specifically to avoid a cospherical tie among the corners), a 4-triangle fan triangulation. Traced the
dual union-find completely by hand against `HelixDelaunay`'s own already-computed filtration values (not
re-deriving Helix's own geometry, which is already validated elsewhere — verifying only the new dual
construction on top of it, the same division of labor the cubical example used). Predicted bars matched
`SimplicialHomologyContext`'s own output exactly, full floating-point precision, first try — no bugs found in
this particular trace, unlike item 6, because the two bug fixes item 6 needed (resolved-root vs. raw-id for
the "old" side's top-cell lookup; explicit `infinityId` special-casing in the young/old decision, rather than
relying on a birth-value comparison alone) were ported directly into this implementation from the start, not
rediscovered. An early draft of the design note wrongly described this fixture's own H1 bars as "all
zero-persistence" — two of the four are not (`2.0 -> 2.002868`, `2.0 -> 2.009308`); caught by a failing test
assertion, not by re-reading the numbers, and fixed in both the note and the test.

## Implementation

`homology/FastAlphaHomology.scala`, `FastAlphaHomologyContext[CoefficientT: Field]`, compiled clean on the
first attempt and matched the hand-verified example immediately. Structure mirrors
`FastCubicalHomologyContext` closely (`computeH0`, `computeDualTopDimension`, the event-ordering/union-find
core) with the two adaptations above plus one new piece: an explicit, up-front validation pass over
`facetToTopIds`, throwing `IllegalStateException` (naming the offending facet(s) and their coface count, and
pointing at the specific `HelixDelaunay` limitation likely responsible) if any facet has a coface count other
than 1 or 2 — the "fail loudly and specifically" response the measured, real precondition risk requires.

## Testing

`homology/FastAlphaHomologySpec.scala`:
- The hand-verified 5-point fan fixture (structural counts plus exact multiset agreement with the naive
  engine).
- A second, richer 6-point fixture (found by a small search, not hand-derived) with a genuine
  nonzero-persistence H1 bar and at least one real merge between two non-infinity dual components — code-path
  coverage the first fixture's own all-merges-go-directly-to-infinity structure never exercised (the
  orientation-flip arithmetic and both of item 6's ported bug fixes).
- Genuine-cycle checks and `Fp(3)` sign-genericity on both hand fixtures.
- `requires ambient dimension 2`.
- A pinned regression fixture (a concrete 12-point set, found by a targeted 50000-trial search, not a random
  seed relied on to rediscover it — the same discipline `AlphaComplexDQPRegressionSpec` already uses in this
  file for comparably rare failures) asserting the engine throws its own specific, named exception on a real
  facet-multiplicity violation.
- A random-point property test, classifying rather than failing on either of the two known, pre-existing
  `HelixDelaunay` limitations it can legitimately hit (this class's own named `IllegalStateException`, or an
  unrelated `HelixDelaunay` construction failure) — asserting full agreement with the naive engine plus genuine
  cycles on every trial that doesn't hit either.

All scratch investigation scripts (the precondition-rate measurements, the worked-example computation, the
regression-fixture search) were throwaway, run via `Test/runMain`, and deleted before committing — not part of
the permanent test suite.

## Scope decision: not wired into `matlab.TDA4j`/`cli` this session

Unlike item 6, which was wired into all four surfaces (no correctness risk at all — a cubical grid's own
"every facet has ≤2 cofaces" holds unconditionally), this engine carries a real, if rare, exception risk on
otherwise-ordinary user input. Exposing that as a production `engine=` option is a user-experience judgment
call — is a ~1-in-18700 chance of a clear, actionable exception (rather than a silent wrong answer, which is
the alternative failure mode DQP's own already-shipped "accepted limitation" exhibits) an acceptable tradeoff
for the speed this engine offers on 2D alpha complexes — that the project lead should make directly, having
seen the measured rate, rather than one this session should make unilaterally. The engine itself is complete,
tested, and documented for direct Scala use (`alpha-complex.md`, `persistence-engines.md`'s engine 7 section);
wiring it into `matlab.TDA4j`/`cli` (a new `EngineKind` case, `alphaBackend=helix`-only validation, the same
reject-everywhere-else pattern item 6's own `EngineKind.FastCubical` needed across every non-alpha complex
branch) is a well-scoped, low-effort follow-up once that call is made — not blocked on anything technical.

## Commands

```
sbt "testOnly org.appliedtopology.tda4j.homology.FastAlphaHomologySpec"
sbt clean test        # full suite, confirmed green after this change
sbt laikaSite          # docs build, confirmed clean
```
