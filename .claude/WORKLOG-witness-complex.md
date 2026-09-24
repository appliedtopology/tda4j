# Witness complex, built on the genericized VR coface machinery (2026-09-23)

Asked to implement a witness complex stream (De Silva & Carlsson, "Topological estimation using witness
complexes", 2004) -- the user was reading the JavaPlex tutorial and noted it leans heavily on this
construction. The user is a JavaPlex co-author, so precision against JavaPlex's own semantics mattered more
than usual; this worklog leans on the actual JavaPlex Java source (downloaded and read directly, not recalled
from memory) rather than the paper's prose alone.

## Reference source

Pulled `LazyWitnessStream.java` and `WitnessStream.java` from
`github.com/appliedtopology/javaplex` directly (`edu.stanford.math.plex4.streams.impl`) and read them in
full before writing any Scala. Key facts extracted, all confirmed against the literal source, not inferred:

- JavaPlex ships **two independent implementations**, not one "witness complex with options":
  - `WitnessStream<T>` (plain name, no "Lazy") -- the **general** construction. Each dimension `k` has its own
    threshold array `m[k][n]` = the `(k+1)`-th nearest landmark's distance to witness `n` (0-indexed, NO
    sentinel). `addCofaces_` explicitly requires every codimension-1 facet of a candidate to already be
    present in the complex (`containsElement(face)`) before accepting it, and records
    `filtrationIndex = max(filtrationIndex_from_faces, own_filtrationIndex)`.
  - `LazyWitnessStream<T>` -- the **lazy** construction, `extends FlagComplexStream`: only the 1-skeleton is
    computed directly (`constructEdges`), and everything above dimension 1 comes from the generic flag/clique
    expansion `FlagComplexStream` already provides. One global `nu` parameter (JavaPlex default: `2`, hard
    capped to `{0, 1, 2}` via `verifyLessThan(nu, 3)`) selects a single per-witness threshold `m_nu`, computed
    with a **prepended zero sentinel**: `m_temp = [0.0] ++ sort(D[:, n])`, `m[n] = m_temp[nu]`. So `nu = 0` ->
    `m = 0` everywhere; `nu = 1` -> nearest-landmark distance; `nu = 2` -> 2nd-nearest.
  - Both witness sets are **every point of the metric space, landmarks included**
    (`plex3Compatible = true`, the default in both classes) -- a landmark can witness itself and others.
- The edge/simplex value formula, both classes: for candidate landmark set `sigma` and witness `n`,
  `d_max(n) = max_{l in sigma} D(l, n)`; per-witness value `= max(0, d_max(n) - m(n))`; the simplex's own raw
  value is the **min over all witnesses** of that per-witness value. (`getWitnessAndDistance` in
  `LazyWitnessStream`; the equivalent inline loop in `WitnessStream.addCofaces_`.)

## The one non-obvious math fact this whole design rests on

`max(0, x)` is monotone nondecreasing, so it **commutes with `min`**: `max(0, min_n g(n)) = min_n max(0,
g(n))`. This is why JavaPlex's own "clamp per witness, then min" and a hypothetical "min, then clamp once" are
provably identical -- confirmed by hand before writing `WitnessGeometry.witnessValue`, not assumed. Not just
a curiosity: it's what makes a single shared `witnessValue` helper valid for both the lazy edge formula and
the general per-dimension formula without re-deriving anything per call site.

## Design: reusing `RipserCofaceSimplexStream`, twice, for two different reasons

Both `CechCofaceSimplexStream` (2026-09-19 session) and this arc's two witness streams subclass
`RipserCofaceSimplexStream` rather than hand-rolling coface enumeration -- but the two witness variants get
there by two genuinely different arguments, and conflating them would have been a real bug:

1. **`LazyWitnessSimplexStream`**: the lazy witness complex is *defined* as the flag/clique complex of a
   weighted graph. So `WitnessMetricSpace` reifies that graph directly as a `FiniteMetricSpace[Int]` (over
   LOCAL landmark indices `0 until landmarks.size`) whose `distance(a, b)` **is** the edge witness value at a
   fixed `nu`. Handed to `RipserCofaceSimplexStream` with **no `filtrationValueOverride` at all** -- the
   inherited `MaximumDistanceFiltrationValue` ("max pairwise distance") IS the flag-complex extension this
   construction wants, for free. `WitnessMetricSpace` is explicitly documented as NOT a real metric (can be
   zero for distinct landmarks, need not satisfy the triangle inequality) -- and explicitly never to be handed
   to `JVPTree`/`SparseMetricSpace`/`RecursiveStackVietorisRipsSimplexStream`/anything in `alpha`, all of which
   assume genuine metric properties `RipserCofaceSimplexStream`'s own combinatorial candidate generation does
   not need.

