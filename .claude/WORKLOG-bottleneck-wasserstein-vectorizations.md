# Worklog: bottleneck/Wasserstein distance + diagram vectorizations (gap-analysis items 4 and 8)

2026-09-24, cloud session picking up `.claude/WORKLOG-mainstream-feature-gap-analysis.md` (written to survive the
local-to-cloud migration). Executes that worklog's item 1 ("Bottleneck/Wasserstein + vectorizations — fully
independent of each other and of everything else... Start here") in full, including the four-surface treatment.
Session transcript: `https://claude.ai/code/session_014m9j2FdkTDmh1PL2MA9rX6`.

## Environment note (reusable for future cloud sessions on this repo)

This container had no `sbt` at all. Installed `sbt-launch-1.12.11.jar` (matching `project/build.properties`)
directly from `repo1.maven.org` into `/opt/sbt-launcher/`, wrapped in `/usr/local/bin/sbt`. The launcher's default
resolver list includes `repo.typesafe.com`/`repo.scala-sbt.org`, both denied by this session's egress proxy (403);
fixed with a `/root/.sbt/repositories` override (`[repositories]\n  local\n  maven-central: .../maven2/`) plus
`-Dsbt.override.build.repos=true -Dsbt.repository.config=...` in the wrapper — Maven Central alone has everything
sbt/zinc/Laika/scalafmt need. Even Maven-Central-only, the shared egress IP hit real (not policy) `429`s from
Maven Central itself during the full plugin-graph bootstrap (Laika's PDF rendering pulls in a large transitive
graph: circe, cats, xmlgraphics-commons); plain retries made steady, monotonic progress (fewer failures each time,
via `~/.ivy2`/`~/.cache/coursier` caching) and fully succeeded within ~10 attempts. Once bootstrapped, ordinary
`sbt <task>` runs need no further network access and are fast. Also: this sbt version's non-interactive flag is
neither `-batch` nor `--batch` (both get mis-parsed as a bogus command by 1.12.11's CLI parser) — plain `sbt
<task>` already runs non-interactively when stdin isn't a TTY, which is all `run_in_background`/piped Bash needs.

**Discovered, not fixed (out of scope for this arc)**: `streams/SheehyRipsStream.scala`, `streams/
DtmRipsStream.scala`, `streams/DistanceToMeasure.scala` (plus their specs and `TDA4jSpec.scala`) currently fail
`sbt scalafmtCheck` on a clean `scala`-branch checkout — confirmed by running `scalafmtCheck` against a checkout
with none of this session's own changes present. Comment-line-wrap drift (their doc comments exceed `maxColumn`
under scalafmt 3.11.1's actual behavior), not a functional issue. Reverted scalafmt's own reformatting of these
files out of every commit this session made (`git checkout --` immediately after each `scalafmtAll` run) rather
than fixing them, since they're unrelated to this arc's own scope — flagged here so a future session doesn't
mistake this for new drift.

## What was built

**`barcode.BarcodeDistance`** (`BarcodeDistance.scala` + `BipartiteMatching.scala`): bottleneck and Wasserstein
distance between two single-dimension `PersistenceBar[Double, _]` diagrams, plus `...ByDimension` wrappers.

- Ground-metric (`GroundNorm.LInfinity`/`LP(p)`) and aggregation-`order` convention verified against Hera
  (`anigmetov/hera`) and GUDHI's own docs via WebSearch/WebFetch (gudhi.inria.fr and arxiv.org are both
  egress-blocked in this environment; cross-checked instead via search-result quotes from both projects, GitHub
  topic search results, and independently re-derived the diagonal-distance formula `pi * 2^(1/p - 1)` from plane
  geometry rather than trusting either source blindly) — matches the worklog's own instruction to verify this,
  not recall it.
- **Design improvement over the originating worklog's own sketch**: essential (never-dying) bars are split out
  and matched by sorted birth value *before* building the bipartite graph, rather than threaded through
  Hopcroft-Karp/Hungarian as `+Infinity`-cost edges. Sorted-pairing is provably cost-optimal for any Lp/max
  aggregation on the line (standard exchange argument), and this sidesteps an `Infinity - Infinity = NaN`
  landmine in Hungarian's potential-update arithmetic entirely, rather than guarding against it after the fact.
  A mismatched essential-bar count reports `Double.PositiveInfinity` directly (no finite matching exists) as an
  emergent consequence of this split, not a special case.
- `HopcroftKarp`/`Hungarian` (`BipartiteMatching.scala`, `private[barcode]`): textbook maximum-bipartite-matching
  and O(n^3) assignment-problem solvers, unit-tested directly against brute-force permutation search (not just
  exercised indirectly through `BarcodeDistance`).
- Oracles beyond the unit-level ones: brute-force cross-check (independent re-implementation, not calling into
  `BarcodeDistance`'s own privates) on small finite diagrams; `d_B <= d_W` property; symmetry; identity; a real
  VR-persistence stability test (perturb points, check `d_B <= 2*epsilon`).

**Two real bugs caught while building the stability test** (both in the *test*, not in `BarcodeDistance` — the
distance implementation itself was independently re-verified correct at every step via direct brute force on the
actual failing instances, never had to be changed):
1. `LimitedCofaceSimplexStream(..., k+1)` truncation leaves spurious `dim == k+1` bars in `barcodeAt`'s output
   (no `dim == k+2` cells exist to correctly pair them) — CLAUDE.md's own documented "drop `dim == k+1` bars"
   rule, confirmed the hard way: leaving them in gave the two compared clouds different essential-bar *counts*
   at the scaffolding dimension, correctly (if confusingly, until traced back) reported as `+Infinity`.
2. The classical stability bound (Cohen-Steiner-Edelsbrunner-Harer 2007) is `d_B <= 2*epsilon` where `epsilon`
   bounds each *point's* Euclidean (L2) displacement — an early version of the test generated per-*coordinate*
   deltas independently via `Gen.choose(-epsilon, epsilon)`, whose L-infinity box reaches up to `epsilon*sqrt(2)`
   in Euclidean norm. Diagnosed by dumping the actual failing instance and brute-forcing its bottleneck distance
   directly (matched `BarcodeDistance`'s own answer exactly, ruling out a matching bug) alongside each point's
   realized L2 displacement, which confirmed `2 * (max realized L2 displacement)` comfortably covered the
   measured distance while `2 * epsilon` (the coordinate-wise bound) did not. Fixed by clipping each point's raw
   delta vector to Euclidean norm `<= epsilon` rather than sampling coordinates independently — tests the
   theorem's actual precondition instead of a superficially similar, looser one. Also switched the test to
   `maxFiltrationValue = Some(Double.PositiveInfinity)`: the *default* enclosing-radius truncation is itself a
   function of the point cloud, so it shifts by up to `2*epsilon` between the base and perturbed clouds too,
   which is a real, separate confound on top of the theorem's own precondition (comparing two diagrams truncated
   at two different radii is not quite the same claim as the theorem's fixed-filtration one) — worth knowing if
   a future stability-style test is tempted to use the truncated default.

**`barcode.Vectorization`** (`Vectorization.scala`): persistence landscapes (Bubenik 2013) and persistence images
(Adams et al. 2017).

- Persistence images verified against `scikit-tda/persim`'s actual source (`raw.githubusercontent.com`, not
  egress-blocked) rather than memory: birth-persistence transform `(b,d) -> (b, d-b)`, isotropic-Gaussian
  surface, the specific piecewise-linear weight function, and — the one piece worth calling out — that real
  implementations integrate each pixel's mass *exactly* via a product of 1D normal-CDF differences (the
  isotropic Gaussian factors along both axes), not by sampling the surface at the pixel center. Implemented the
  same way, using `org.apache.commons.math3.special.Erf` (already vendored).
- Essential-bar policy differs between the two, deliberately, and is documented as a design decision rather than
  left implicit (per the originating worklog's own instruction): landscapes include essential bars for free (the
  tent function `max(0, min(t-birth, death-t))` degrades to the meaningful ramp `t-birth` at `death=Infinity`,
  no special-casing needed); persistence images drop them (a Gaussian centered at `(birth, Infinity)` has no
  overlap with any finite pixel grid — silently underflowing to zero would be an implicit policy, not a
  decided one).
- Landscape oracle: the closed-form identity `sum_k integral(level_k) = sum_i persistence_i^2 / 4` (exact
  integration is linear and order-statistics-summed-over-k equals summed-over-i at every fixed t) checked via
  fine-grid trapezoidal integration. Persistence image oracle: total mass over a wide grid equals the point's own
  weight (an exact check, since CDFs saturate to 0/1 many sigma out), plus an independent Riemann-sum
  cross-check against the raw (non-CDF-trick) Gaussian density.

## Four-surface treatment

- **MATLAB facade** (`matlab.PersistenceResult`): `bottleneckDistance`/`wassersteinDistance` (against another
  `PersistenceResult` — MATLAB's Java bridge already holds/passes those around, since `TDA4j.computeFrom*` itself
  returns one) and `landscape`/`persistenceImage`, all plain instance methods with required positional
  parameters — deliberately *not* routed through `TDA4j`'s flat `String[]` options map, since (unlike
  `computeFrom*`) none of these methods' parameters are optional/cross-cutting in the way that map exists for
  (matches the `coveringRadiusFromPoints`-style precedent, not `computeFromPoints`'s). The one genuinely optional
  parameter (`persistenceImage`'s weight cap) is a plain method overload, not a sentinel value — this codebase's
  own stated "prefer `Option` over sentinels" preference doesn't reach the MATLAB boundary (`Option` isn't
  marshalable there), but overloading achieves the same thing without inventing a new sentinel convention.
  `groundNorm`/`order` *do* reuse this facade's own existing sentinel convention (`Double.PositiveInfinity` means
  "unbounded"/"L-infinity", exactly like `maxFiltrationValue`'s own documented meaning) since that convention
  already exists here for exactly this reason.
- **CLI** (`cli.TDA4jCLI`/`TDA4jConf`): `--distance-to <file>` (+ `--distance-format`/`--distance-order`/
  `--distance-ground-norm`) mirrors `BarcodeDistance` only, reading the comparison diagram via the pre-existing
  `io.{CSV,Gudhi,Dipha}.readPersistenceDiagram`. **The two vectorizations are deliberately not mirrored on the
  CLI** — they produce a matrix, not a diagram, which doesn't fit this CLI's existing single-diagram
  text/csv/gudhi/dipha/perseus output model without real new plumbing (an output format for a matrix, file
  layout, etc.); documented as a deliberate scope boundary in `TDA4jConf.distanceTo`'s own doc rather than
  silently skipped or padded out with weak support.
- **Docs**: `developers-guide/architecture.md`'s existing `Barcode.scala` section extended; `class-diagrams.md`'s
  existing Mermaid diagram extended with both new objects; `user-guide/README.md` gets a new "Comparing diagrams
  and turning them into vectors" subsection (Java example) plus a one-line CLI mention. `sbt laikaSite` run to
  confirm the additions actually parse (an internal anchor link guess — `architecture.md#barcodescala-...` —
  turned out not to match Laika's real slug convention; fixed to a plain file link, matching every other
  cross-reference in this docs set, which turns out to never use fragment anchors at all).

## Verification

`sbt test`: 498 examples (488 passed, 10 skipped benchmarks, matching pre-existing skip count), 0 failures, run
clean 2x after the stability-test fix (plus the isolated `BarcodeDistanceSpec` run clean 5x in a row beforehand).
`sbt scalafmtCheck`: 0 failures among this session's own files (the 3 pre-existing-drift files noted above are
the only ones flagged, confirmed unrelated by reverting this session's own changes to zero and re-running).
`sbt laikaSite`: succeeds, HTML+PDF+EPUB all generated. `sbt mimaReportBinaryIssues`: no-op (`mimaPreviousArtifacts`
is empty pre-1.0, nothing to check).

## Status

Worklog item 1 (of the recommended execution order) is complete. Next: item 2, boundary-matrix export on
`PersistenceResult` (small, well-scoped, already-designed per that worklog's item 1).
