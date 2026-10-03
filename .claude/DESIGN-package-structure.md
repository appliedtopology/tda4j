# Package structure: flatten, split into add-ons, or a hybrid?

Decision document, 2026-10-03, written for the project lead to choose from before an overnight implementation
session. Supersedes nothing yet: `WORKLOG-package-reorg.md` is the derivation of the current subpackage layout.

## The problem, measured

- **Every real file imports almost every subpackage anyway.** Cross-package import lines in `src/`: 106×
  `algebra.{given, *}`, 93× `cells`, 78× `streams`, 53× `homology`, 43× `alpha`, 28× `barcode` — about 400 lines
  that carry no information, because the import set is nearly constant.
- **User-facing code pays the same tax.** The user-guide quickstart fences open with 4–6 imports before the first
  line of TDA (`_docs/user-guide/quickstart.md`, see "Before/after" below). This is the opposite of "easy".
- **There are no name collisions to protect against.** A scan of every public top-level `class`/`trait`/
  `object`/`type`/`def`/`val`/`given` across the subpackages found **zero** names defined in two packages. Only
  8 top-level givens exist in total.
- **The packages never enforced layering anyway.** Nothing stops `streams` importing `homology` except review;
  the one real cross-edge (`CubicalHomologyEngine`) was fixed by moving a file, not caught by the compiler.
  Only separate sbt modules would enforce dependency direction.
- **But some corners really are leaves**: `groups`, `io`, `matlab`, `cli` — nothing in the library imports them.
  The simplicial-set (Sage-parity) layer is *almost* a leaf: outside `cells/` and `groups/` it is referenced only by
  `homology.BettiNumbers.apply(FiniteSimplicialSet, prime)`, by `TDAlab`, and by the two stream files that exist
  only for it (`SimplicialSetStream`, `FilteredSimplicialSetStream`).

## Options

- **A. Flat.** Everything back in `org.appliedtopology.tda4j`. Keep the directories as file organization (Scala
  does not require directory = package; IntelliJ shows an inspection warning, nothing more).
- **B. Core + add-ons as separate sbt modules** (`tda4j-core`, `tda4j-sset`, `tda4j-io`, `tda4j-cli`, ...).
- **C. Hybrid (recommended).** Flat core package; a handful of **opt-in add-on subpackages**, each one a leaf by the
  criterion below. One sbt module.
- **D. Keep the subpackages, add a root re-export facade** (`export algebra.{*, given}` etc. in the root package), so
  users write one import while the library keeps its structure.

**Add-on criterion (for C):** a subpackage is justified only if (1) nothing in core imports it, and (2) it brings
its own vocabulary that a typical persistent-homology user never needs. `io`, `matlab`, `cli`, `groups` pass today;
`sset` passes after moving ~4 files' worth of references (below). **Dowker fails the criterion** even though it is
a natural "optional" feature: it is one stream file, speaks exactly the core vocabulary (a `LevelwiseSimplexStream`
fed to the same engines), and sits next to the `VietorisRips`/`Cech`/`Witness` dispatchers. Splitting it out would
make the stream catalog harder to find, not easier.

## Matrix

| | A. Flat | B. sbt modules | C. Hybrid | D. Re-export facade |
|---|---|---|---|---|
| Imports a user writes for a VR/alpha/cubical demo | 1 | 1–2 per module | 1 | 1 |
| ... for simplicial sets / group cohomology | 1 | +1 module dep, +1 import | 2 (`tda4j.{*, given}` + `tda4j.sset.{*, given}`) | 1 |
| Import churn inside the library | ~400 lines deleted | ~400 deleted, then re-added across module boundaries | ~400 deleted; add-ons keep 1 line each | none (no internal improvement) |
| Dependency direction enforced by the compiler | no | **yes** | no (but core→add-on edges stay visible as explicit imports) | no |
| Scaladoc navigation | one index of ~110 entries | per-module indexes | core index ~90 entries, sset/io/matlab/cli separate | duplicate entries (original + forwarder) |
| Build/CI/doc/MiMa complexity | unchanged | **high**: multi-project build, per-module MiMa baselines, unified scaladoc + snippet-compiler classpath across modules, assembly jar aggregation | unchanged | unchanged |
| Real third-party dependency isolation | none | scallop out of core; miniball/jvptree/commons-math are small | none (not needed: no heavy dependency exists today) | none |
| Name-binding risk from flattening (see "Spike") | present | n/a | present, same size as A | n/a |
| Given-ambiguity problems | unchanged | unchanged | unchanged | unchanged (plus forwarder givens: an extra copy to be ambiguous with) |
| Reversibility | easy (it is the pre-reorg layout) | hard | easy; an add-on can become a module later with no source change | easy |
| Overnight risk | low–medium (mechanical, full-suite gated) | **high** | low–medium | n/a: **does not compile** (spike 1) |
| Fixes the "where do I find X" confusion for sset | no | partly | **yes**, if the sset consolidation rides along | no |

