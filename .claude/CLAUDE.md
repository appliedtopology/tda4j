# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How this file works.** Each entry is a current rule, invariant, or known limitation, plus a pointer to the
`.claude/WORKLOG-*.md`/`DESIGN-*.md` that holds its derivation (what was tried, measurements, repros). Derivations
go in the worklog, not here. Detail that matters for one subsystem only is in `.claude/rules/` (see "Subsystem notes"). Last condensed 2026-10-10 (lab detail to `rules/labs.md`); 2026-10-09; earlier, longer versions: `git show e5e86ec:.claude/CLAUDE.md` (and the commits named there).

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
  (+ `AlphaBackend`), `BowyerWatsonDelaunay` (default up to 4-D), `HelixDelaunay`, `AlphaComplexDQP`. `io/` — `CSV`, `Ripser`, `Dipha`, `Gudhi`, `Perseus`.
- root `package.scala` — `TDAlab` (below).
- **add-on `sset`** (`import org.appliedtopology.tda4j.sset.*`, directory `sset/`) — simplicial sets (the Sage-parity
  layer), group classifying spaces (persistent group homology, library-only), `BettiNumbers`, simplicial-set streams.
  Depends on the core; the core uses it only through `TDAlab`'s generated re-exports. `rules/simplicial-sets.md`.
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

If `sbt` isn't on `PATH`, run `.claude/scripts/install-sbt.sh` (paces around Maven Central's cold-cache rate limits); in
the cloud environment's setup script it saves new sessions a 20-40 minute cold start.

