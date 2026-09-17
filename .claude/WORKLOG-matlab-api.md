# Worklog: MATLAB API entry point

Session date: 2026-09-16. Triggered by a long-standing `../TODO.md` note ("FUTURE NOTE - Michael Robinson to ask for
TDA Matlab api implementation... 'What can you envision using this library for?'") — this had never been started.
The build already had `sbt-assembly` wired in (`assembly / assemblyJarName` in `build.sbt`), but nothing
MATLAB-specific existed.

## Design decisions, made interactively rather than assumed

MATLAB's Java interop was always the intended mechanism (MATLAB ships its own JVM and can call plain Java classes
directly) — the actual design questions were about what the Java-facing surface should look like, since almost
everything distinctive about this codebase (opaque types, extension methods, `is`-syntax givens, unicode operators)
is invisible from plain Java/MATLAB. Resolved, in order:

1. **Scope**: a broad, string-dispatched facade (not just one VR engine) — "string based dispatch options so that
   adding options doesn't change call signatures."
2. **Integration style**: a bare Java facade + fat jar, no `.m` wrapper functions, no `.mltbx` packaging — minimal
   ceremony, matches a proof-of-concept/demo purpose.
3. **Input**: both a point cloud and a precomputed distance matrix, points implemented first.
4. **Output**: a rich, non-generic result object that trivially yields an N×3 `double[][]` (dim/birth/death), with
   room to grow into representative-cycle and boundary-matrix access without redesigning the public API.
