# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. Detail that matters for one subsystem only is in `.claude/rules/` (see "Subsystem notes"). Last condensed 2026-10-03; earlier, longer versions: `git show e5e86ec:.claude/CLAUDE.md` (and the commits named there).

## What this is

TDA4j is a Scala 3 library for persistent homology and topological data analysis (a spiritual successor to
JavaPlex/Ripser, from the Stanford Computational Topology workgroup lineage). Single sbt module, root package
`org.appliedtopology.tda4j`, pre-1.0 (`0.5.0-SNAPSHOT`, see `version.sbt`), actively evolving API. **0.5.0 deliberately does not keep binary
compatibility with 0.4.x** (project lead: still in flux) — no compat shims for renames/signature changes.

## Working stance

- Think as an algebraic topologist who ships code: for any change to a construction or engine, name the invariant
  it preserves (∂∂ = 0, filtration monotonicity, pairing, representative validity) and test *that*, not only Betti numbers.
- Scala 3.9.0 LTS with `-source:future`, `-language:experimental.modularity`, `-preview`: use `is`-typeclasses,
  deferred givens and opaque types where they buy a real type distinction; flag anything needing more flags.
- Functional and type-driven, but performance wins in inner reduction loops: a type-level encoding that allocates
  there has to earn its place by an A/B measurement (see Session practices).
- Tests first: write the *discriminating* spec — one the plausible wrong answer fails (F₂ vs F₃, cup products,
  cell-for-cell stream comparison) — before the implementation.
- Easy over simple (Li Haoyi): judge an API by the first five lines a newcomer writes, with the docs snippets as the
  measure: one import, sensible defaults, errors that say what to do; keep the machinery reachable, not mandatory.

## Package layout

**One flat core package plus one add-on** (`DESIGN-package-structure.md`, derivation `WORKLOG-package-flatten.md`;
the 2026-09 subpackage split it replaced is `WORKLOG-package-reorg.md`). Everything a persistent-homology user needs is
in `org.appliedtopology.tda4j` itself, so users write `import org.appliedtopology.tda4j.*` (no `given` selector needed,
see "Givens" below). The core's source directories are **file organization only, not packages**:

- `algebra/` — `RingModule`, `Field`, `FiniteField`, `Chain`, the `Cell`/`OrderedCell`/`OrderedBasis` contracts,
  `LinearAlgebra` (dense, small complexes).
- `cells/` — `Simplex`/`SimplexOps`/`SimplexOrderedCell` (+ `SimplexInstances`), `Cubical`/`CubicalOrderedCell`
  (+ `CubeInstances`).
- `streams/` — streams, metric spaces, `PointCloud`, the dispatchers (`VietorisRips`, `Cech`, `Witness`, `Dowker`,
  `DtmRips`, `SparseRips`, `Truncated`; `DESIGN-stream-naming.md`), cubical streams/images, `UnionFind`/`Kruskal`.
- `homology/` — the engines (naive `CellularHomologyEngine`, chunks, cohomology, Ripser, fast cubical/alpha),
  `Persistence` (the verb), `CircularCoordinates`, `LatticeReduction`. Streams never use engines (convention only).
- `barcode/` — `Barcode`, `PersistenceDiagram`, `PersistenceFilter`, distances, vectorizations. `alpha/` — `AlphaShapes`
  (+ `AlphaBackend`), `AlphaComplexDQP`. `io/` — `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus`.
- root `package.scala` — `TDAlab` (below).
- **add-on `sset`** (`org.appliedtopology.tda4j.sset`, directory `sset/`; users opt in with
  `import org.appliedtopology.tda4j.sset.*`) — simplicial sets (the Sage-parity layer) AND group classifying spaces
  (`FiniteGroup`, `ClassifyingSpace`: nerve `BG` filtered by a subgroup chain = persistent group homology; library-only,
  no MATLAB/CLI; cost `(|G|-1)^n` cells, S₄ to H₂ ≈ 4 s, `DESIGN-persistent-group-cohomology.md`), `BettiNumbers`,
  `SimplicialSetStream`/`FilteredSimplicialSetStream`. Depends on the core; nothing in the core uses it except `TDAlab`'s
  generated re-exports. Detail: `rules/simplicial-sets.md`.