2. **`WitnessCofaceSimplexStream`** (general): initially worried this reuse was UNSOUND here, for a reason
   worth recording since it could trip up a future change to either class. The general complex's per-dimension
   `own_k` value is **not** obviously monotone facet-to-coface on its own (a fixed witness's `m_k` grows with
   `k`, working against `max_l D(l, ·)` also growing with `|sigma|` -- the two don't obviously net out in the
   right direction). Worked through it explicitly:
   - Picked the SAME witness `x` that achieves a coface's `own_k` minimum, restricted to a facet `sigma`: its
     `max_l D(l,x)` over the facet is `<=` the coface's (subset max), but `m_{k-1}(x) <= m_k(x)` (a lower rank
     is a smaller nearest-neighbor distance) -- so `x` witnessing the coface at value `v` does NOT imply `x`
     witnesses the facet at `<= v` under the facet's OWN (smaller) threshold. So raw `own_k` alone genuinely
     is not guaranteed monotone, and JavaPlex's `containsElement(face)` gate looked, at first, like it might be
     doing real work `RipserCofaceSimplexStream`'s "generate from one canonical facet, filter by threshold"
     shape can't replicate (that shape never independently re-validates every OTHER facet of a candidate).
   - Resolved by noticing JavaPlex's own `filtrationIndex = max(filtrationIndex_from_faces, own_filtrationIndex)`
     line: the RECORDED value is not `own_k` alone, it's `max(own_k, max over facets)`, computed recursively
     bottom-up. That recursive max is exactly what makes "the complex at threshold R" automatically
     downward-closed for every R, by the identical argument that makes VR's own "max pairwise distance"
     automatically downward-closed (a monotone-nondecreasing aggregate of the same face relation, over the
     face relation itself). And `containsElement(face)` turns out to be REDUNDANT once this recursive fv is
     used: if some facet's own recorded value already exceeds the global cutoff `maxDistance`, the coface's
     `max(...)`-computed value exceeds it too, so the plain `fv <= threshold` filter excludes it anyway --
     `containsElement` is JavaPlex's own imperative short-circuit (skip computing `own_k` for a doomed
     candidate), not a correctness requirement.
   - This means `RipserCofaceSimplexStream`'s plain "generate from canonical facet, filter by
     `filtrationValue <= threshold`" IS correct here too, PROVIDED `filtrationValueOverride` computes the
     recursive max, not the raw `own_k`. Verified both by proof and empirically (`WitnessStreamSpec`'s
     downward-closure + brute-force-enumeration-completeness checks both pass on random clouds), and by a
     hand-built discriminator fixture designed specifically to fail if the recursion were dropped (below).
   - **Memoization matters here in a way it didn't for Cech's `CechFiltration`**: `EnumeratingCofaceSimplexStream`
     memoizes only its OWN default fallback filtration value, never a caller-supplied
     `filtrationValueOverride` -- so the general stream's override caches itself in a `TrieMap`, same pattern
     as `CechFiltration`. But UNLIKE `CechFiltration`'s facet-floor shortcut (`cache.getOrElse(facet, 0.0)`,
     safe there because Cech's own downward-closure argument guarantees every facet was already visited one
     dimension earlier), this override's facet lookups **recurse** (`apply` calls itself on every facet)
     rather than defaulting a cache miss to `0.0` -- because candidate generation here only ever touches ONE
     canonical facet (the min-vertex-removed one) before asking about a candidate at all, so a facet other
     than that one is generally NOT already cached, and silently defaulting it to `0.0` would drop it from the
     max and corrupt monotonicity for exactly the simplices this whole recursive-max scheme exists to get
     right.

## Hand-derived discriminator fixture

Four points on a line -- `P0=(0,0), P1=(1,0), P2=(3,0), P3=(7,0)` -- landmarks `{P0,P1,P2}`, witness set all
four (every pairwise distance an exact integer, so the fixture needs no floating-point tolerance anywhere).
Worked the formula by hand:

