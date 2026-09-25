# WORKLOG: extending FastAlphaHomologyContext to d >= 3 via a hybrid with chunks

Session: 2026-09-25, same session as `WORKLOG-fast-engines-hybrid-cubical.md` (cubical's own d>=3 extension,
done first) and `WORKLOG-alpha-dual-unionfind-matlab-wiring.md`. Direct continuation of
`.claude/DESIGN-fast-engines-hybrid-middle-dimensions.md`'s own "Sequencing" section: alpha's port, deliberately
done AFTER cubical's was implemented and validated, not concurrently.

## What changed

- `alpha/AlphaShapes.scala`: new `LimitedAlphaShapesStream(helix: HelixDelaunay, maxDim: Int)` -- the
  `Simplex[Int]`/`HelixDelaunay` analogue of `streams.LimitedCubicalGridStream`, needed because `AlphaShapes` is
  a `StratifiedSimplexStream[Int, Double]`, not a `CofaceSimplexStream[Int, Double]`, so `streams.
  LimitedCofaceSimplexStream` (hardcoded to the latter) doesn't fit it. Same shape as the cubical wrapper:
  delegates `filtrationOrdering`/`filtrationValue` unchanged, hides dimensions `> maxDim`.
- `homology/FastAlphaHomology.scala`: `persistentHomology` now branches on `helix.ambientDimension`, byte-for-
  byte mirroring `FastCubicalHomologyContext`'s own structure. `d=2` is the unchanged old path. `d >= 3` adds
  `computeMiddleDimensions`, running `PersistenceInChunksContext[Int, C](ambientDimension - 2)` on
  `LimitedAlphaShapesStream(helix, ambientDimension - 1)` and taking its `barcodeAt(+Infinity)` directly (covers
  `H_0` through `H_{ambientDimension-2}`), then appending `computeDualTopDimension(helix)` (unchanged, on the
  real untruncated stream) for `H_{d-1}`. `require(ambientDimension >= 2, ...)` replaces the old `== 2` check.
  `FastAlphaTriangulationException`'s own message and the class doc were both updated to report the new,
  dimension-dependent measurement below rather than only the original `d=2` figure.