- subpackages `matlab` (MATLAB facade) and `cli` (`TDA4jConf`/`TDA4jCLI`, thin translator over `matlab.TDA4j`) — leaves.
- Tests mirror this. `src/test/scala/tda4juser/` is deliberately OUTSIDE the package: it checks what a user's code
  sees (`UserImportsSpec`, `TDAlabAloneSpec`); the `tutorial` specs and generated page scripts are INSIDE it, so only
  `sbt doc` checks that doc fences resolve from outside.

Every file in a subpackage (`sset`, `matlab`, `cli`, `tutorial`) starts with an explicit `import
org.appliedtopology.tda4j.*`, not just the chained `package` clause. **Flat-package name-binding hazard**: a wildcard
import of an external library (`import cats.syntax.all.*`) silently BEATS a same-named definition from another file of
this package -- no error, no warning (under subpackages it was a loud ambiguity). Prefer importing external libraries by
name in core files; when adding a top-level name to the core, re-run the audit (inject `import
org.appliedtopology.tda4j.*` next to each file-level external wildcard and compile -- ambiguity errors are the hits;
`WORKLOG-package-flatten.md`).

### Givens: the rule the flat layout depends on

**The core package has no top-level givens.** Default instances live in the companion of the DATA type they serve
(`Simplex`, `Cube`, `Chain`, `BarcodeEndpoint`, `Fp`, generator enums) -- implicit scope: found with no import, consulted
only when nothing lexical matches, so a user's or a stream's given always wins and never ties. Never put an instance in
a TYPECLASS companion (`Field`, `OrderedCell`, `RingModule`): that companion is searched for `?T is Field` with `T`
still unknown, so a lone instance there silently decides type inference (a default `Double is Field` would turn a
forgotten `F_p` import into real coefficients and wrong torsion answers). A top-level generic given is worse still:
visible everywhere in the flat package, `[CellT: OrderedCell] => Ordering[CellT]` made `SimplicialHomologyEngine()`
infer `VertexT = BarcodeEndpoint[Cube]`. Opt-in derivations are named givens imported by name: `import
OrderedCell.cellOrdering` (generic code holding only `CellT: OrderedCell`), `Field.showFromField`. `UserImportsSpec`
pins this from the user's side.

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

**After `sbt package` or a 3.8.4 docs build, a test compile can see no main classes at all** ("Not found: TDAlab");
`sbt clean` fixes it -- stale incremental state, not code.

**Never run two `sbt` invocations against this checkout at once** — the incremental compiler's own class-file
writes from one process can be read mid-update by the other, producing a `NoClassDefFoundError` that looks like a
real regression but disappears on a clean, sequential rerun.

## User-facing entry points: `Persistence`, labs, the cursor

**Every user file needs `import scala.language.experimental.modularity`** (or `-experimental`): the library is
compiled with that flag, so every definition in it is `@experimental` and Scala refuses to let non-experimental code
use it. Removing the flag is not cheap -- the `Self`-member typeclass context bounds (`C: Field`) are the experimental
part (~100 errors without it). With that one line, a plain downstream project needs nothing else (no `-preview`: the
`into` conversions work), checked against the packaged jar (`WORKLOG-cursor-and-verb.md`). Doc fences must include the
line even though the docs build (project flags) would compile them without it.

**`Persistence(input, maxDimension = 1, maxFiltrationValue, complex = VietorisRips, characteristic = 17, engine)`**
(`homology/Persistence.scala`) is the one-call verb: points/metric space/`Image`/any stream in, an immutable
`PersistenceDiagram` (bars + representatives; coefficient type is a member, `import d.given`; `dim`, `at(f)`,
`longest`, `longerThan(x)`, `significant()`, `bettiNumbers`) out. `engine = Chunks | Naive | Cohomology | Ripser` (Ripser
only for `VietorisRips` on points or a metric space).