- `D` (landmarks x witnesses): `[[0,1,3,7],[1,0,2,6],[3,2,0,4]]`.
- `nu=2` edges: `d(0,1)=0`, `d(1,2)=0`, `d(0,2)=1` -- and `1 > 0 + 0`, the triangle inequality genuinely fails
  (confirms `WitnessMetricSpace` really isn't a metric, not just as a defensive doc comment).
- The triangle `{0,1,2}`'s own raw `own_2 = 0` (every witness's own 3rd-nearest-landmark threshold happens to
  exactly cancel its max-distance-to-the-triangle) -- but its facets' max is `1` (from edge `{0,2}`), so the
  CORRECT recorded value is `max(0, 1) = 1`. An implementation that used raw `own_k` alone would report `0`
  here: a coface with a SMALLER filtration value than one of its own faces, silently violating monotonicity.
  This is the single strongest test in `WitnessStreamSpec` -- both `WitnessMetricSpace(nu=2)` and
  `WitnessCofaceSimplexStream.recursiveFiltrationValue` are checked against these exact hand-computed integers.

## Landmark selection

`LandmarkSelector.maxmin` (sequential furthest-point/greedy covering, JavaPlex's own recommended default) and
`.random` (seeded uniform). `maxmin` ties are broken by lowest ambient index -- iterate `sortedElements`
ascending and let `maxBy`'s own "first occurrence wins a strict-greater-than comparison" semantics do it,
rather than hand-rolling a comparator; pinned by a fixture with an exact tie (`{0, +5, -5}` from
`firstLandmark=0`). Both return a `LandmarkSelection(landmarks, coveringRadius)` -- the covering radius
(`R = max_x min_l d(x,l)`) falls out of `maxmin`'s own tracked `minDistToLandmarks` array for free, and is
independently recomputed (a genuinely separate brute-force pass, not just returning the same running value)
in the property test that checks it, catching a possible bug in the running-min UPDATE step that a
self-consistency check couldn't.

## Testing (`WitnessStreamSpec`, 19 examples; `TDA4jSpec`'s own witness section, 8 examples; all passing)

Order follows the codebase's established convention for validating a new stream (see `CechStreamSpec`'s own
header): hand-derived discriminator fixture first, then an independent from-scratch brute-force
reimplementation of the whole formula (translated fresh from the JavaPlex source, not sharing code with
`WitnessGeometry`) cross-checked cell-for-cell via property tests on random point clouds, then the
general-vs-lazy(nu=2) cross-check the shared math predicts (both the exact 1-skeleton match and the `>=`
inequality at higher dimensions), then downward-closure/enumeration-completeness (brute-force
`combinations(d+1)` filtered by the independent oracle vs. the stream's own `iterateDimension`), then the
standard bars-account-for-cells structural invariant on both stream flavors via the naive engine, then
cross-engine agreement (naive vs. ripser, chunks, and cohomology -- see below), and a final qualitative-only
smoke test (20 circle points, self-witnessing) checking at least one long H1 bar exists -- explicitly NOT a
pinned oracle: small/evenly-spaced witness complexes are known to be messy at `nu=2`'s default heavy fv=0
ties, so an exact expected barcode wasn't attempted there.

**A pre-submission advisor review caught three real gaps in the first draft of this test suite**, worth
recording since each is a genuine methodological lesson, not just a nitpick:

1. **The `cycleVertices`-maps-to-ambient-indices test could not have caught the bug it was written to catch.**
   With `numLandmarks=3` over 6 points, an UNMAPPED local landmark index (0, 1, or 2) is *also* a perfectly
   valid ambient index in `[0, 6)` -- so "every reported vertex is in `[0, points.length)`" passes whether or
   not the facade actually maps through `witnessLandmarks(i)`. Fixed by computing the actual landmark set
   `maxmin` chooses (`{0, 4, 5}` on the fixture in question -- points 4 and 5 tie exactly at `sqrt(4.25)`, 4
   wins by lower index) and asserting the reported vertices are neither a subset of `{0,...,numLandmarks-1}`
   nor anything outside the real landmark set -- the only way to actually distinguish "mapped" from "not."
   Checked for both `engine=ripser` (the `DiameterIndex` decode path) and `engine=naive`/general (the
   `Simplex[Int]` path) separately, since they're independent mapping code.
2. **`chunks`/`cohomology` were offered by the facade for `complex=witness` but never once run against a
   witness stream.** `CellularPersistenceInChunksContext` has a documented history of a real pairing bug on
   tie-heavy cliques (`WORKLOG-chunks-pairing-bug.md`), and `nu=2`'s per-witness clamp makes duplicate
   zero-length bars (`birth == death == 0.0`) genuinely common -- a similar tie-heavy shape. Added property
   tests: chunks-vs-naive and cohomology-vs-naive on `LazyWitnessSimplexStream`, cohomology-vs-naive on
   `WitnessCofaceSimplexStream`, each run for BOTH a proper-subset landmark configuration and "every point is a
   landmark" (maximal ties). All agree exactly -- a real, previously-unexercised combination now confirmed, not
   assumed to "just work" because neither engine has witness-specific code.
3. **Cross-engine barcode comparisons used `.toSet`, which silently collapses exactly the duplicate-bar
   multiplicities `nu=2`'s ties make likely.** Fixed by comparing sorted `List`s instead (multiset-correct)
   throughout, including the pre-existing ripser-vs-naive check from the first draft -- it still agrees exactly
   under the stricter comparison, so the weaker check hadn't actually been hiding a bug here, but the
   methodology itself was the real defect, independent of whether this particular run exposed it.

Also added, per the same review: a `computeFromDistanceMatrix`-vs-`computeFromPoints` parity check for
`complex=witness` (both variants) -- witness needs only a `FiniteMetricSpace[Int]`, unlike alpha/Cech, so this
path exists and wasn't previously exercised -- and a `landmarkSelector=random`/`landmarkSeed` facade test
(only `maxmin` had been exercised through `matlab.TDA4j` until this point, even though `random` is an equally
documented, equally reachable option value).

## MATLAB/CLI/docs wiring (all four CLAUDE.md surfaces touched this session)

- `matlab.TDA4j`: `complex=witness`, new options `numLandmarks` (REQUIRED, no sensible default),
  `witnessVariant` (`lazy`/`general`), `landmarkSelector` (`maxmin`/`random`), `landmarkSeed`, `nu`. Engine
  defaults/refusals mirror the `alpha`/`cech` pattern exactly: `witnessVariant=general` refuses
  `engine=ripser`/`chunks` (not a flag complex), `witnessVariant=lazy` allows all four (it really is a flag
  complex, including `ripser` -- the one exception in this codebase to "ripser is VR-only"). `witnessVariant`
  is parsed BEFORE the engine default is computed, since witness's own engine default (`ripser` for lazy,
  `naive` for general) depends on it, unlike every other `complex` value's default. `maxFiltrationValue`
  defaults to the enclosing radius for lazy (valid, same cone argument as VR/Cech) but to `+Infinity` for
  general (NOT valid there -- not a flag complex). `cellVertices` maps every local landmark index back through
  `witnessLandmarks(i)` before returning to the caller -- checked directly in `TDA4jSpec` (see the Testing
  section above for how, and for a real discriminating-power bug this exact check went through first).
