# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. Detail that matters for one subsystem only is in `.claude/rules/` (see "Subsystem notes"). This file was condensed on 2026-09-22 from a ~190k-char version (commit `06a55dd`),
2026-09-25 from a ~75k-char version (commit `b8739a8`), and 2026-09-26 from a ~56k-char version (commit
`e5e86ec`) — `git show <commit>:.claude/CLAUDE.md` for any of those full texts.

## What this is

TDA4j is a Scala 3 library for persistent homology and topological data analysis (a spiritual successor to
JavaPlex/Ripser, from the Stanford Computational Topology workgroup lineage). Single sbt module, root package
`org.appliedtopology.tda4j`, pre-1.0 (`0.5.0-SNAPSHOT`, see `version.sbt`), actively evolving API. **0.5.0 deliberately does not keep binary
compatibility with 0.4.x** (project lead: still in flux) — no compat shims for renames/signature changes.

## Package layout

Source/test directories mirror package names; file names mostly carry over from the old flat layout
(`WORKLOG-package-reorg.md`), with a few later renames/moves to fix a file's content drifting from its name
(`RipserStream.scala` → `SimplexIndexing.scala`; `CubicalHomologyEngine` moved from `streams` to `homology`).

- `algebra` — `RingModule`, `Field`, `FiniteField`, `Chain` (plus the `Cell`/`OrderedCell`/`OrderedBasis`
  contracts), `SSetElement` (degeneracy words + `insertOuter`/`faceOf`).
- `cells` — `Simplex`/`SimplexOps`/`SimplexOrderedCell`, `Cubical`/`CubicalOrderedCell`, `SimplicialSet`
  (`FiniteSimplicialSet`, with `.product`/`.coproduct`/`.quotient`/`.identify` on its companion object, also in
  `SimplicialSet.scala`), `SimplicialSetConstructions` (shared ordering helpers those draw on).
- `streams` — `SimplexStream`, `FiniteMetricSpace`, `VietorisRips` (also the `streams.VietorisRips(...)` dispatcher: homological-degree `maxDimension`, pick this over the individual constructions; `DESIGN-stream-naming.md`), `Cofacets`, `SimplexIndexing`, `CubicalStream`,
  `CubicalImage`, `UnionFind` (also defines `Kruskal`, which is metric-space-specific — hence
  here, and why no `util` package exists), `SimplicialSetStream`, `FilteredSimplicialSetStream`, `CechStream`.
- `homology` — `Homology` (`CellularHomologyEngine` naive + `CellularPersistenceInChunksEngine` chunks, plus the thin
  `Simplicial`/`Cubical`/`PersistenceInChunks` wrappers), `RipserCohomology` (`RipserCohomologyEngine`, the oracle),
  `PackedRipserCohomology`, `Cohomology` (`CellularCohomologyEngine`), `FastCubicalHomology`, `FastAlphaHomology`,
  `PersistenceEngine` (one-shot dispatch trait), `CircularCoordinates`, `LatticeReduction`. The package graph is acyclic: `streams` never depends on `homology`.
- `groups` — `FiniteGroup`, `ClassifyingSpace` (nerve `BG` of a finite group as a truncated `FiniteSimplicialSet`, filtered by a
  subgroup chain = persistent group homology). **Library-only proof of concept**: depends on `cells`/`streams`/`homology`, nothing
  depends on it; no MATLAB/CLI/user docs. Cost is `(|G|-1)^n` cells: S₄ to H₂ ≈ 4 s, H₃ and S₅ H₂ take > 8 min
  (`DESIGN-persistent-group-cohomology.md`).
- `barcode` — `Barcode`, `PersistenceFilter`. `alpha` — `AlphaShapes`, `AlphaComplexDQP`. `unicode` — `PrintingHelper` (unused).
- `matlab` — MATLAB facade. `io` — `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus` (leaf package).
  `cli` — `TDA4jConf`, `TDA4jCLI` (thin translator over `matlab.TDA4j`/`io`).