## Recommendation: C

A gives almost all of C's ergonomics, but throws away the one boundary that is real (the leaves) and puts the
Sage-parity layer's ~40 public names into the same index as `VietorisRips`. B solves a problem we do not have (no
heavy dependency to isolate), costs a build-system rewrite, and makes docs/snippet compilation harder at the exact
moment we want them easier; C keeps B possible later, because each add-on is already a leaf. D leaves every
library file's import block in place and adds forwarders, so it only treats the user-facing symptom.

### Before / after (quickstart, alpha fence)

Today (`_docs/user-guide/quickstart.md`):

```scala
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)
import org.appliedtopology.tda4j.alpha.{given, *}

val shape = AlphaShapes(points.toSeq, dispatch = "helix")
```

Under A or C:

```scala
import org.appliedtopology.tda4j.{*, given}

given Double is Field = Field.DoubleApproximated(1e-9)
val shape = AlphaShapes(points.toSeq, dispatch = "helix")
```

Simplicial sets under C:

```scala
import org.appliedtopology.tda4j.{*, given}
import org.appliedtopology.tda4j.sset.{*, given}

val rp2 = SimplicialSet.realProjectivePlane   // name to be settled in the sset consolidation
```

### Proposed layout under C

- **core** `org.appliedtopology.tda4j` (flat): today's `algebra`, `cells` minus the sset files, `streams` minus the
  two sset streams, `homology`, `barcode`, `alpha`, root `package.scala`. `unicode.PrintingHelper` is unused — delete
  it rather than flatten it (confirm).
- **add-on `tda4j.sset`**: `SimplicialSet`/`FiniteSimplicialSet`, the `SimplicialSets` catalog, `SSetElement`,
  `SSetMap`, `SimplicialSetConstructions`, `CupProduct`, `Steenrod`, `FundamentalGroup`, `SimplicialSetStream`,
  `FilteredSimplicialSetStream`, and the `BettiNumbers` overload that takes a `FiniteSimplicialSet`.
- **add-on `tda4j.groups`**: unchanged (depends on core + `sset`).
- **leaves `tda4j.io`, `tda4j.matlab`, `tda4j.cli`**: unchanged. With `import tda4j.*` in scope, `io.CSV...` and
  `matlab.TDA4j` are reachable qualified with no extra import. Side effect worth knowing: that same import makes
  `io` mean `tda4j.io`, shadowing `scala.io` for code that writes `io.Source` (it already does today).
- `TDAlab` stays core; its sset-facing members move to (or are re-exported from) the add-on so core does not
  import `sset`. Open question: should `TDAlab` re-export the add-ons for the pylab "one import" ideal? That would
  make core depend on the add-ons, so the criterion says no — `TDAlab` could instead expose them qualified.

## Givens, ambiguity, and ClassTag

`ClassTag` will not help: it is runtime erasure evidence (needed to build an `Array[T]` generically), it plays no
part in choosing between two candidate givens. And the package layout is orthogonal to the problem — almost every
file already imports every given, so flattening neither causes nor cures it.

The known ambiguity/capture hazards in this codebase are (a) `given [CellT: OrderedCell] => Ordering[CellT]`
(`algebra/Cell.scala`), a blanket instance that competes with every explicitly-provided filtration `Ordering` for
the same cell type, and (b) same-named extension methods on different receivers (`boundary` on `Chain` vs. on
`Cube`, `WORKLOG-cubical.md`). The tools that do fix these:

1. **A distinct type for the distinct meaning.** A filtration order is not "the" order on cells; giving it its own
   typeclass or opaque wrapper (`FiltrationOrder[CellT]`) means it can never be ambiguous with `Ordering[CellT]`.
   Highest-leverage fix, also the most invasive.
2. **Drop or demote the blanket `Ordering` derivation** — make it an explicit `.ordering` call or a named given that
   must be imported by name, or move it into a low-priority trait so explicit instances win.
3. **By-type given imports** (`import pkg.{given Ordering[?]}`) and named givens, where a call site wants exactly one.
4. **`NotGiven[...]`** guards on a blanket instance where a more specific one exists.

To pick among these I need the actual error from the demo — please paste it (or the snippet). That investigation
is separate from the package change and should not ride along in the same commits.

## Spike: two language questions, checked rather than assumed

Run in a scratch sbt project on Scala 3.9.0 with this repo's flags (`-source:future`,
`-language:experimental.modularity`, `-preview`):

1. **Option D is not viable.** `export p.sub.{*, given}` at package top level fails with "Implementation
   restriction: package p.sub is not a valid prefix for a wildcard export, as it is a package". Only exporting
   every name individually would work, which means maintaining a ~110-name list by hand: rejected.