- `matlab/TDA4j.scala`, `cli/TDA4jConf.scala`: the `helix.ambientDimension != 2` gate relaxed to `< 2` (mirrors
  cubical's own `dispatchCubical` relaxation); doc comments updated to match.
- Docs: `CLAUDE.md` (Alpha complex section, MATLAB API section), `persistence-engines.md` (engine 7's own
  section), `alpha-complex.md`, `class-diagrams.md`, `user-guide/README.md` -- same set of surfaces the cubical
  entry touched, all had "ambient dimension 2 only"/"not yet started" framing to correct.

## Why this needed almost no new algorithm code (same finding as cubical, confirmed again here)

Re-read `FastAlphaHomologyContext`'s actual source before writing anything, exactly as the cubical entry did.
Both `computeH0` and `computeDualTopDimension` were ALREADY written generically in terms of
`helix.ambientDimension` (`bars += new PersistenceBar(ambientDim - 1, ...)`, the facet-to-top-id map built by a
generic pass over "every top simplex's own `ambientDim+1` facets") -- neither hardcodes `2`. The only thing
gating this engine to `d=2` was the single `require`. This is the SAME structural finding the cubical entry
made, now confirmed on a second, independently-written engine that happens to share the same author's own
"mirror the cubical engine term-for-term" design intent (`FastAlphaHomologyContext`'s own class doc already says
as much) -- not a coincidence, but not something to assume holds for a hypothetical THIRD such engine without
checking its own source the same way, either.

## The measurement this entry's own design note called for: alpha's facet-multiplicity rate at d=3

The design note flagged, before any alpha-specific code was written, that the `d=2` rate (~1-in-18700) must NOT
be assumed to carry over to `d=3`, since `HelixDelaunay`'s own SEPARATE near-cospherical limitation is already
documented to get worse with ambient dimension (`AlphaCrossValidationSpec`'s own doc: negligible at `d=2`/`d=5`
in that spec's sweep, but ~1-in-170 at `d=4` with 20-30 points -- notably non-monotone in `d`, which is exactly
why this needed direct measurement rather than extrapolation).

Measured directly via a throwaway script (`Test/runMain`, deleted after use, same methodology as the original
`d=2` measurement -- `Gen.double`-style random points): at `d=3` with `n∈[6,16]` points (matching the ORIGINAL
`d=2` methodology's own point-count range), **zero** facet-multiplicity violations occurred in 20000 trials
(and zero unrelated HelixDelaunay construction failures either) -- only an upper bound (~1-in-6700 at 95%
confidence via rule of three), not a point estimate, since none occurred. At `d=3` with `n∈[20,30]` points
(matching `AlphaCrossValidationSpec`'s OWN near-cospherical regime instead), 6 facet-multiplicity violations
occurred in 10000 trials (~1-in-1666), plus one unrelated HelixDelaunay construction failure -- confirming the
design note's own prediction: the risk is real and MEANINGFULLY WORSE at `d=3` than the `d=2` figure alone
would suggest, and it depends on point density, not just ambient dimension. This is a materially different risk
profile than what the project lead reviewed and signed off on for `d=2` (~1-in-18700) -- flagged explicitly
here and to the project lead directly, not glossed over, and reflected in the shipped exception message/docs
(which now report BOTH figures, dimension-labeled, rather than only the original `d=2` number).

## Tests added

Extended `FastAlphaHomologySpec` in place (mirroring `FastCubicalHomologySpec`'s own extension exactly):

- The old "requires ambient dimension 2" test (a legitimate 3D `HelixDelaunay`, previously expected to throw)
  is now obsolete -- 3D is a real, supported case. Unlike cubical's own analogous fix (which had a genuinely
  reachable `< 2` case via a 1-axis `CubicalGridStream`), attempting the SAME fix for alpha (a 1D point cloud)
  found that `HelixDelaunay` itself throws its own `ArrayIndexOutOfBoundsException` deep in
  `HelixDelaunayBuilder.compute`/`Hypersphere.apply` when constructing a 1-dimensional triangulation at all --
  a pre-existing `HelixDelaunay` limitation unrelated to and out of scope for this work. So this test was
  removed rather than rewritten to a broken assertion; `FastAlphaHomologyContext`'s own `require` is kept in
  the source (documents the real constraint, mirrors the cubical engine's parallel structure, costs nothing)
  but is currently unreachable via any `HelixDelaunay` the public constructor can actually produce -- recorded
  in the spec's own comment so a future reader doesn't wonder why there's no test for it.
- One hand-pinned 8-point 3D fixture (found by a short targeted search -- found on the FIRST attempt, unlike
  `d=2`'s own denser search -- since 3D Delaunay triangulations aren't practical to hand-verify the way a
  cubical grid's cell counts are) with a genuine nonzero-persistence `H_1` bar, matching the naive engine
  exactly; a genuine-cycle check across `H_1` (from chunks) and `H_2` (from the dual union-find); Fp(3)
  sign-genericity on the same fixture.
- A random 3D property test, using a DELIBERATELY smaller point-count range (5-8, vs the `d=2` generator's
  5-10) than either the naive `d=2` extrapolation or `AlphaCrossValidationSpec`'s own near-cospherical regime
  would suggest, specifically BECAUSE the newly-measured `d=3` facet-multiplicity rate grows sharply with point
  count -- a generator drawing from the `n∈[20,30]` regime would spend a large, wasteful fraction of its own
  trials just hitting the (correctly-classified, but uninformative) exception path instead of exercising the
  hybrid computation itself. Classifies `FastAlphaTriangulationException`/other `HelixDelaunay` failures exactly
  like the `d=2` property test already does; asserts naive-agreement and genuine cycles otherwise. Uses a new,
  separate `RandomPoints3D` case class + `Arbitrary` (not `Prop.forAll(gen) { ... }` directly) --
  `CubicalStreamSpec`'s own doc comment already documents that explicit-Gen overload colliding badly with
  ScalaCheck's other `forAll` overloads in this codebase; `given Arbitrary` + `prop {}` sidesteps it.
- `matlab.TDA4jSpec`'s own obsolete "reject engine=fast-alpha for a 3D point cloud" test was fixed the same way
  cubical's analogous obsolete test was: turned into a positive agreement test (reusing the SAME pinned 8-point
  fixture, for a single source of truth on what "a known-safe 3D alpha point cloud" means across both spec
  files) rather than deleted outright.

## Validation

Same discipline, not narrated twice: `sbt "Test/compile"`, `sbt "testOnly ...FastAlphaHomologySpec
...TDA4jSpec ...CLISpec"` (all green, obsolete tests fixed along the way -- same "one test broke, mirrors
cubical's own analogous fix" pattern), `sbt clean test` (full suite green), `sbt scalafmtCheck
scalafmtSbtCheck` (3 files needed `scalafmtAll`, confirmed pure re-wrapping via `git diff`, re-ran affected
specs after), `sbt mimaReportBinaryIssues` (no previous artifacts, pre-1.0), `sbt laikaSite` (clean, same
4 pre-existing unrelated scaladoc warnings as every other entry this session).

## What is explicitly NOT done in this entry

- **Cost/benefit A/B measurement**, same open item the cubical entry left -- not measured for alpha either.
- **Validation at `d >= 4`.** Cubical's own entry included one `d=4` smoke test on a trivial flat grid; this
  entry has none for alpha (a 4D `HelixDelaunay` construction is itself a slower, riskier thing to smoke-test
  given the ALREADY-elevated `d=3` failure rate found above, and this entry's own scope was "match cubical's
  `d=3` coverage," not "explore how far up `d` this can go" for alpha specifically).
- **Any change to the shipped `d=2` risk/decision.** The project lead's own sign-off on `d=2`'s ~1-in-18700 rate
  stands unchanged; only `d=3`'s own, separately-measured and separately-reported rate is new here.