**Zero-length bars are dropped by default** (project lead: seeing them is the opt-in, never hiding them): every engine,
the verb, MATLAB and the CLI take `includeZeroLength` (default `false`); short bars are one call away
(`longerThan(x)`, `significant()`, also on `List[PersistenceBar]` with no import). Tests must not use `#bars == #cells` as
an oracle unless they opt in; check the ordering contract or the actual barcode (`rules/facade.md`, `rules/streams.md`). `Input` is an `into` type, so one `apply` with defaults covers every
input (Scala forbids defaults on more than one overload). `VietorisRips`/`Cech`/`AlphaShapes` implement
`PointCloudComplex` and double as the `complex` choice. **Default field: `FiniteField.DefaultPrime = 17`** (project
lead: never F₂ by default -- it hides signs and odd torsion); also the MATLAB/CLI default. The verb runs to the end;
long runs use an engine's **cursor**, which is kept on purpose: `advanceFor(budget)`, `processedCells`/`totalCells`,
`diagramAt(f)` exact at any `f` wherever the cursor is, `snapshotAt(f)` (`rules/engines.md` query contract).
Engines also have inferring companion forms: `SimplicialHomologyEngine.persistentHomology(stream)`.

**`into` parameter types** (`Optional[Double]`, `PointCloud`): public numeric options take `2.0`, `2`, `Some(2.0)` or
`None`; point inputs take `Array[Array[Double]]`, `Seq[Seq[Double]]`, `Seq[Array[Double]]`. Use them for new public
signatures instead of `Option[Double]` / a fixed collection type. `AlphaBackend` (enum) replaced string dispatch.

### TDAlab and the other labs

