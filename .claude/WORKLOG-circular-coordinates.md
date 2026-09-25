# Worklog: circular coordinates via truncated-complex cohomology

2026-09-24, cloud session, continuing `.claude/WORKLOG-mainstream-feature-gap-analysis.md`. Executes that
worklog's item 3 (recommended execution order item 3 — "needs the birth-matching bookkeeping [item 2] worked
out, otherwise straightforward given the CG/threshold plumbing already exists"). Session transcript:
`https://claude.ai/code/session_014m9j2FdkTDmh1PL2MA9rX6`.

## What was built

`homology.CircularCoordinates` (de Silva-Morozov-Vejdemo-Johansson, "Persistent Cohomology and Circular
Coordinates," Discrete & Computational Geometry 45:737-759, 2011): `h1Bars(metricSpace, maxFiltrationValue)`
lists every persistent H¹ class's `(birth, death)` sorted by persistence descending; `compute(metricSpace, r,
cocycleIndex, prime, maxFiltrationValue)` returns `Result(theta: Map[Int, Double], birth, death, r, prime)` —
an angle in `[0, 1)` per ambient point index, present only for points in the connected component containing
the chosen class. `NoIntegerCocycleException extends RuntimeException` for the one real failure mode (no
integer lift at the chosen prime). MATLAB facade: `matlab.TDA4j.h1Bars(points): Array[Array[Double]]`,
`circularCoordinates(points, r[, cocycleIndex, prime]): CircularCoordinatesResult` (new
`matlab.CircularCoordinatesResult` class, `theta()`/`hasCoordinate(i)`/`birth()`/`death()`/`r()`/`prime()`,
`Double.NaN` sentinel for an absent coordinate — the facade's usual pattern for "missing," per `TDA4j.scala`'s
existing convention).

## The reframing (the actual content of this item, not just a literature port)

The originating worklog's own open question: does a *finite* H¹ bar's already-computed representative in
`CellularCohomologyContext` actually restrict to a nonzero cocycle on a sub-level complex `K_r` for `r` inside
`[birth, death)`? CLAUDE.md only guarantees this for *essential* bars. The user's fix, carried out here exactly
as specified: don't ask that question. Fix `r` up front, build the *static* truncated complex `K_r`
(`LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(metricSpace, maxFiltrationValue = Some(r)), 2)` —
the same `maxFiltrationValue` knob enclosing-radius truncation already uses, no new subcomplex-extraction code),
and compute `CellularCohomologyContext`'s persistent cohomology of *that fixed complex* directly. The target
class is essential there by construction — nothing survives past `r` in a view that stops at `r` — so the
verification question dissolves rather than needing an answer.

**The birth-matching bookkeeping, worked out concretely**: when several H¹ classes are alive simultaneously at
`r` (the common case), picking "the" one requires matching a `K_r`-essential class back to a specific
full-filtration bar. This needs only a birth-value equality check, not a more elaborate algorithm — confirmed,
not just assumed: `K_r`'s persistent cohomology is fed the *same* filtration values as the full computation,
just cut off at `r`, so it necessarily assigns every bar the same birth it has in the full computation
(truncating the *end* of a filtration cannot change how early something is born — the reduction algorithm's
own pivot selection at any birth time `b <= r` sees an identical column history in both computations up to
that time). `compute` implements this as `krEssentialH1.filter(b => endpointValue(b.lower) == birth)`, with two
`require`s that would fire on a genuine engine bug (zero matches — structurally impossible per the argument
above) or a genuine data tie (more than one match — reported as unresolvable, not silently picking one).

## Integer lift and the exact verification

Cohomology is computed over an odd prime field (`prime`, default `47`, not this library's usual `2` default —
an RP²-type class exists over `F_2` with no real/integer lift at all, so a mod-2 "cocycle" would be a mirage
for coordinatization specifically, even though `F_2` is fine for ordinary barcodes; `require`d odd and prime).
`FiniteField.Fp.toInt` gives the centered residue (confirmed by reading `FiniteField.scala` directly, same fact
already established in the boundary-matrix worklog) — the natural integer lift. The lift is then checked
**exactly**, not mod-`prime` (which the field computation already guarantees trivially and proves nothing about
the real integers): `verifyIntegerCocycle` sums each triangle's integer-coefficient boundary and requires it be
precisely `0`, throwing `NoIntegerCocycleException` naming the offending triangle otherwise. This is the
worklog's own explicit instruction ("`∂(ℤ-lift) = 0` must be a runtime check, not assumed") implemented
literally, not approximated.

## Harmonic smoothing

`min_g ||z - d0 g||^2` (`d0` = 0-coboundary, `(d0 g)(edge [i,j]) = g(j) - g(i)`) via the normal equations
`d0^T d0 g = d0^T z` — a sparse SPD least-squares solve. Solved matrix-free with
`org.apache.commons.math3.linear.ConjugateGradient` against a hand-built `RealLinearOperator` whose `operate`
applies the graph Laplacian directly via `Simplex.boundary[Double]` (no dense matrix ever materialized, no new
dependency — `commons-math3-3.6.1` is already vendored, confirmed present via `javap` on the actual jar before
writing any code against it, same discipline the boundary-matrix and DQP work already established). Restricted
to the connected component of `K_r`'s 1-skeleton containing the cocycle's own support (found by a plain BFS
from an arbitrary vertex in the cocycle's support) — a class has no meaningful coordinate outside a component
it has no path within, so other components get no `theta` entry at all, not a `0.0` or other sentinel default
(mirrors the same "genuinely absent, not a fake default" design choice `AlphaComplexDQP`'s vertex-attachment
fix already made for a different reason, per CLAUDE.md's alpha-complex section). One arbitrary vertex per
component is anchored at `g = 0` (dropped from the reduced linear system entirely, not merely constrained) to
make the reduced Laplacian genuinely positive *definite* — the unreduced graph Laplacian is singular on
constants, one null dimension per connected component; anchoring removes exactly that dimension. This was
chosen over disabling `ConjugateGradient`'s own positive-definiteness check, per the originating worklog's own
listed alternative — the reduced system needs no such override, and a genuinely non-PD operator would indicate
a real bug rather than an expected condition to suppress.

**The output coordinate is, directly, `theta(v) = frac(g(v))`** — no separate path-integration step. This is
less obvious from de Silva-Morozov-Vejdemo-Johansson's own more abstract framing than it looks once seen
written as code; cross-checked against a real reference implementation
(`scikit-tda/DREiMac`'s `toroidalcoords.py`, fetched and read directly, not recalled from memory, per this
codebase's own io-module verification ethos) to confirm the smoothed potential itself — not its gradient
integrated along some path, and not any further transform — is the coordinate.

## Verification

`CircularCoordinatesSpec`, 8 examples, all passing:
- **Clean circle** (n=16): recovers the true geometric angle, checked via circular mean resultant length
  (`sqrt(sumCos^2 + sumSin^2)/n` of `theta - trueAngle`, wraparound-safe unlike ordinary variance) against
  both possible orientations (a cohomology class alone can't know which way is "positive") — `> 0.99`.
- **Noisy, non-symmetric circle** (n=23, deliberately not a round number, ±2.5% radial noise): same check,
  `> 0.95`, restricted to whichever points ended up in the majority component — guards against a bug that
  only a tie-heavy, perfectly-symmetric fixture would hide (the same class of concern CLAUDE.md's own
  "Streams: the ordering contract" section flags generally).
- **Parameter validation**: `r` outside `[birth, death)`, non-prime or even `prime`, out-of-range
  `cocycleIndex` — each a distinct `IllegalArgumentException` with an actionable message, checked directly.
- **Cross-prime consistency**: `{47, 53, 101}` on the same `r` agree on every point's `theta` to within `1e-6`
  circular distance — a real cross-check (three separate field computations, three separate integer lifts,
  three separate CG solves), not just re-running the same code path.
- **Two disjoint circles, far apart** (offset by 1000 in both coordinates): `theta` covers exactly one
  circle's own contiguous ambient-index block, never a mix — the component-restriction logic actually
  restricting, not merely present.
- **Essential-representative cross-check** (the originating worklog's own suggested first correctness test,
  done as its own independent test rather than folded into `compute`'s internals): `K_r`'s essential H¹
  representative from `CellularCohomologyContext` is checked to have zero coboundary against `K_r`'s own
  triangles directly (`coboundaryOfChain(rep).isZero()`), and cross-checked for existence against
  `PackedRipserCohomologyContext` computed independently over the same `K_r` — two genuinely independent
  engines agreeing that an essential H¹ class exists there, not just one engine's internal consistency.

`matlab.CircularCoordinatesResultSpec`, 4 examples, all passing: `h1Bars` row count/sort order; the facade's
`circularCoordinates` matches `homology.CircularCoordinates.compute` called directly, point-for-point,
including which points get a coordinate at all; the two-argument overload matches the explicit-default
four-argument call; `NoIntegerCocycleException`'s superclass is `RuntimeException` (a MATLAB caller catches it
generically the same way it already catches `IllegalArgumentException`, confirmed by direct reflection rather
than assumed from the `extends` clause alone — cheap and removes any doubt about the exact bridge behavior).

Full suite: `sbt test` — 519 examples (509 passed, 10 skipped benchmarks), 0 failures, 0 errors, clean before
and after this item's work. `sbt scalafmtCheck`/`sbt laikaSite`: clean for every file this item touched; the
same three pre-existing scalafmt-drift files noted in the prior two worklogs
(`DistanceToMeasure.scala`/`DtmRipsStream.scala`/`SheehyRipsStream.scala` and the specs that happen to share a
`sbt scalafmtAll` invocation with them) reformatted and were reverted, confirmed still the only files
`scalafmtCheck` flags on a clean tree — unrelated to this item, same as before. No new scaladoc-link warnings
(this item's own `[[NoIntegerCocycleException]]` cross-reference is same-package, unlike the cross-package
`[[...]]` links the prior two items had to route around as plain text).

## Design decisions not spelled out in the originating worklog's own sketch

- **`h1Bars` as its own public entry point, not folded into `compute`**: a caller has no way to pick a
  meaningful `r` without first knowing a target bar's own range — this is the intended *first* call for both
  the direct Scala API and the MATLAB facade, not a diagnostic add-on. Mirrors exactly how the two-step
  witness-complex API (`selectLandmarksFrom*` then `computeFrom*AndLandmarks`) already splits "find out what
  your options are" from "commit to one."
- **Full computation and `K_r` computation share nothing but the metric space and dimension cap** — two
  separate `EnumeratingCofaceSimplexStream`/`LimitedCofaceSimplexStream` constructions, not one stream
  re-filtered. Simpler than threading a truncation-aware view through a single stream, and cheap: `K_r` is by
  construction no larger than the full stream's own `maxFiltrationValue`-truncated view.
- **`CircularCoordinatesResult` is its own MATLAB-facing class, not a repurposed `PersistenceResult`** — a
  per-point angle map is a genuinely different result *shape* from a barcode (no dimension/birth/death rows,
  no cycle representative lookup), so a new small class matches this facade's own established discipline
  (flat `double`/`int`/`String`/array fields only, `Double.NaN` for "missing" rather than a new sentinel
  convention) better than overloading `PersistenceResult` with fields that don't apply to it would.
- **No CLI mirror, decided and documented (not just omitted)**: matches the precedent already set by the
  vectorizations and boundary-matrix export (`architecture.md`'s "Barcode representation" section) — the
  natural output is a per-point array, not a diagram, and doesn't fit `TDA4jCLI`'s existing
  diagram-in-diagram-out shape any better than a landscape or boundary matrix does. A second, independent
  reason specific to this item: picking `r` is an inherently interactive, data-dependent two-step process
  (`h1Bars` then `compute`) that doesn't reduce to a single flag the way `--distance-to` does for
  `BarcodeDistance` (which needs only a file path).

## Status

Item 3 of the recommended execution order is complete: `homology.CircularCoordinates` (Scala API),
`matlab.TDA4j.h1Bars`/`circularCoordinates` + `matlab.CircularCoordinatesResult` (MATLAB facade), CLI
deliberately not mirrored (documented), `src/docs/developers-guide/architecture.md`/`class-diagrams.md` and
`src/docs/user-guide/README.md` updated, full suite green, scalafmt/Laika clean. Next per the recommended
execution order: item 4 (now item 5 in the live task list), flag-complex edge collapse.