No linter beyond scalafmt; tests are specs2 (`org.specs2.mutable.Specification`). CI on every PR to `scala`: `test.yml`
(jobs `test`, `docs-build`, `mima`) and `lint.yml` (`scalafmtSbtCheck` on `build.sbt` too: format it before pushing);
`docs.yml` publishes on push to `scala`. MiMa checks against every earlier plain release of the series
(`mimaBaselineVersions`); a deliberate break is a commented `mimaBinaryIssueFilters` entry (project lead: binary
compatibility is not a strong requirement before 1.0). The ~319 `-Wunused:all` warnings are deliberately left alone
(`WORKLOG-compiler-warnings.md`). Docs build with Scala 3.8.4 (`TDA4J_SCALA_VERSION`, seen only by a FRESH sbt server:
`rules/docs-and-tutorials.md`). After `sbt package` or a docs build a test compile can miss main classes ("Not found:
TDAlab"): `sbt clean`. **scalafmt only sees git-tracked files**: `git add` a new file before `scalafmtAll`.

**Never run two `sbt` invocations against this checkout at once** — the incremental compiler's own class-file
writes from one process can be read mid-update by the other, producing a `NoClassDefFoundError` that looks like a
real regression but disappears on a clean, sequential rerun.

## User-facing entry points: `Persistence`, labs, the cursor

**Every user file needs `import scala.language.experimental.modularity`** (or `-experimental`): every definition of the
library is `@experimental` (the `Self`-member context bounds `C: Field` need the flag; ~100 errors without it). With
that line a plain downstream project needs nothing else, `into` conversions included (packaged jar checked,
`WORKLOG-cursor-and-verb.md`). Doc fences include the line although the docs build would not need it.

**`Persistence(input, maxDimension = 2, maxFiltrationValue, complex = VietorisRips, characteristic = 17, engine = Auto)`**
(`homology/Persistence.scala`) is the one-call verb: points/metric space/`Image`/any stream in, an immutable
`PersistenceDiagram` (bars + representatives; coefficient type is a member, `import d.given`; `dim`, `at(f)`,
`longest`, `longerThan(x)`, `significant()`, `bettiNumbers`) out. **Default: degrees 0..2, cycles**;
`representatives = Representatives.Cycles | Cocycles` (MATLAB `representativeType`, CLI `--representative-type`).
`Auto` = Ripser for `VietorisRips` on points/metric space, `FastCubical` for images of dimension >= 2 (cycles only),
`Cohomology` otherwise. Every engine but the fast ones gives both kinds: its native kind (cocycles for Ripser/
Cohomology, cycles for Chunks/Naive/FastCubical) and the other derived from its pairing by `Involution` (reduce only
death columns, or birth coboundaries; every pivot checked). `Chunks`/`Naive` reduce every top cell (VR/Cech H2 on
~100 points: minutes or OOM). A truncated stream's own degree (`homologyDegreeLimit`) is the default for streams.
`WORKLOG-default-degree-2.md`, `WORKLOG-involution.md`.

**Zero-length bars are dropped by default** (project lead: seeing them is the opt-in, never hiding them): every engine,
the verb, MATLAB and the CLI take `includeZeroLength` (default `false`); short bars are one call away
(`longerThan(x)`, `significant()`, also on `List[PersistenceBar]` with no import). Tests must not use `#bars == #cells` as
an oracle unless they opt in; check the ordering contract or the actual barcode (`rules/facade.md`, `rules/streams.md`). `Input` is an `into` type: one `apply` with defaults covers every input. `VietorisRips`/`Cech`/`AlphaShapes` implement
`PointCloudComplex` and double as the `complex` choice. **Default field: `FiniteField.DefaultPrime = 17`** (project
lead: never F₂ by default -- it hides signs and odd torsion); also the MATLAB/CLI default. The verb runs to the end;
long runs use an engine's **cursor**, which is kept on purpose: `advanceFor(budget)`, `processedCells`/`totalCells`,
`diagramAt(f)` exact at any `f` wherever the cursor is, `snapshotAt(f)` (`rules/engines.md` query contract).
Engines also have inferring companion forms: `SimplicialHomologyEngine.persistentHomology(stream)`.

**`into` parameter types** (`Optional[Double]`, `PointCloud`): public numeric options take `2.0`, `2`, `Some(2.0)` or
`None`; point inputs take `Array[Array[Double]]`, `Seq[Seq[Double]]`, `Seq[Array[Double]]`. Use them for new public
signatures instead of `Option[Double]` / a fixed collection type. `AlphaBackend` (enum) replaced string dispatch.

### TDAlab and the other labs (detail: `rules/labs.md`)

`TDAlab`/`CubicalLab` extend `abstract class Lab(characteristic, precision)`; prebuilt `TDAlab.F2`/`F3`/`F17`/`Reals`.
`import TDAlab.F17.{*, given}` must be the ONLY library import a lab user needs: coefficients, `Fp(...)`, chain
arithmetic, `.show`, and GENERATED flat re-exports of every public top-level type of the core and `sset` -- rerun
`.claude/scripts/tdalab-exports.py` after adding or opening one (`TDAlabExportsSpec` guards it). A lab is never
consulted by an engine.

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

**Shared test generators** (`matrixGen`) live in `src/test/.../streams/Generators.scala`, never in a spec. Before
creating a test file, `ls` for its name: `Write` overwrites silently.

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

**Openness principle (project lead, foundational: the library is a platform for experiments and research)**: public
and extensible by default. Access modifiers are not documentation: "not the entry point" is said in scaladoc ("what
`VietorisRips` builds"), never by hiding the class. A restriction (`private[tda4j]`, `sealed`, `final` on a class)
carries a `//` comment naming one of these reasons:
1. Invariant-bearing mutable state or an unchecked constructor: restrict it, and expose a read-only view or a checked
   factory (`CubicalGridStream.topValues` over `protected[tda4j] topCellValues`; `EdgeCollapsedMetricSpace.edges`).
2. A library-wide guarantee: nothing public drops representatives (`barsWithoutTopRepresentatives`).
3. The flat namespace: a public top-level name joins every user's wildcard import and silently shadows their own
   same-named definition, so generic helpers (`Level`, `LongIntMap`, top-level defs) stay restricted or move into a
   companion object first.
4. Facade leaves (`matlab`, `cli`): their contract is strings and Java arrays; what they dispatch to is public.
5. Public another way (`sset.Constructions` behind `FiniteSimplicialSet`'s methods).
Plain `private` is for an algorithm's own scratch types, state and helpers (no comment); promote a member to `protected`
when a subclass needs it as a hook, to public when it computes something a caller wants (a pairing, a validity check).
`sealed` only for a closed mathematical classification matched exhaustively (`BarcodeEndpoint`). `final` only on value
types (case classes: subclassing breaks equality), where library fast paths match on the concrete class (`HeapChain`,
`PackedChain`), and on `@tailrec` defs; never for speed (HotSpot devirtualizes a class with no loaded subclass).
Concrete public classes are `open`: users lack `-language:adhocExtensions`, so extending a non-`open` class warns under
`-source:future -feature`. Not reasons: a small API surface, "users should not need it", "it might change" (pre-1.0,
anything may). `WORKLOG-openness-audit.md`.

### Algebraic core

- `RingModule`/`Field`: minimal typeclasses built with `is` syntax; everything downstream is generic over `Field`.
  `FiniteField`: `Fp` opaque type per prime `p`.
- `Chain[CellT: Ordering, CoefficientT: Field]`: abstract and OPEN (project lead: a place for the community to
  experiment): a storage passes order and field up (kept once) and implements `entryIterator`, all else defaults over it
  (`tda4juser/ChainStorageSpec`, outside the package). Ours: `HeapChain` (a `PriorityQueue` ordered by cell), `PackedChain`
  (`rules/cubical.md`). No nulls. Equality: formal sums by `field.isEqual` (an `Fp` has several `Int` forms), order-blind.
  Defines `reduceBy`/`reduceByUntil` (with an optional `fallback` for pivots needing on-the-fly substitution) and
  a `RingModule` instance. `reduceLoop` uses a `mutable.TreeMap` accumulator (use `z.head`, not `headOption`, which
  allocates an iterator on `mutable.TreeMap`). Known footgun, not fixed: `Chain` overrides `equals` without a
  matching `hashCode` — don't put `Chain`s in a `Set`/`Map` key.
- `Cell`/`OrderedCell`/`OrderedBasis`: anything with a `boundary` and a total order plugs into homology. Concrete
  instances: `Simplex`, `Cube`, `FiniteSimplicialSet` generators.
- `Cocell`/`OrderedCocell` were **removed on purpose**: coboundary is extrinsic (depends on the ambient complex),
  so a per-cell `coboundary` is the wrong shape. Don't reintroduce (`DESIGN-generic-cohomology.md`).
- **Generic-`given` capture gotcha**: a `given` like `chainRM` fixes its `Ordering[CellT]` where it is summoned; at
  class scope (before the stream's ordering exists) it silently pivots on the cell's intrinsic order. Summon it where
  the per-stream ordering is in scope (`WORKLOG-naive-homology.md`). The intrinsic order is opt-in (`import
  OrderedCell.cellOrdering`), but `Homology.scala`/`Cohomology.scala` import it file-wide: the hazard is live there.

### Streams and complexes (ordering contract and constructions: `rules/streams.md`)

**Public entry points** (`DESIGN-stream-naming.md`): `VietorisRips`, `Cech`, `Witness(variant = Lazy | General)`,
`Dowker`, `DtmRips`, `SparseRips`, `Truncated`, plus `CubicalImage` and `AlphaShapes`; `maxDimension` is the top
HOMOLOGICAL degree, the result a `LevelwiseSimplexStream[Int, Double]`. The construction classes (`...CofaceSimplexStream`,
`Incremental...`, `LazyWitness...`, `SheehyRips...`, `LimitedCoface...`) are public machinery (build a new filtered
complex on `RipserCofaceSimplexStream`'s coface loop); docs and tutorials use the objects. A new entry point is tested against the hand-wrapped class cell for cell AND value for value
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
  X", "confirmed by", worklog pointers or measurements there -- those go in `.claude/`. `//` implementation comments
  MAY carry history and worklog pointers (project lead, 2026-10-03), as long as none of it moves into scaladoc. Error messages and `--help`
  never name `.claude/` files or private classes. The Developer's Guide is being edited by a student: touch it only to
  fix facts.
- **Never revert the formatter's output.** If `scalafmtAll` touches files outside your change, commit that in its OWN
  commit ("Format: ... formatter output only, no behavior change") and say so — reverting only hides the debt, and a
  clean lint beats a minimal diff. Run all three lint checks (Commands) before pushing.
- Performance claims need isolated A/B measurement (`git stash` A/B, median of trials, one engine per JVM);
  machine noise here often exceeds small effects — report unconfirmed effects as unconfirmed.
- **Finalizing a user-visible capability** (new complex, engine, or option) means checking four surfaces each
  session that lands a chunk of it: (1) `matlab.TDA4j` dispatch, (2) `cli.TDA4jCLI`/`TDA4jConf` (1:1 mirror),
  (3) `_docs/developers-guide/` (`persistence-engines.md`, `architecture.md`, `class-diagrams.md`),
  (4) `_docs/user-guide/`. Internal refactors and bug fixes with no new surface are exempt.
- A cloud session (working on its own `claude/...` branch) may commit and push its own work to that branch at
  will, without asking first — the branch is disposable/session-scoped, not shared history. A local/interactive
  session working directly on a shared branch still waits to be asked; the project lead commits that work.
- **A bug found in a paper or reference implementation** goes in `.claude/BUGS-IN-REFERENCES.md` (flat, never condensed
  away): state exactly what was verified, and claim nothing end-to-end without a repro that shows it.

## Collaboration preferences

The project lead values intellectual honesty and direct pushback over agreement — say plainly when an approach is
a dead end, when benchmarks are mixed, or when a deliverable is unverified, rather than softening it. Don't reflexively hedge findings.