5. **`java.util.Map<String,Object>` for options — explicitly rejected by the project lead** ("uses parametrized
   types, so it can't be used from Matlab"), followed by an explicit further constraint: static methods, and
   "stick with pretty much only `double[][]`, `string`, and a few other primitives and arrays." This settled the
   options mechanism as a flat, alternating `String[] key, value, key, value, ...` array — no generics anywhere in
   a public signature. (Whether `java.util.HashMap`+`.put`/`.get` genuinely fails from MATLAB was not independently
   re-litigated once the project lead stated it from experience — the flat-`String[]` design sidesteps the question
   entirely at negligible cost, so there was no reason to spend effort resolving the disagreement empirically.)
6. **Coefficient field default**: found, by grepping the existing test suite, that `RipserCohomologySpec`/
   `HomologySpec`/`EngineComparisonBenchmarkSpec`/`PersistenceInChunksSpec`/`VietorisRipsSpec` all default to
   `Field.DoubleApproximated` (`Double` coefficients), with `FiniteField`/`Fp` appearing only where a test
   specifically needs exact arithmetic to avoid epsilon-driven zero-detection issues. Initially proposed matching
   that existing convention (`field="R"` default). **Overridden by the project lead**: "The research field standard
   is finite fields. I'd rather recast the test suite than break tradition that hard" — so the facade defaults to
   `field="Z"` (mod-2), with `"R"` available as an explicit option. This is a deliberate, known divergence from what
   the existing library test suite itself defaults to; recasting those existing specs' own defaults to match is a
   separate, not-yet-scheduled task, not done in this session.

## Spikes run before writing the facade (on advisor's recommendation)

Four cheap checks before committing to an implementation, since a wrong assumption here would invalidate any
amount of API design:

- **`sbt assembly` actually builds a jar.** Succeeded cleanly, 46s, no `assemblyMergeStrategy` needed despite a
  varied dependency set (scalaz, kiama, commons-math3, a 2.13-cross-compiled graph-core, scala-parallel-collections)
  — the predicted merge-conflict risk didn't materialize. `../target/scala-3.8.4/TDA4j-0.1.3-SNAPSHOT-assembly.jar`.
- **MATLAB's bundled JVM version — attempted, not resolved.** `matlab -batch "disp(version('-java'))"` against the
  locally installed MATLAB R2024b was left running for ~10 minutes with zero output (normal MATLAB startup is
  10-30s) before being killed. A fallback attempt to read the bundled JRE's version directly off disk
  (`<MATLAB.app>/sys/java/jre/.../release`) also came up empty — R2024b's bundle layout doesn't put a JRE where
  older-version documentation suggests. **This remains an open, unverified item**: if MATLAB's JVM is older than
  whatever this project compiles to (no `-release`/`-target` is set in `../build.sbt`; classes come out at the
  compiling JDK's own level), every class in the assembly jar fails to load with `UnsupportedClassVersionError`,
  and no amount of API design helps. The reasonable read is that MATLAB's headless `-batch` mode hit some
  license/activation/telemetry check that can't complete without a display or prior interactive run in this
  sandboxed environment — not something further CLI attempts from here are likely to resolve. **Next step for the
  project lead**: run `version -java` inside an actual interactive MATLAB session and report back; only then does
  it make sense to consider adding `scalacOptions += "-release:N"`.
- **Static method visibility, verified with `javap -public`, not assumed.** Confirmed `Tda4j.class` (a Scala
  `object`, no colliding companion `class`) gets real Java static forwarders — `computeFromPoints`/
  `computeFromDistanceMatrix`, each in a no-options and an `Array[String] options` overload, both non-generic.
  `PersistenceResult.class` likewise exposes only plain instance methods (`int size()`, `double[][] toArray()`,
  `int dimension(int)`, `double birth(int)`, `double death(int)`, `int[][] cycleVertices(int)`,
  `double[] cycleCoefficients(int)`) — no `Function1`/generic type ever appears in a *public* method signature (it
  does appear in `PersistenceResult`'s constructor parameter list, which is irrelevant since MATLAB never
  constructs one directly — only ever receives instances back from `Tda4j`'s static methods).
- **What no Scala-side check can cover, and isn't claimed to**: whether MATLAB's Java bridge actually marshals
  `double[][]` (2-D matrix → `double[][]`, not just the more reliable 1-D `double[]` case) and `String[]`
  (cellstr → `String[]`) the way this design assumes. Genuinely unverified from this environment — see "What's
  NOT verified" below.

## The facade

`org.appliedtopology.tda4j.matlab` (`Tda4j.scala`, `PersistenceResult.scala`), fixed to `VertexT=Int`,
`FiltrationT=Double` throughout (matches how every metric-space-driven engine in this codebase already works).

- `Tda4j.computeFromPoints(double[][] points[, String[] options])` — `EuclideanMetricSpace`, the only entry point
  that supports `complex=alpha` (alpha complexes need real coordinates, not just distances).
- `Tda4j.computeFromDistanceMatrix(double[][] distances[, String[] options])` — `ExplicitMetricSpace`, VR only.
- Options, flat alternating `String[]` (`{"engine","naive","maxDimension","3"}`), parsed with fail-fast
  `IllegalArgumentException` on an odd-length array or an unrecognized key (a typo should fail loudly, not
  silently fall back to a default and produce a quietly-wrong barcode): `complex` (`vr`/`alpha`), `engine`
  (`ripser`/`naive`/`chunks`), `alphaBackend` (`helix`/`DQP`), `maxDimension`, `maxFiltrationValue`, `field`
  (`Z`/`R`), `prime`, `epsilon`.
- Coefficient-field dispatch is two concrete calls into one shared private generic `computeGeneric[C: Field](...)`
  — `field="Z"` builds `new FiniteField(prime)` and imports its `given ff.Fp is Field`, `field="R"` builds
  `Field.DoubleApproximated(epsilon)` — rather than any existential-type gymnastics.
- **Capability matrix deliberately pruned, not exposed whole**: `complex=alpha` refuses `engine=ripser`
  (`RipserCohomologyContext` only ever consumes a `FiniteMetricSpace[Int]`, has no notion of an alpha complex at
  all) and refuses `engine=chunks` (the exact combination `HomologySpec`'s `BarcodeRegressionSpec` stays
  `skipAll`'d for — a documented stall/OOM risk, and a JVM OOM taking down a MATLAB session mid-demo is a bad
  failure mode to hand a collaborator). Both refusals throw immediately with an explanation, not a hang.
- **`PersistenceResult.cycleVertices`/`cycleCoefficients` are honest about what's actually available**: `engine=
  chunks` throws `UnsupportedOperationException` unconditionally (`PersistenceInChunksContext.diagramAt` discards
  its own stored `Chain`s — there is genuinely nothing to return, not a bug to fix here), and even `engine=ripser`
  can throw per-bar (`annotation = None` when a bar was resolved via the apparent-pairs shortcut, which
  legitimately never writes a `basis(tau)` entry). Chosen over silently returning an empty array specifically so a
  MATLAB user sees a clear error instead of misreading "empty" as "no cycle exists."

## A real bug found by the facade's own cross-validation, not a pre-existing one

The verification spec (`Tda4jSpec.scala`) checks, among other things, that `engine=ripser` and `engine=naive` agree
through the facade on a fixed 6-point cloud (the same kind of cross-validation `RipserCohomologySpec` already does
directly against the engines, re-run here specifically to prove the *facade's conversion layer* doesn't scramble
anything — see advisor's framing below). First run disagreed: ripser reported an essential H₂ bar
`(2, 1.4142..., ∞)`; naive reported the same bar as zero-length and finite, `(2, 1.4142..., 1.4142...)`.

Root cause was in the new facade code, not either engine: `EnumeratingCofaceSimplexStream` has a filtration-value
threshold parameter but **no dimension cap of its own at all** — its `iterateDimension` catch-all case is only
bounded by `d < metricSpace.size` (see CLAUDE.md's own note on this). The `engine=naive` branch built this stream
directly and handed it to `SimplicialHomologyContext.persistentHomology`, which just consumes `stream.iterator`
with no dimension enforcement of its own either — so despite `maxDimension` defaulting to 2, the naive path was
silently computing through however many higher-dimensional simplices a 6-point cloud allows, and a 3-simplex at
the same tied filtration value ended up killing the H₂ class ripser (correctly, via `RipserCohomologyContext`'s
own `maxDimension`-truncated `coboundaryOf`) reports as essential.

First fix: wrap the stream in `LimitedCofaceSimplexStream(rawStream, maxDimension)` before handing it to
`SimplicialHomologyContext` — exactly the wrapping `RipserCohomologySpec`'s own `naiveBars` test helper already
uses, which this facade code should have matched from the start. `engine=chunks` did not need the same fix:
`PersistenceInChunksContext` walks `0.to(maxDim)` explicitly itself and never asks the stream for anything beyond
that, regardless of the stream's own natural bound (confirmed by reading `Homology.scala`, not assumed). This alone
made all 11 examples in `Tda4jSpec` pass at the time — but see below, this made the two engines *agree*, not
*correct*.

This is worth recording as the concrete payoff of advisor's specific suggestion to "re-run the engine-level
cross-validation through the facade" rather than treating a clean compile as sufficient — a hand-written
conversion layer is exactly the kind of code that can silently drop a parameter (`maxDimension` was accepted,
stored, and threaded to two of three engines correctly, and simply never reached the third) without any type
error, since Scala had no way to know `EnumeratingCofaceSimplexStream`'s missing dimension cap was a problem at
all.

## The deeper issue: the bug above was really a well-known truncation artifact, not just a naive/ripser mismatch

Making `engine=naive` and `engine=ripser` agree was necessary but not sufficient — the project lead pointed out,
after reviewing the first fix, that what both engines were agreeing on was itself wrong: **computing H_k correctly
requires (k+1)-dimensional chains** (H_k = ker(∂_k)/im(∂_{k+1}) -- with no (k+1)-chains built at all, there is no
way to distinguish a genuine k-cycle from one that a not-yet-built (k+1)-simplex would have killed). Building only
to `maxDimension` and reporting its own top dimension -- which is exactly what the first version of this facade
did, consistently, across both VR engines -- makes every top-dimension class look essential *by construction*,
regardless of whether it actually is. This is a well-known, general property of any truncated chain complex, not
specific to this codebase: "the top dimension of a truncated complex is junk," in the project lead's words. The
correct fix is not to make the artifact consistent across engines (which is what the first pass did) but to
recognize the top dimension as outside the range of information the truncation can support at all, and not report
it.

Fixed properly: for `complex=vr`, the facade now builds to `requestedMaxDimension + 1` internally (passed as the
actual `maxDimension` parameter to whichever underlying engine/stream is in play -- including the
`LimitedCofaceSimplexStream` wrapping from the first fix above, now wrapping at `requestedMaxDimension + 1` rather
than `requestedMaxDimension`) and filters the returned bars to `dim <= requestedMaxDimension` before constructing
`PersistenceResult` -- the newly-built extra top dimension exists purely as scaffolding to correctly resolve the
requested top dimension, and is itself now subject to the identical artifact one level up, so it's dropped rather
than reported. `"maxDimension"` as a public option name is kept (it's the intuitive reading for a MATLAB user
asking for "H_0 through H_2" in the first place), but its *internal* handling changed from "highest simplex
dimension to build" to "highest homological degree to report, building one dimension higher than that."
`complex=alpha` needed no change and gets none: an alpha complex's own chain complex terminates naturally (bounded
by ambient dimension, or higher under cosphericity -- see CLAUDE.md's degeneracy-hazard note), it is never
artificially cut short by this option to begin with, so its own top dimension is genuine information, not
scaffolding -- confirmed by re-reading `AlphaShapeDQP`/`HelixDelaunay`'s own always-untruncated convention, not
just asserted.

Pinned as a discriminating regression, not just re-verified for agreement: `Tda4jSpec` now also builds
`RipserCohomologyContext` directly at the OLD, un-corrected `maxDimension=2` (no extra dimension, no drop) and
asserts its output does NOT match the facade's actual (corrected) output on the same cloud -- proving this was a
real, visible behavior change on this fixture, not merely an internal refactor that happened to keep passing the
existing checks. All 12 examples in `Tda4jSpec` pass after this fix (up from 11 -- the new discriminating test is
additional, not a replacement).

## Verification

`Tda4jSpec.scala` (`../src/test/scala/org/appliedtopology/tda4j/matlab`), 12 examples, all passing:

- `computeFromPoints` (default options) matches `RipserCohomologyContext[Fp(2)]` driven directly one dimension
  higher with the top dimension dropped — catches endpoint/Inf/dimension conversion bugs in `PersistenceResult`'s
  construction, using the corrected (not the original) construction as its reference.
- The top-dimension truncation-artifact fix demonstrably changes output on this cloud relative to the original,
  un-corrected `RipserCohomologyContext[Fp(2)]` construction (built at `maxDimension` directly, no extra
  dimension, no drop) — a discriminating regression, not just "still agrees with itself."
- `engine=naive` agrees with the default `engine=ripser`, both through the facade.
- `computeFromDistanceMatrix` on the cloud's own Euclidean distances agrees with `computeFromPoints` on the same
  cloud.
- `field=R` agrees with the default `field=Z` up to floating-point tolerance.
- Five option-parsing error paths (odd-length array, unrecognized key, `alpha`+`ripser`, `alpha`+`chunks`,
  `alpha` via `computeFromDistanceMatrix`) all throw `IllegalArgumentException` as designed.
- Representative-chain access works for at least one `engine=ripser` bar (vertex/coefficient array lengths match)
  and explicitly throws `UnsupportedOperationException` for `engine=chunks`.

**`sbt test` ran clean**: `163 examples, 0 failure, 0 error, 159 passed, 4 skipped, 1 pending` in 80 seconds. The
first full run (before this last item below) got stuck for 15+ minutes deep in `EngineComparisonBenchmarkSpec`'s
timeout-heavy sweep at large `n`, and, being a single `sbt` invocation, was holding the project-wide sbt lock file
the whole time -- blocking a second `sbt` invocation needed to verify the dimension fix from even starting. Killed
it and added `skipAll` to `EngineComparisonBenchmarkSpec` (with a comment explaining why and how to re-enable it
deliberately) rather than keep working around a 15-minute-plus `sbt test` on every iteration -- it's a timing
benchmark that asserts nothing, so skipping it costs nothing on CI's actual pass/fail signal, and its own doc
comment's claim of "stays cheap under plain sbt test/CI" evidently no longer holds. The 4 skips are the 3 already
accounted for elsewhere in this codebase (see CLAUDE.md) plus this new one; the 1 pending is
`HomologySpec.scala`'s `BarcodeRegressionSpec`, unchanged, unrelated to this session.

## What's NOT verified — stated plainly, not implied

- **MATLAB's own Java marshalling of `double[][]` and `String[]` across the bridge.** Nothing in this session
  exercises actual MATLAB — the local MATLAB R2024b install could not be driven headlessly from this sandboxed
  environment (see the JVM-version spike above). A short illustrative `.m` smoke script is included
  (`matlab-smoke-test.m`) but has not been run.
- **MATLAB's bundled JVM version relative to this project's compiled bytecode level.** Open until the project lead
  runs `version -java` in an actual MATLAB session.
- Whether `java.util.HashMap`+`.put`/`.get` genuinely fails from MATLAB (the reason the options API avoids `Map`
  entirely) was taken on the project lead's stated experience, not independently reproduced.

## Deliberately out of scope for this pass

- Boundary-matrix export (`PersistenceResult` was designed so this is a second closure alongside the existing
  cycle-provider closure, not a redesign, when it's wanted).
- `.m` wrapper functions / `.mltbx` packaging (the project lead explicitly chose the bare-facade route for now).
- Recasting the existing library test suite's own `Field` default from `Double` to a finite field to match the
  now-facade-default convention — a real, separate piece of work the project lead flagged as acceptable to take on,
  not done here.
- Emergent pairs, `PersistenceInChunksContext` cycle recording, `SimplicialHomologyByDimensionContext` exposure —
  all pre-existing library-level gaps/limitations this facade correctly declines to paper over (see CLAUDE.md).