- `cli.TDA4jConf`/`TDA4jCLI`: `--num-landmarks`/`--witness-variant`/`--landmark-selector`/`--landmark-seed`/
  `--nu`, 1:1 mirrored into `buildOptions`, no CLI-side validation/defaults (same convention as every other
  flag).
- `developers-guide/architecture.md` (new "Witness complexes" section, package-layout line), `persistence-
  engines.md` (engine-5 justification paragraph + choosing-an-engine table row), `class-diagrams.md` (two new
  subclass edges), `user-guide/index.md` (new "Witness complexes" section, CLI flag list, MATLAB options
  table, engine-choice table, tutorials-placeholder note). `sbt makeSite` run once at the end to confirm the
  new Markdown/Mermaid actually renders (Paradox has no separate "check" task) -- rendered cleanly, no new
  warnings beyond three pre-existing, unrelated scaladoc link-resolution warnings.

## Deliberately NOT done / known divergences from JavaPlex

- **`matlab.TDA4j`/`cli.TDA4jCLI` callers have no way to get `R` (the landmark selection's own covering
  radius) or the chosen landmark set back out.** The JavaPlex tutorial's own recommended recipe -- pick
  landmarks via maxmin, read off `getMaxDistanceFromPointsToLandmarks()`, then pass `2R` (or some other
  multiple) as `maxFiltrationValue` -- is NOT reproducible through the facade as it stands: `numLandmarks`
  goes in, but `R` never comes out, so a MATLAB/CLI caller wanting the tutorial's own threshold choice has to
  either guess a fixed value or drop to Scala (`LandmarkSelector.maxmin(...).coveringRadius`) and bypass the
  facade for that one step. Given the user's own stated context is reading the JavaPlex tutorial, this is
  likely the FIRST friction point they'd hit trying to replicate it end-to-end through MATLAB -- flagged here
  as a real, known gap, not fixed in this session (would need a new `PersistenceResult`-adjacent return
  channel, or a separate `TDA4j` landmark-selection entry point, either a genuine new-surface decision, not a
  small addition to make silently).