- root — `package.scala` (`TDAlab`, the pylab-style user entry point, see "TDAlab" below); test side
  `APISpec.scala`/`ShowSpec.scala`, kept flat as cross-cutting tests.

Cross-package references use `import org.appliedtopology.tda4j.<pkg>.{given, *}` — **the `given` matters**: a plain
`import pkg.*` does NOT import `given` instances in Scala 3, and this codebase's `Ordering`/`RingModule`/`Field`
instances are all givens. Broad wildcard imports are deliberate (mirroring the old same-package visibility).

## Commands

Java 21, Scala 3.9.0 LTS (`WORKLOG-scala-3.9-lts-upgrade.md`). `.jvmopts` sets `-Xmx2G` — the old test-suite OOMs
were sbt's own 1024MB default, not code (`WORKLOG-test-suite-memory-and-benchmark-gating.md`).

```
sbt testFull                    # full test suite, every spec (plain `test` is incremental+disk-cached in sbt 2 and can run 0 specs; CI's fresh runner is fine)
sbt "testOnly *SimplexSpec"     # single specs2 spec (glob ok)
sbt scalafmtAll                 # format everything — run before committing
sbt "scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck"   # exactly what CI's lint job runs (check only; fix with `sbt scalafmtAll scalafmtSbt`)
sbt mimaReportBinaryIssues      # binary compat (CI `mima` job)
TDA4J_SCALA_VERSION=3.8.4 sbt doc   # docs site (_docs/ + sidebar.yml) via scaladoc -> target/out/jvm/scala-3.8.4/tda4j/api
sbt assembly                    # fat jar for CLI/MATLAB
sbt -DrunBenchmarks=true test   # also run benchmark/profiling specs — NOT what CI runs
```

If `sbt` isn't on `PATH` in this environment, see `.claude/scripts/install-sbt.sh` (bootstraps the launcher and
paces around Maven Central's cold-cache rate limiting — `.claude/WORKLOG-toroidal-coordinates.md`'s own
environment note has the story).

No linter beyond scalafmt. Tests are specs2 (`org.specs2.mutable.Specification`). CI: `test.yml` (three parallel jobs `test`, `docs-build`, `mima`),
`lint.yml` (scalafmt: build files, main and test sources), both on every PR to `scala` and cancelled when the PR is pushed again; `docs.yml` (scaladoc → GitHub Pages, push to `scala` only). MiMa's baseline is every earlier plain release of the same compatibility series (`mimaBaselineVersions` in `build.sbt`), so it compares against nothing while only `0.5.0-SNAPSHOT` exists. The ~319 `-Wunused:all` warnings
(mostly unused wildcard imports) are deliberately left alone (`WORKLOG-compiler-warnings.md`).

**`sbt scalafmtSbt`/`scalafmtSbtCheck` format the build definition** (`build.sbt`, `project/*.sbt`), and CI's lint job
runs the check: format `build.sbt` before pushing it.

**Docs are built with Scala 3.8.4, everything else with 3.9.0** (scaladoc 3.9.0's JavaScript is broken; this
includes the `ux.js` `$.get` navigation bug). The pin is the `TDA4J_SCALA_VERSION` env var read by `scalaVersion`
in `build.sbt`, set only on the docs steps of `test.yml` (`docs-build`), `docs.yml` and `release.yml` (not `++3.8.4`). sbt 2 puts output under
`target/out/jvm/scala-<ver>/tda4j/`. Remove the pin when 3.9.1 releases.

**Never run two `sbt` invocations against this checkout at once** — the incremental compiler's own class-file
writes from one process can be read mid-update by the other, producing a `NoClassDefFoundError` that looks like a
real regression but disappears on a clean, sequential rerun.

## TDAlab: the user-facing entry point

