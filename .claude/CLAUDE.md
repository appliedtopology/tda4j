# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. Detail that matters for one subsystem only is in `.claude/rules/` (see "Subsystem notes").
Last condensed 2026-10-27; earlier, longer versions: `git show e5e86ec:.claude/CLAUDE.md`.

## What this is

TDA4j is a Scala 3 library for persistent homology and topological data analysis (a spiritual successor to
JavaPlex/Ripser, from the Stanford Computational Topology workgroup lineage). One library for the JVM and Scala.js
(an sbt project matrix), root package `org.appliedtopology.tda4j`, pre-1.0 (version in `version.sbt`), actively evolving API. **A 0.Y series does not keep binary
compatibility with the one before** (0.7.0 starts a fresh MiMa baseline; `build.sbt` `mimaBaselineVersions`) — no compat shims for
renames/signature changes; a break inside a series is a commented `mimaBinaryIssueFilters` entry plus a Breaking line.

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

## Git & Pull Request Guidelines

- CRITICAL: This is a single-developer repository and environment. Everything in it (uncommitted or unmerged changes,
  branch history, files, docs, issues, a rule file over its budget) was written by YOU, or by an earlier session of
  you, including what is no longer in your context. Where it came from never changes who owns it.
- Never write a commit message, PR description, issue or reply that disavows work here ("already like that at the
  base", "not touched by this PR"). Say what is true now and what you did about it. Cause may be named when it decides
  the action (a check that is red on the base branch is ported or reported, not fixed in the PR); it never decides
  who fixes it.
- A defect or overage you notice on the way (a file over its budget, a stale doc, an unfiled limitation) is fixed in
  the same change or filed as an issue before you finish, not reported as pre-existing.
- Assume local modifications are intentional parts of the branch and summarize them as your own work. If unsure about
  one after a /compact or a /clear, review it with `git diff` and the relevant worklog instead of apologizing or
  disclaiming it.

## Package layout

**One flat core package plus one add-on** (`DESIGN-package-structure.md`, derivation `WORKLOG-package-flatten.md`;
the 2026-09 subpackage split it replaced: `WORKLOG-package-reorg.md`). Everything a persistent-homology user needs is
in `org.appliedtopology.tda4j` itself, so users write `import org.appliedtopology.tda4j.*` (no `given` selector
needed, see "Givens"). The core's source directories are **file organization only, not packages**:

- `algebra/` — `RingModule`, `Field`, `FiniteField`, `Chain`, `Cell`/`OrderedCell`/`OrderedBasis`, `LinearAlgebra`.
- `cells/` — `Simplex`, `Cube` and their `OrderedCell` instances.
- `streams/` — streams, metric spaces, `PointCloud`, the dispatchers (`VietorisRips`, `Cech`, `Witness`, `Dowker`,
  `DtmRips`, `SparseRips`, `Truncated`; `DESIGN-stream-naming.md`), cubical images, `UnionFind`/`Kruskal`.
- `homology/` — the engines, `Persistence` (the verb), `CircularCoordinates`, `LatticeReduction`. Streams never use
  engines (convention only). `barcode/` — diagrams, filters, distances, vectorizations. `alpha/` — `AlphaShapes`
  (+ `AlphaBackend`), the Delaunay backends, `AlphaComplexDQP`. `io/` — file formats. Root `package.scala` — `TDAlab`.
- **add-on `sset`** (`import org.appliedtopology.tda4j.sset.*`) — simplicial sets (the Sage-parity layer), nerves of
  groups, monoids and finite categories (persistent group homology, library-only). Depends on the core; the core uses
  it only through `TDAlab`'s generated re-exports. `rules/simplicial-sets.md`.
- leaf subpackages: `matlab` (facade), `cli` (thin translator over `matlab.TDA4j`), `plot` (plots and a live viewer,
  whose one dependency is cask; any other feature needing a library goes in a new module; `rules/plotting.md`,
  `WORKLOG-plot-into-core.md`), `javascript` (Scala.js only: the npm package's exports over the facade).
- **two platforms**: sbt 2's project matrix builds the JVM (`tda4j`) and Scala.js (`tda4jJS`); JS-only code is in
  `src/*/scalajs`, JVM-only in `src/*/scalajvm`, and `src/{main,test}/scala` compiles for BOTH (no JVM-only library, no
  `java.nio.file`/`java.io.File`/threads there). Seams, limits: `rules/scalajs.md`.
- `python/` (the `tda4j` wheel, not a package): JPype over the `matlab` facade.
- `demo/` (not a package): a SEPARATE sbt build for a talk's REPL jar (`demo/README.md`, `WORKLOG-demo-jar.md`,
  `WORKLOG-demo-release.md`); the root build neither builds nor sees it, `release.yml` attaches it.
- Tests mirror this. `src/test/scala/tda4juser/` is deliberately OUTSIDE the package: it checks what a user's code
  sees (`UserImportsSpec`, `TDAlabAloneSpec`); the `tutorial` specs and generated page scripts are INSIDE it, so only
  `sbt doc` checks that doc fences resolve from outside.

Every file in a subpackage (`sset`, `matlab`, `cli`, `tutorial`) starts with an explicit `import
org.appliedtopology.tda4j.*`, not just the chained `package` clause. **Flat-package name-binding hazard**: a wildcard
import of an external library (`import cats.syntax.all.*`) silently BEATS a same-named definition from another file of
this package. Import external libraries by name in core files; after adding a top-level name, re-run the audit (inject
`import org.appliedtopology.tda4j.*` next to each file-level external wildcard and compile: ambiguity errors are the
hits; `WORKLOG-package-flatten.md`).

### Givens: the rule the flat layout depends on

**The core package has no top-level givens.** Default instances live in the companion of the DATA type they serve
(`Simplex`, `Cube`, `Chain`, `BarcodeEndpoint`, `Fp`, `Look`, generator enums): implicit scope, found with no import,
consulted only when nothing lexical matches, so a user's or a stream's given always wins and never ties. Never put an
instance in a TYPECLASS companion (`Field`, `OrderedCell`, `RingModule`): it is searched for `?T is Field` with `T` still
unknown, so a lone instance there silently decides type inference (a default `Double is Field` would turn a forgotten
`F_p` import into real coefficients and wrong torsion answers). A top-level generic given is worse: visible everywhere
in the flat package (`[CellT: OrderedCell] => Ordering[CellT]` made `SimplicialHomologyEngine()` infer `VertexT =
BarcodeEndpoint[Cube]`). Opt-in derivations are named givens imported by name (`import OrderedCell.cellOrdering`,
`Field.showFromField`). `UserImportsSpec` pins this from the user's side. `Show` likewise: every user-facing data type
has one in its companion; cats' `Show` is invariant, so a hierarchy's instance is typed `[..., D <: Base] => Show[D]`
(`WORKLOG-show-instances.md`).

## Commands

Java 21, Scala 3.9.0 LTS (`WORKLOG-scala-3.9-lts-upgrade.md`). `.jvmopts` sets `-Xmx2G` (the 1024MB default caused test-suite OOMs:
`WORKLOG-test-suite-memory-and-benchmark-gating.md`). Node >= 20.16 for the Scala.js tests.

```
sbt tda4j/testFull              # JVM specs (plain `test` is disk-cached in sbt 2 and can run 0 specs)
sbt tda4jJS/test                # Scala.js specs, on Node >= 20.16
sbt "tda4j/testOnly *SimplexSpec"   # single specs2 spec (glob ok)
sbt scalafmtAll                 # format everything — run before committing
sbt "scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck"   # exactly what CI's lint job runs (check only; fix with `sbt scalafmtAll scalafmtSbt`)
sbt tda4j/mimaReportBinaryIssues    # binary compat (CI `mima` job; JVM artifact only)
TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc   # docs site (_docs/ + sidebar.yml) via scaladoc -> target/out/jvm/scala-3.8.4/tda4j/api
sbt tda4j/assembly              # fat jar for CLI/MATLAB
sbt tda4jJS/npmPack             # the npm package, target/npm/tda4j-<version>.tgz
sbt -DrunBenchmarks=true tda4j/test   # also run benchmark/profiling specs — NOT what CI runs
```

No `sbt` on `PATH`: `.claude/scripts/install-sbt.sh`. A cloud Setup script
should hold `.claude/scripts/cloud-setup-script.sh` (installer plus warmed dependencies), else a session starts cold (~1 h).

No linter beyond scalafmt; tests are specs2. CI jobs (`test.yml`, `lint.yml`) and their local equivalents: steward skill; a weekly `coverage.yml` maps coverage. The ~319 `-Wunused:all` warnings stay
(`WORKLOG-compiler-warnings.md`). Docs build with 3.8.4 (a FRESH sbt server only: `rules/docs-and-tutorials.md`). After
`sbt package` or a docs build a test compile can miss main classes ("Not found: TDAlab"): `sbt clean`. **scalafmt only sees git-tracked files**: `git add` a new file before `scalafmtAll`.

**Never run two `sbt` invocations against this checkout at once**: one process reads the other's half-written class
files, and the `NoClassDefFoundError` looks like a regression until a clean, sequential rerun.

## User-facing entry points: `Persistence`, labs, the cursor

**Every user file needs `import scala.language.experimental.modularity`** (or `-experimental`): every definition of the
library is `@experimental` (the `Self`-member context bounds `C: Field` need the flag; ~100 errors without it). With
that line a plain downstream project needs nothing else, `into` conversions included (packaged jar checked,
`WORKLOG-cursor-and-verb.md`).

**`Persistence(input, maxDimension = 2, maxFiltrationValue, complex = VietorisRips, characteristic = 17, engine = Auto)`**
(`homology/Persistence.scala`) is the one-call verb: points/metric space/`Image`/any stream in, an immutable
`PersistenceDiagram` (bars + representatives; coefficient type is a member, `import d.given`; `dim`, `at(f)`,
`longest`, `longerThan(x)`, `longerThanFraction()`, `bettiNumbers`) out. **Default: degrees 0..2, cycles**;
`representatives = Representatives.Cycles | Cocycles` (MATLAB `representativeType`, CLI `--representative-type`).
`Auto` = Ripser for `VietorisRips` on points/metric space, `FastCubical` for images of dimension >= 2 (cycles only),
`Cohomology` otherwise. Every engine but the fast ones gives both kinds, the non-native one derived from its pairing
by `Involution`. The verb caps the reduction at its degree, and a truncated stream's own degree (`homologyDegreeLimit`)
is the default for streams (`rules/engines.md`, #51; `WORKLOG-default-degree-2.md`, `WORKLOG-involution.md`).

**Zero-length bars are dropped by default** (project lead: seeing them is the opt-in, never hiding them): every engine,
the verb, MATLAB and the CLI take `includeZeroLength` (default `false`; `rules/engines.md`). Tests must not use
`#bars == #cells` as an oracle unless they opt in; check the ordering contract or the actual barcode (`rules/facade.md`, `rules/streams.md`). `Input` is an `into` type: one `apply` with defaults covers every input. `VietorisRips`/`Cech`/`AlphaShapes` implement
`PointCloudComplex` and double as the `complex` choice. **Default field: `FiniteField.DefaultPrime = 17`** (project
lead: never F₂ by default -- it hides signs and odd torsion); also the MATLAB/CLI default. The verb runs to the end;
long runs use an engine's **cursor**, which is kept on purpose: `advanceFor(budget)`, `processedCells`/`totalCells`,
`diagramAt(f)` exact at any `f` wherever the cursor is, `snapshotAt(f)` (`rules/engines.md` query contract).

**`into` parameter types** (`Optional[Double]`, `PointCloud`): public numeric options take `2.0`, `2`, `Some(2.0)` or
`None`; point inputs take `Array[Array[Double]]`, `Seq[Seq[Double]]`, `Seq[Array[Double]]`. Use them for new public
signatures instead of `Option[Double]` / a fixed collection type.

### TDAlab and the other labs (detail: `rules/labs.md`)

`TDAlab`/`CubicalLab` extend `abstract class Lab(characteristic, precision)`; prebuilt `TDAlab.F2`/`F3`/`F17`/`Reals`.
`import TDAlab.F17.{*, given}` must be the ONLY library import a lab user needs. The flat re-exports of every public
top-level type of the core and `sset` are GENERATED: rerun `.claude/scripts/tdalab-exports.py` after adding or opening
one (`TDAlabExportsSpec` guards it). A lab is never consulted by an engine.

## Scala style used throughout (detail: `rules/scala-style.md`)

Uses Scala 3.7+'s newest context-abstraction syntax (`Type is TypeClass`, `type Self: Ordering as ordering`, opaque types
with their extension methods in the companion): don't "correct" it to older idioms. Optional parameters, never
sentinels: `Optional[Double]` for public ones. **No top-level `object`/`class` with a non-ASCII name**: scaladoc writes
one page FILE per type and `sbt doc` dies under a POSIX locale (`∆` is `val ∆ : Simplex.type = Simplex`,
`WORKLOG-package-flatten.md`).

**Writing specs** (shared generators, `===` typing in specs2 5.9, the ScalaCheck `forall` ascription, `.and(` mid-body):
`rules/testing.md`. Before creating a test file, `ls` for its name: `Write` overwrites silently.

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
**Current gaps**: every engine records a representative for every bar (`PackedRipserCohomologyEngine`'s apparent pairs
too), and so does `ClassifyingSpace.persistentGroupHomology`. `BlockParallelReduction` (JVM only, not reachable from
`Persistence`) gives bars only (#192); `ParallelPackedCohomology` is `Engine.ParallelRipser` (`rules/engines.md`).

**Openness principle (project lead, foundational: the library is a platform for experiments and research)**: public
and extensible by default. Access modifiers are not documentation: "not the entry point" is said in scaladoc ("what
`VietorisRips` builds"), never by hiding the class. A restriction (`private[tda4j]`, `sealed`, `final` on a class)
carries a `//` comment naming one of these reasons:
1. Invariant-bearing mutable state or an unchecked constructor: restrict it, and expose a read-only view or a checked
   factory (`CubicalGridStream.topValues`).
2. A library-wide guarantee: nothing public drops representatives (`barsWithoutTopRepresentatives`).
3. The flat namespace: a public top-level name joins every user's wildcard import and silently shadows their own
   same-named definition, so generic helpers (`Level`, `LongIntMap`, `Parallel`) stay restricted or move into a companion.
4. Facade leaves (`matlab`, `cli`): their contract is strings and Java arrays; what they dispatch to is public.
5. Public another way (`sset.Constructions` behind `FiniteSimplicialSet`'s methods).
Plain `private` is for an algorithm's own scratch types, state and helpers (no comment); `protected` when a subclass
needs it as a hook, public when it computes something a caller wants (a pairing, a validity check).
`sealed` only for a closed mathematical classification matched exhaustively (`BarcodeEndpoint`). `final` only on value
types (case classes: subclassing breaks equality), where library fast paths match on the concrete class (`HeapChain`,
`PackedChain`), and on `@tailrec` defs; not for speed without an A/B.
Concrete public classes are `open`: users lack `-language:adhocExtensions`, so extending a non-`open` class warns under
`-source:future -feature`. Not reasons: a small API surface, "users should not need it", "it might change" (pre-1.0,
anything may). `WORKLOG-openness-audit.md`.

### Algebraic core (detail: `rules/algebra.md`)

Everything downstream is generic over `Field` (`Fp` is an opaque type per prime). **Never test the runtime class of a
generic coefficient** (`case d: Double`): on Scala.js every boxed number is a `Double`, so an `Fp` matches. Ask the field
(`Field.toDouble`, #80). `Chain` is abstract and OPEN; its `equals` and `hashCode` are those of the formal sum (#58).
`Cocell`/`OrderedCocell` were **removed on purpose**: don't reintroduce (`DESIGN-generic-cohomology.md`).
**Generic-`given` capture gotcha**: a `given` like `chainRM` fixes its `Ordering[CellT]` where it is summoned; at
class scope (before the stream's ordering exists) it silently pivots on the cell's intrinsic order. Summon it where
the per-stream ordering is in scope (`WORKLOG-naive-homology.md`). The intrinsic order is opt-in (`import
OrderedCell.cellOrdering`), but `Homology.scala`/`Cohomology.scala` import it file-wide: the hazard is live there.

### Streams and complexes (ordering contract and constructions: `rules/streams.md`)

**Public entry points** (`DESIGN-stream-naming.md`): `VietorisRips`, `Cech`, `Witness(variant = Lazy | General)`,
`Dowker`, `DtmRips`, `SparseRips`, `Truncated`, plus `CubicalImage` and `AlphaShapes`; `maxDimension` is the top
HOMOLOGICAL degree, the result a `LevelwiseSimplexStream[Int, Double]`. The construction classes (`...CofaceSimplexStream`,
`Incremental...`, `LazyWitness...`, `SheehyRips...`, `LimitedCoface...`) are public machinery; docs and tutorials use the
objects. A new entry point is tested against the hand-wrapped class cell for cell AND value for value (`ComplexesSpec`):
Betti numbers would not catch a wrong `+1`.

## Subsystem notes (`.claude/rules/`)

Detail for one subsystem lives in a path-scoped rule file that loads when you read a file in its area. Read the matching
file before changing that subsystem; this table is the index, in case a rule did not load.

| file | covers | loads for |
|---|---|---|
| `rules/streams.md` | the ordering contract every stream must satisfy (the #1 bug source), VR constructions, `ExplicitStreamBuilder` | `streams/` |
| `rules/engines.md` | the persistence engines, `Involution`, benchmark specs | `homology/` |
| `rules/barcode.md` | `BarcodeDistance`, `Vectorization`, `BootstrapBand`, `ToMATo` | `barcode/` |
| `rules/module-maps.md` | maps of barcodes: `MapAlgebra`, `PersistenceMap`, their oracle | `MapAlgebra`, `PersistenceMap` |
| `rules/morse-and-zigzag.md` | discrete Morse theory, zigzag persistence | `DiscreteMorse`, `MorseCell`, `Zigzag*` |
| `rules/circular-coordinates.md` | circular and toroidal coordinates, from points or a diagram | those files |
| `rules/cubical.md` | cubical complexes, `FastCubicalHomologyEngine` | cubical files |
| `rules/simplicial-sets.md` | the `sset` add-on: simplicial sets, the Sage-parity layer, group classifying spaces | `sset/` |
| `rules/filtered-complexes.md` | Cech, witness, Dowker, DTM, sparse Rips, edge collapse | those files |
| `rules/alpha.md` | alpha complexes (DQP, Helix, fast alpha) | `alpha/`, alpha files |
| `rules/facade.md` | MATLAB facade, CLI, file I/O, the persistence threshold | `matlab/`, `cli/`, `io/` |
| `rules/python.md` | the Python package: facade mirror, options, wheel, CI | `python/` |
| `rules/scalajs.md` | the Scala.js build: platform seams, shared-code limits, JS API, npm package | `scalajs/`, `scalajvm/`, `build.sbt`, `npm/` |
| `rules/docs-and-tutorials.md` | docs site, tutorial pages (the docs are the tests), language tabs | `_docs/`, tutorial specs, `build.sbt` |
| `rules/plotting.md` | the `plot` subpackage: scene model, tweaks, looks, live viewer, gallery | `plot/` (main and test) |
| `rules/algebra.md` | fields, chains and cells: `RingModule`, `Field`, `Chain`, `Cell` | `algebra/`, `cells/` |
| `rules/testing.md` | writing specs: shared generators, specs2 traps | `src/test/**` |
| `rules/scala-style.md` | the Scala 3 idioms used throughout, opaque-type and extension-method hazards | `src/**/*.scala` |

## Session practices

- **Write a `.claude/WORKLOG-<topic>.md` by default** for any substantial investigation, debugging, or
  profiling arc, without being asked. Worklogs are point-in-time snapshots, never retroactively edited. At the
  end of the arc, update this file (or the matching `.claude/rules/` file, for one subsystem) with **only the resulting
  rule/invariant/limitation plus a worklog pointer**: no narrative, measurements or repros. This file loads in
  every session: keep it under ~25k characters (and each rule file under ~12k); when one drifts past that, condense it
  the same way (strip narrative to worklog pointers, move single-subsystem detail into a rule file) and note the new
  condensing date/commit at top.
- **Docs carry the contract, worklogs carry the history.** Scaladoc, user guide and tutorials say what the code does and
  what to call; no "used to", "fixed in session X", "confirmed by", worklog pointers or measurements there -- those go
  in `.claude/`. `//` implementation comments
  MAY carry history and worklog pointers (project lead, 2026-10-03), as long as none of it moves into scaladoc. Error
  messages and `--help` never name `.claude/` files or private classes. **The Developer's Guide is kept current with the
  code like the other docs** (project lead, 2026-10-17): a change to a subsystem updates that subsystem's page, under the
  same rule, and a fact the code contradicts is fixed in the change that makes it false.
- **Never revert the formatter's output.** If `scalafmtAll` touches files outside your change, commit that in its OWN
  commit ("Format: ... formatter output only, no behavior change") and say so: a clean lint beats a minimal diff. Run
  all three lint checks (Commands) before pushing.
- Performance claims need isolated A/B measurement (`git stash` A/B, median of trials, one engine per JVM);
  machine noise here often exceeds small effects — report unconfirmed effects as unconfirmed.
- **A new user-visible capability** (complex, engine, option): each session that lands a chunk of it checks
  `matlab.TDA4j`, `cli.TDA4jConf` (1:1 mirror), `python/` (a gap fails `test_facade_coverage.py`), `_docs/developers-guide/`, `_docs/user-guide/`. Refactors and bug fixes
  with no new surface are exempt.
- **Open items are GitHub issues** (project lead, 2026-10-11; labels and workflow in `CONTRIBUTING.md`): a question,
  limitation or unverified claim a session leaves gets an issue, linked from its worklog and PR; a user-visible change
  gets a `changelog.d/<topic>.md` fragment, never a `CHANGELOG.md` line (PRs that add to it conflict and drop out of the
  merge queue; `changelog.d/README.md`, `WORKLOG-changelog-fragments.md`).
- A cloud session (working on its own `claude/...` branch) may commit and push its own work to that branch at
  will, without asking first — the branch is disposable/session-scoped, not shared history. A local/interactive
  session working directly on a shared branch still waits to be asked; the project lead commits that work.
- **Driving a PR to green** (CI jobs and their local equivalents, how proactive to be): `.claude/skills/steward/SKILL.md`.
- **A bug found in a paper or reference implementation** goes in `.claude/BUGS-IN-REFERENCES.md` (flat, never condensed
  away): state exactly what was verified, and claim nothing end-to-end without a repro that shows it.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. Don't reflexively hedge findings.