- **JavaPlex's `numDivisions`/`IncreasingLinearConverter` filtration-value quantization was not replicated.**
  This codebase's filtration values are real `Double`s throughout (every other stream here works the same
  way); JavaPlex's own tutorial numbers won't reproduce bit-for-bit against this implementation for that
  reason alone, independent of anything else -- not a bug, a deliberate divergence, documented so a future
  session porting an actual JavaPlex tutorial number doesn't mistake it for one.
- **Landmarks are always a subset of the ambient point set's own indices** (JavaPlex's own convention, and
  every downstream tool's), not arbitrary external points the original paper's definition technically allows.
- **No `getAssociatedSimplices`/witness-tracking diagnostic API** (JavaPlex's own per-witness reverse lookup,
  used for witness bicomplexes) -- out of scope; nothing in this session's ask needed it, and this codebase's
  own representatives principle (a real `Chain` per bar) already covers the "what does this bar actually look
  like" need a different way.
- **`RipserCofaceSimplexStream`'s own `iterateDimension` never updates `currentDimension` in its `d >= 1`
  branch** (a pre-existing quirk, not introduced by this session) -- so for `d >= 2` it always regenerates the
  whole dimension via a fresh `binomial(L, d)` sweep rather than reusing `currentDimensionCache`. Inherited by
  both witness streams unchanged; fine at tutorial scale (`L` in the tens), not chased here since it's a
  pre-existing base-class property, not something this arc's changes made worse.