`abstract class Lab(characteristic, precision = 1e-9)` (root `package.scala`) carries what every lab shares
(coefficients via `Coefficients`, `Fp`, the re-exports, `.show`); `TDAlab` (simplicial) and `CubicalLab` extend it,
with prebuilt objects `TDAlab.F2`/`F3`/`F17`/`Reals` (likewise `CubicalLab`): `import TDAlab.F17.{*, given}` must be the
ONLY library import a lab user needs. It brings `CoefficientT`, `Fp(...)`, the
field's given, chain arithmetic (`⊠`, `+`, `-`) on `Chain[Simplex[Int], CoefficientT]`, a `Simplex -> Chain` conversion,
Cats `.show` syntax, and flat re-exports of every public top-level class/trait/object/type/enum of the core and the
`sset` add-on, plus the `∆` val. The re-export block is GENERATED (`.claude/scripts/tdalab-exports.py`, between `BEGIN/END
generated re-exports` markers) and guarded by `TDAlabExportsSpec` -- rerun the script after adding a public type. Only
types and val aliases are re-exported (a re-exported def is ambiguous for users who import both). Hence `∆`
is `val ∆ : Simplex.type = Simplex`, and top-level defs have companion spellings that ride along with the re-exported
objects (`Simplex.fromSortedSet`/`ordering`/`isOrderedCell`, `Cube.fromVector`/`ordering`/`isOrderedCell`). No namespace objects (`tdalab.streams.X` is gone) and no given re-exports (defaults
come from companions). `characteristic = 0` means `Double`, a prime `p` `Z/p`. Labs are opinionated by design (project lead): `TDAlab` fixes
`Int` vertices. **`TDAContext`/`TDAenvironment`-style context classes were removed on purpose** -- a lab is never
consulted by an engine. Cats (`cats-core`,
`kittens`) is a dependency for `Show`; `Chain` is declared `into class` (needs `-preview`; `// format: off` around it
because scalafmt can't parse `into`) and implicit conversions are enabled in-source, not by a flag.

## Scala style used throughout

Uses Scala 3.7+'s newest context-abstraction syntax — don't "correct" it to older idioms:

- `Type is TypeClass` for context bounds/givens (`Chain[CellT, CoefficientT] is RingModule`).
- `type Self: Ordering as ordering` — named context-bound aliasing inside trait bodies.
- Unicode algebra operators: `⊠` (scalar action), `∆(...)` (simplex literal), `<*`, `|*|` (`RingModule.scala`/
  `Field.scala`).
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` / `opaque type Cube = Vector[Int]` — no runtime wrapper; API
  is extension methods.
- Optional parameters, never sentinels: `Optional[Double]` (below) for public ones, `None` + `.getOrElse(...)` inside
  (a default cannot reference an earlier parameter of the same list).
- A method's own `[T: Ordering, C: Field]`-style context bounds desugar to a `using` clause appended AFTER every
  explicit parameter list — so a default value earlier in that same signature cannot reference the given that
  default itself needs. No workaround short of every caller passing the value explicitly, or restructuring the
  signature so the context bound is a `using` clause of its own, ahead of that parameter (`Chain.reduceByUntil`).

**Opaque-type extension methods** live in the type's companion (`object Simplex`/`Cube`), found by implicit scope.
Hazards: opaque transparency is file-scoped (so `simplexIsOrderedCell`/`cubeIsOrderedCell` live in their own files), and
a wildcard-imported stdlib extension of the same name wins (so `min`/`max` stay top-level).
`WORKLOG-extension-companion-objects.md`.

**No top-level `object`/`class` with a non-ASCII name**: scaladoc writes one page FILE per such type (`∆$.html`), and a
JVM under a POSIX locale cannot encode it (`sbt doc` dies with `InvalidPathException`). `∆` is therefore `val ∆ :
Simplex.type = Simplex` (`WORKLOG-package-flatten.md`); unicode extension methods and vals are fine.

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
  it's summoned. Summoned at class scope (before the stream's filtration ordering exists) it silently pivots on the
  cell's intrinsic (lexicographic) order. Summon it where the per-stream ordering is in scope
  (`WORKLOG-naive-homology.md`). The intrinsic order is now opt-in (`import OrderedCell.cellOrdering`), so a new
  class-scope summon without it fails to compile instead of capturing silently -- but `Homology.scala`/`Cohomology.scala`
  import it file-wide (behaviour-preserving), so the hazard is still live there. `TDAlab`'s class-scope
  `chainIsRingModule` is user-arithmetic convenience only, never used by an engine.

### Streams and complexes (ordering contract and constructions: `rules/streams.md`)

**Public entry points** (`DESIGN-stream-naming.md`): `VietorisRips`, `Cech`, `Witness(variant = Lazy | General)`,
`Dowker`, `DtmRips`, `SparseRips`, `Truncated`, plus `CubicalImage` and `AlphaShapes`; `maxDimension` is the top
HOMOLOGICAL degree, the result a `LevelwiseSimplexStream[Int, Double]`. The construction classes (`...CofaceSimplexStream`,
`Incremental...`, `LazyWitness...`, `SheehyRips...`, `LimitedCoface...`) are `private[tda4j]`: never in docs fences
(they fail `sbt doc`). A new entry point is tested against the hand-wrapped class cell for cell AND value for value
(`ComplexesSpec`) — Betti numbers would not catch a wrong `+1`.

## Subsystem notes (`.claude/rules/`)

Detail for one subsystem lives in a path-scoped rule file that loads when you read a file in its area. Read the matching
file before changing that subsystem; this table is the index, in case a rule did not load.

| file | covers | loads for |
|---|---|---|
| `rules/streams.md` | the ordering contract every stream must satisfy (the #1 historical bug source), VR constructions, `maxFiltrationValue` default, `ExplicitStreamBuilder` | `streams/` |
| `rules/engines.md` | the four persistence engines, `BarcodeDistance`/`Vectorization`, circular and toroidal coordinates, benchmark specs | `homology/`, `barcode/` |
| `rules/cubical.md` | cubical complexes, `FastCubicalHomologyEngine` | cubical files |
| `rules/simplicial-sets.md` | the `sset` add-on: simplicial sets, the Sage-parity layer, group classifying spaces | `sset/` |
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
- **Docs carry the contract, worklogs carry the history.** Scaladoc, user guide and tutorials say what the code does and
  what to call (Li Haoyi's "easy": one import, defaults, errors that say what to do); no "used to", "fixed in session
  X", "confirmed by", worklog pointers or measurements there -- those go in `.claude/`. Error messages and `--help`
  never name `.claude/` files or private classes. The Developer's Guide is being edited by a student: touch it only to
  fix facts.
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
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. Don't reflexively hedge findings.