2. **Flattening has a real, silent name-binding hazard.** In package `flat`, file `Def.scala` defines top-level
   `clash`; file `Use.scala` (also in `flat`) does `import lib.Lib.*`, which also has a `clash`. The call
   resolves to **the wildcard import's** `clash`, with no error and no warning. A definition from another file of
   the same package ranks *below* a wildcard import. Under today's subpackages, the same situation is two wildcard
   imports of equal rank, which is a loud ambiguity error. So flattening (A or C) turns a compile error into a
   silent wrong binding: the "silently hits the wrong member" bug class CLAUDE.md already records.
   **Mitigation, added as an overnight gate:** the external wildcard imports in `src/` are few (`org.scalacheck.*`,
   `scala.concurrent.duration.*`, `math.Ordering.Implicits.*`, `scala.math.Fractional.Implicits.*`,
   `org.apache.commons.math3.linear.*`, `java.util.concurrent.*`, `com.eatthepath.jvptree.*`, `cats.syntax.all.*`,
   `org.rogach.scallop.*`, collection converters, `scala.util.chaining.*`, `scala.sys.process.*`,
   `scala.util.control.*`). Intersect their member names with the core's top-level names before flattening and
   rename or narrow the import on any hit. Test counts staying identical is the second check, not the only one.

## Simplicial-set consolidation (stretch; natural to do as part of creating `tda4j.sset`)

What is confusing today, concretely:

- `SimplicialSet.scala` holds the `trait SimplicialSet`, `class FiniteSimplicialSet` **and** the combinators
  `product`/`coproduct`/`quotient`/`identify` on `object FiniteSimplicialSet`.
- `SimplicialSets.scala` (512 lines) is the Sage-style catalog — `simplex`, `sphere`, `kleinBottle`, `horn` — **and
  also more combinators** (`cone`, `suspension`, `wedge`, `smash`, `join`, `subcomplex`) **and queries** (`fVector`,
  `isConnected`). So "where is `wedge`?" and "where is `product`?" have different answers for no reason.
- `SimplicialSetConstructions.scala` is private-ish ordering helpers with a public-sounding name.
- Enumeration is split: `generatorsAt(n)` (non-degenerate only) is a trait method; `elementsAtDim(sset, n)` (all
  elements, degenerate included) is on the companion; `generatorsByDim` is a raw field; `fVector` is in the other
  object. There is no single "iterate the cells" entry point.

Proposed direction (to refine overnight, not frozen):

- One companion, `object SimplicialSet`, holding **constructors** (catalog: `simplex`, `sphere`, `point`,
  `kleinBottle`, `fromSimplicialComplex`, `realProjectiveSpace`, ...); `SimplicialSets` goes away.
- **Combinators as methods** on `FiniteSimplicialSet` where they have a receiver (`x.product(y)`, `x.wedge(y)`,
  `x.cone`, `x.suspension`, `x.quotient(f)`, `x.subcomplex(keep)`), so they are discoverable by `x.` completion.
- **Enumeration as methods**, named for what they return: `x.nondegenerate(n)` / `x.cells(n)` (non-degenerate
  generators, what homology sees), `x.simplices(n)` (all elements including degenerate), `x.dimension`,
  `x.fVector`, `x.isConnected`. Keep `generatorsAt` as the trait's abstract primitive.
- Ordering helpers become `private[sset]`.
- A user-guide page "Simplicial sets" walking construct → inspect/enumerate → homology/cup products/Steenrod →
  maps → filtered, with every fence compiled (the docs-are-tests convention).

## Overnight plan (if C is chosen)

Gates (each must pass before the next step; failures stop and are reported, not papered over):

1. Record the `sbt testFull` baseline (pass/fail/skip counts) before touching anything.
2. Audit the external wildcard imports against core's top-level names (spike 2); fix any hit first.
3. Flatten core one package at a time, dependency order (`algebra` → `cells` → `streams` → `homology` →
   `barcode`/`alpha`), deleting the now-redundant imports; `private[barcode|homology|streams]` → `private[tda4j]`
   (9 sites). Full `testFull` after each package: compiling clean does not rule out a silent extension/given
   resolution change, only identical test counts do.
4. Create `tda4j.sset` (move files, the `BettiNumbers` overload, `TDAlab`'s sset members); `groups` imports it.
5. Rewrite every docs fence and tutorial-spec import; `TDA4J_SCALA_VERSION=3.8.4 sbt doc` (the snippet compiler
   runs here — fences are tests).
6. `sbt scalafmtAll scalafmtSbt`, then `scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck`.
7. Update CLAUDE.md (package layout, the `{given, *}` import rule, import examples) and the relevant `rules/` files;
   write `WORKLOG-package-flatten.md`.
8. sset consolidation as separate commits after 1–7 are green, so it can be reviewed or reverted on its own.

Never two sbt processes on the checkout at once. Push to the session branch; no PR unless asked.