- **Tutorial-scale timing, measured as a real controlled A/B, not the confounded first attempt.** `N=1000`
  random points, `numLandmarks=50`, `maxDimension=2` (so `LimitedCofaceSimplexStream` caps enumeration at
  4-vertex simplices), one JVM, ad-hoc timing (not `EngineComparisonBenchmarkSpec`-grade, no repeated trials).
  A first pass compared `witnessVariant=lazy`+`engine=ripser` (0.23s) against `witnessVariant=general`+
  `engine=naive` (51.0s) and wrote up "~220x slower" -- caught by advisor review as varying THREE things at
  once (variant, engine, AND threshold: lazy's default is the enclosing radius, general's is `+Infinity`),
  exactly the class of mistake `[[tda4j-measure-dont-infer]]`'s memory file exists to prevent. Redone properly:
  - **Isolating variant alone at `+Infinity`** (both `engine=naive`, same 251,175 materialized cells either
    way -- `50 + 1225 + 19600 + 230300`): lazy **41.9s** end-to-end, general **45.2s** end-to-end -- under
    ~8% apart. A separate materialize-only pass (iterate the dimension-capped stream, no reduction) shows
    construction itself is NOT that close: general **5.1s** vs. lazy **3.5s**, ~1.5x, plausibly the recursive
    facet-lookup formula vs. a single metric `distance` call. (That materialize-only number plus a
    reduce-only pass done afterward, on the SAME stream re-iterated with warm filtration-value/TrieMap
    caches -- not the identical already-built `Vector`s, since `RipserCofaceSimplexStream` regenerates `d>=2`
    from a fresh binomial sweep each time it's asked, a pre-existing quirk noted below -- sums to MORE than
    the end-to-end facade number, e.g. general's `5.1 + 46.8 = 51.9s` against its own `45.2s` end-to-end;
    consistent with real double-counted materialization work, not a contradiction.) So: construction alone
    differs by a real ~50%, but end-to-end wall clock differs by under 10%, because reduction (~42-47s either
    way) dominates the total by roughly 10x over construction. The original "220x" was comparing
    `PackedRipserCohomologyContext` (which never materializes the full complex at all) against full
    materialization-plus-naive-reduction of a quarter-million cells -- an engine-choice effect, not a variant
    effect.
  - **Isolating variant alone at the realistic threshold `2R`** (`R` = the landmark selection's own covering
    radius, `R ≈ 0.121` on this cloud, `2R` the JavaPlex tutorial's own recommended threshold -- a SEPARATE,
    smaller complex per variant now, not the shared 251,175-cell one above, since `fv_general >= fv_lazy`
    pointwise means general's own complex at a fixed threshold is a subset of lazy's): `lazy+naive` **11.16s**
    (7887 bars) vs. `general+naive` **11.46s** (7785 bars). This one is single-trial and UNCONFIRMED, not a
    measured "<3%" the way the `+Infinity` case's ~8-9% is (reproduced across two separate runs, same
    direction both times): a single `lazy+ripser@2R` call alone varied `0.14s` to `0.20s` (~40%) between two
    otherwise-identical cold-JVM invocations of this exact script, which is larger than the 2.7% gap this
    bullet would otherwise be claiming -- so the honest statement is "indistinguishable from noise at
    single-trial resolution," not "confirmed under 3%." A real answer would need
    `EngineComparisonBenchmarkSpec`-style repeated trials, not attempted here.
  - **Isolating engine alone at `2R`** (`witnessVariant=lazy` fixed, only `engine` differs): `lazy+ripser`
    ran **0.14-0.20s** across two separate invocations vs. `lazy+naive`'s **11.16s** -- identical bar count
    (7887) either way, roughly 55-80x given the ripser-side noise just noted. This, not the variant choice, is
    where nearly all of a default-vs-default user-facing gap actually comes from: `witnessVariant=lazy`
    (facade default, `engine=ripser`) took **0.14-0.20s** at `2R`, while `witnessVariant=general` (facade
    default, `engine=naive` -- it has no `ripser` option) took **11.46s** -- roughly 55-80x, and (given the
    two points above) that gap is attributable mostly to the engine, with the variant's own share unconfirmed
    at this resolution but bounded well under the engine's.
  - **Honest take-away for a future tutorial port, and for the user guide**: prefer `witnessVariant=lazy`
    (the facade default) when the flag-complex behavior is acceptable -- primarily because it unlocks
    `engine=ripser`, which is where nearly all of the realistic-usage speed gap actually is, not because the
    general construction is intrinsically much more expensive to build (it isn't, by more than ~3-50%
    depending which sub-cost is being isolated). The real lever for a slow run at either variant is picking a
    smaller `maxFiltrationValue` (the tutorial's own `2R`, not `+Infinity`) to shrink the materialized cell
    count in the first place -- `+Infinity` to `2R` cut `general+naive` from ~45s to ~11s, the single biggest,
    most clearly single-variable effect measured in this whole arc.
- **A real hazard, but not new to this construction**: `WitnessCofaceSimplexStream` driven DIRECTLY (bypassing
  `matlab.TDA4j`'s own `LimitedCofaceSimplexStream` dimension cap) with its default `maxFiltrationValue =
  +Infinity` will enumerate every dimension up to `L - 1` -- for `L=50` that's the full `2^50` power set, a
  correctness-irrelevant but very real performance trap. This is the identical standing risk any unbounded
  `CofaceSimplexStream` carries when used without either a finite threshold or a dimension cap (the same is
  true of `EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(Double.PositiveInfinity))` on
  a large point cloud) -- not something introduced by, or specific to, the witness complex. Documented on the
  class itself and in the user guide (pick a finite `maxFiltrationValue`, or wrap in `LimitedCofaceSimplexStream`, for
  a direct, un-faceaded call).
- No parallel/opt-in-parallelism variant was added for either witness stream (unlike Cech's
  `parallelFiltrationValue`) -- the per-candidate cost here (an `O(N)` witness-value computation) is real but
  this session's ask was the construction itself, not a performance pass; a natural future addition if a large
  witness complex turns out to need it, following the exact `TrieMap`-based pattern
  `.claude/WORKLOG-parallelization-survey.md` already established for Cech.