`TDAlab(characteristic, precision = 1e-9)` (root `package.scala`) is the pylab-style facade: `val tdalab =
TDAlab(17); import tdalab.{*, given}` brings in `Fp(...)`, chain arithmetic (`⊠`, `+`, `-`), `∆`/`Simplex`/`Cube`
literals, a `Simplex -> Chain` conversion and Cats `Show` syntax. The odd-looking
`given Show[Simplex[VertexT]] = summon[Show[Simplex[VertexT]]]` lines are **deliberate re-exports**: they make the
existing givens visible through `import tdalab.given` (an instance's `given` import only brings in givens defined or
exported as members of that instance). Don't "fix" them as self-referential. `characteristic = 0` means `Double`; a prime `p`
means `Z/p`; anything else throws `IllegalArgumentException`. Vertices are fixed to `Int`. **`TDAContext` and the
`TDAenvironment`/`FieldChoice`/`FiltrationChoice`/`TopologyChoice` sketches were removed on purpose** — engines are
constructed explicitly (`SimplicialHomologyEngine[Int, Double, Double]()`), not inherited from a context class.
Never consulted by an engine (generic-`given` capture, below). Growth direction: pylab-like ambition (a casual
user should rarely need more than `import tdalab.{*, given}`). Cats (`cats-core`, `kittens`) is a dependency for
`Show`; `Chain` is declared `into class` (needs `-preview`; `// format: off` around it because scalafmt can't
parse `into`) and implicit conversions are enabled in-source, not by a flag.

## Scala style used throughout

Uses Scala 3.7+'s newest context-abstraction syntax — don't "correct" it to older idioms:

- `Type is TypeClass` for context bounds/givens (`Chain[CellT, CoefficientT] is RingModule`).
- `type Self: Ordering as ordering` — named context-bound aliasing inside trait bodies.
- Unicode algebra operators: `⊠` (scalar action), `∆(...)` (simplex literal), `<*`, `|*|` (`RingModule.scala`/
  `Field.scala`).
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` / `opaque type Cube = Vector[Int]` — no runtime wrapper; API
  is extension methods.
- Prefer `Option` over sentinel values (e.g. `maxFiltrationValue: Option[Double] = None`). A default can't
  reference an earlier parameter in the *same* list (`-source:future`), and curried parameter lists would force
  `()` at every call site — `None` + `.getOrElse(...)` inside is the pattern.
- A method's own `[T: Ordering, C: Field]`-style context bounds desugar to a `using` clause appended AFTER every
  explicit parameter list — so a default value earlier in that same signature cannot reference the given that
  default itself needs. No workaround short of every caller passing the value explicitly, or restructuring the
  signature so the context bound is a `using` clause of its own, ahead of that parameter (`Chain.reduceByUntil`).

**Opaque-type extension methods** (`WORKLOG-extension-companion-objects.md`): extensions whose receiver is the
opaque type live in its companion (`object Simplex`/`object Cube`), so different opaque types can reuse names. Two
hazards: (1) opaque transparency is file-scoped, so same-file code calling the type's extensions by dot-syntax
breaks or silently hits the underlying type's member — hence `simplexIsOrderedCell`/`cubeIsOrderedCell` live in
separate files; (2) a companion extension can lose to a same-named stdlib extension from a wildcard import
(`math.Ordering.Implicits.*`'s `min`/`max`) — so `min`/`max` stay top-level. `asSimplex`/`asCube` are top-level
because their receiver is the raw `SortedSet`/`Vector`.

**Shared test generators** (`matrixGen`) live in `src/test/.../streams/Generators.scala`, not in a spec (a spec file got overwritten once and took it with it) — put any new cross-spec generator there. Before creating a test file, `ls` for its name: `Write` overwrites silently.

**specs2 gotcha**: in a class mixing `ScalaCheck`, give a `Seq[Simplex[_]]` an explicit type ascription before
`.forall` — otherwise it can resolve to specs2's `ValueCheck` extension with confusing errors.

## Naming convention: `tda4j`/`TDA4j`, never `Tda4j`

`tda4j` (lowercase) for package, artifact, executable, repo, prose; `TDA4j` for Scala identifiers and headings
(`TDA4j`, `TDA4jConf`, `TDA4jCLI`). `Tda4j` is never correct — a real, repeated drift; watch for it on any new class.
Same rule for every acronym identifier: `CSV`, `CLI` (not `Csv`, `Cli`). `Gudhi`/`Dipha`/`Ripser`/`Perseus` are
proper nouns (external projects' own spellings), correctly titlecased.

## Architecture

**Design principle (project lead, foundational — hold every engine/optimization against it, not just speed)**:
every homology implementation should (a) be generic over `Field` coefficients and (b) return representatives (a
real chain witnessing each bar). An optimization that abandons representatives is probably not worth it. Every
public interface (MATLAB facade included) should expose representatives; anywhere that doesn't is incomplete.
**Current gaps**: none known — every engine, including `PackedRipserCohomologyEngine`'s apparent-pairs shortcut,
records a representative for every bar.

### Algebraic core

- `RingModule`/`Field`: minimal typeclasses built with `is` syntax; everything downstream is generic over `Field`.
  `FiniteField`: `Fp` opaque type per prime `p`.
- `Chain[CellT, CoefficientT]`: formal sum backed by a `PriorityQueue` ordered by cell (leading term = cheap peek).
  Defines `reduceBy`/`reduceByUntil` (with an optional `fallback` for pivots needing on-the-fly substitution) and
  a `RingModule` instance. `reduceLoop` uses a `mutable.TreeMap` accumulator (use `z.head`, not `headOption`, which
  allocates an iterator on `mutable.TreeMap`). Known footgun, not fixed: `Chain` overrides `equals` without a
  matching `hashCode` — don't put `Chain`s in a `Set`/`Map` key.
- `Cell`/`OrderedCell`/`OrderedBasis`: anything with a `boundary` and a total order plugs into homology. Concrete
  instances: `Simplex`, `Cube`, `FiniteSimplicialSet` generators.
- `Cocell`/`OrderedCocell` were **removed on purpose**: coboundary is extrinsic (depends on the ambient complex),
  so a per-cell `coboundary` is the wrong shape. Don't reintroduce (`DESIGN-generic-cohomology.md`).
- **Generic-`given` capture gotcha**: a `given` like `chainRM` resolves its implicit `Ordering[CellT]` once, where
  it's summoned. Summoned at class scope (before the stream's filtration ordering exists) it silently pivots on
  lexicographic order. Summon it where the per-stream ordering is in scope (`WORKLOG-naive-homology.md`).
  `TDAlab`'s class-scope `chainIsRingModule` is user-arithmetic convenience only, never used by an engine.

### Streams and complexes (ordering contract and constructions: `rules/streams.md`)

**Public entry points** (`DESIGN-stream-naming.md`, `WORKLOG-stream-rename.md`): users build complexes through `VietorisRips`, `Cech`,
`Witness(variant = Lazy | General)`, `Dowker`, `DtmRips`, `SparseRips` and `Truncated` — each takes `maxDimension` as the top
HOMOLOGICAL degree and returns a `LevelwiseSimplexStream[Int, Double]` (the old `StratifiedSimplexStream`). The implementation classes
(`Enumerating...`, `Ripser...`, `Inorder...`, `Incremental...`, `RecursiveStack...`, `Cech...`, `LazyWitness...`,
`WitnessCoface...`, `DowkerCoface...`, `DtmRips...`, `SheehyRips...`, `LimitedCoface...`, `CofaceSimplexStream`) are
`private[tda4j]`: use them inside the library, tests and `matlab`, never in docs fences (the snippet compiler runs outside the
package, so a fence using one fails `sbt doc`). No `Cubical`/`Alpha` objects: `CubicalImage` and `AlphaShapes` already are the
dispatching entry points. Any new object must be tested against the hand-wrapped class cell for cell AND value for value
(`ComplexesSpec`) — Betti numbers would not catch a wrong `+1`.

## Subsystem notes (`.claude/rules/`)

Detail for one subsystem lives in a path-scoped rule file that loads when you read a file in its area. Read the matching
file before changing that subsystem; this table is the index, in case a rule did not load.

| file | covers | loads for |
|---|---|---|
| `rules/streams.md` | the ordering contract every stream must satisfy (the #1 historical bug source), VR constructions, `maxFiltrationValue` default, `ExplicitStreamBuilder` | `streams/` |
| `rules/engines.md` | the four persistence engines, `BarcodeDistance`/`Vectorization`, circular and toroidal coordinates, benchmark specs | `homology/`, `barcode/` |
| `rules/cubical.md` | cubical complexes, `FastCubicalHomologyEngine` | cubical files |
| `rules/simplicial-sets.md` | simplicial sets, the Sage-parity layer, group classifying spaces | `cells/`, `groups/` |
| `rules/filtered-complexes.md` | Cech, witness, Dowker, DTM, sparse Rips, edge collapse | those files |
| `rules/alpha.md` | alpha complexes (DQP, Helix, fast alpha) | `alpha/`, alpha files |
| `rules/facade.md` | MATLAB facade, CLI, file I/O, the persistence threshold (which bars are reported) | `matlab/`, `cli/`, `io/` |
| `rules/docs-and-tutorials.md` | docs site, tutorial pages (the docs are the tests), language tabs | `_docs/`, tutorial specs, `build.sbt` |

## Session practices

- **Write a `.claude/WORKLOG-<topic>.md` by default** for any substantial investigation, debugging, or
  profiling arc, without being asked. Worklogs are point-in-time snapshots, never retroactively edited. At the
  end of the arc, update this file, or the matching `.claude/rules/` file for a single subsystem, with **only the resulting
  rule/invariant/limitation plus a worklog pointer** — no narrative, measurements, or repros there. This file loads in
  every session: keep it under ~25k characters (and each rule file under ~12k); when one drifts past that, condense it
  the same way (strip narrative to worklog pointers, move single-subsystem detail into a rule file) and note the new
  condensing date/commit at top.
- **Never revert the formatter's output.** If `scalafmtAll` touches files outside your change, commit that in its OWN
  commit ("Format: ... formatter output only, no behavior change") and say so — reverting only hides the debt, and a
  clean lint beats a minimal diff. CI lint also runs `scalafmtSbtCheck` (`build.sbt`) and `Test / scalafmtCheck`, so run all three before pushing (a pushed `build.sbt` edit once failed lint for this).
- Performance claims need isolated A/B measurement (`git stash` A/B, median of trials, one engine per JVM);
  machine noise here often exceeds small effects — report unconfirmed effects as unconfirmed.
- **Finalizing a user-visible capability** (new complex, engine, or option) means checking four surfaces each
  session that lands a chunk of it: (1) `matlab.TDA4j` dispatch, (2) `cli.TDA4jCLI`/`TDA4jConf` (1:1 mirror),
  (3) `_docs/developers-guide/` (`persistence-engines.md`, `architecture.md`, `class-diagrams.md`),
  (4) `_docs/user-guide/`. Internal refactors and bug fixes with no new surface are exempt.
- A cloud session (working on its own `claude/...` branch) may commit and push its own work to that branch at
  will, without asking first — the branch is disposable/session-scoped, not shared history. A local/interactive
  session working directly on a shared branch still waits to be asked; the project lead commits that work.
- **Found a bug in someone else's paper or reference implementation while validating a tda4j feature against
  it?** Log it in `.claude/BUGS-IN-REFERENCES.md` (flat, never condensed away). State precisely what was verified
  — don't extrapolate an isolated bug into an end-to-end correctness claim without a repro that actually shows
  that (a real miss, corrected — `WORKLOG-toroidal-coordinates.md`). Two entries so far: CJS 2015 (Sheehy-Rips)
  and DREiMac's `_gram_schmidt` (toroidal coordinates).
- **This environment may start with no `sbt` and no dependency cache** — `.claude/scripts/install-sbt.sh`
  bootstraps it. Add its contents to the environment's own setup script (cloud environment menu → Edit → Setup
  script) so new sessions don't repeat the ~20-40 minutes this can take cold.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. This has been
well received repeatedly (mixed paper benchmarks, uncompiled deliverables, drifted oracles, bugs in existing code,
"fixes" that had to be reverted) — don't reflexively hedge findings like these.
