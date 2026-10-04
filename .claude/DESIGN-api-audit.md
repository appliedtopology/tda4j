# API audit: easy, not simple (2026-10-03)

> **Status (same day, after the project lead's review).** Accepted except two points, both changed:
> 1. **Default field: F₁₇, not F₂** (lead: two decades of pushing back on "F₂ and call it a day"; 65537 considered and
>    judged provocative). `FiniteField.DefaultPrime = 17`, used by `Persistence`, the prebuilt labs and the MATLAB/CLI
>    facade. Large primes are now exact (Long products).
> 2. **The cursor stays.** It exists so a days-long run can be inspected and keeps its output if it dies; finding 1's
>    order-dependence is a presentation bug, fixed in place (`diagramAt(f)` exact at any `f`), plus `advanceFor(budget)`
>    for time-boxed slices. `PersistenceDiagram` is the immutable value the VERB returns and `snapshotAt(f)` takes from a
>    cursor -- not a replacement for it.
>
> Implemented: findings 1 (as above), 2 (`Persistence` verb), 3 (inferring companion forms; the class API stays), 4
> (`@implicitNotFound`, default as a parameter), 5 (`Optional[Double]` via `into`), 6 (`PointCloud`), 7
> (`AlphaBackend`), 8 (`Lab` base, `TDAlab.F17` etc., `CubicalLab`, companion spellings). New finding while doing it:
> every user file needs `import scala.language.experimental.modularity` -- the library is compiled with that flag, so
> all of it is `@experimental`; the `Self`-member typeclass context bounds are what need it (~100 errors without).
> A decision for the lead, not changed. Derivation: `WORKLOG-cursor-and-verb.md`.

Written for the project lead after the package flatten (`WORKLOG-package-flatten.md`). An audit only: nothing here is
implemented. Proposed code is marked **(uncompiled)**; feasibility checks are marked **(spike)** and were run on Scala
3.9.0 with this repo's flags.

## The lens

os-lib (Li Haoyi) as the model: `os.read(path)`, `os.write(path, data)`, `os.proc(cmd).call()`. Distilled:

1. **One obvious verb per task**, with the most common call being the shortest.
2. **Defaults for everything else**, as named parameters, not as extra objects to build first.
3. **Plain, immutable data back.** Results don't depend on the order you look at them.
4. **Errors that say what to do.**
5. **The machinery stays reachable** for experts, but is never mandatory for the common case.

The project's own constraints, which every proposal must satisfy:

- Field-generic, and every bar carries a representative.
- No instances in a typeclass companion (`Field`, `OrderedCell`).
- What `TDAlab` re-exports must be an object, type or val, never a top-level def.
- In the flat package, avoid external wildcard imports in core files.

## Method

I extracted every compiled Scala fence from `_docs/user-guide/**` and every tutorial whole-script: 41 fences in all.
I then counted the ceremony before the first answer. These are the docs we show users, so they measure what a
newcomer actually types.

| ceremony | fences affected (of 41) | occurrences |
|---|---|---|
| explicit engine type arguments (`SimplicialHomologyEngine[Int, Double, Double]()`) | 19 | 22 |
| hand-written `given Double is Field = Field.DoubleApproximated(1e-9)` | 15 | 15 |
| `Some(...)` around an optional number | 9 | 19 |
| a lab set up with 3 lines (`import scala.language.experimental.modularity`, `import ...TDAlab`, `val lab = TDAlab(2); import lab.{*, given}`) | 14 (every tutorial) | 14 |
| imports overall | 41 | 81 |

The typical "first answer" path today names **four nouns**: a metric space, a stream, an engine with three type
arguments, and a state object. Only then does it ask a question (`diagramAt(Double.PositiveInfinity)`).

## Findings, ranked by frequency × cost

### 1. Results are a stateful cursor, not data (correctness, not just friction)

`HomologyState.diagramAt(f)` / `barcodeAt(f)` / `diagramWithGeneratorsAt(f)` advance a mutable cursor and never
rewind (`homology/Homology.scala:196-215`, whose doc says so). **(spike)** The VR complex on the unit square, threshold
3.0, `maxDimension = 1`:

| query | result |
|---|---|
| `s.diagramAt(3.0)` | the full diagram, including `(2, 1.414, ∞)` |
| then `s.diagramAt(0.5)` on the same state | three H₀ bars capped at 0.5 **plus one `(0, 0.0, ∞)`** |
| `diagramAt(0.5)` on a fresh state | all four H₀ bars capped at 0.5 |

Same question, two answers, depending on what you asked before. The same run shows two more warts in raw diagrams:

- **An H₂ bar `(2, 1.414, ∞)` with `maxDimension = 1`.** It's an artifact of the truncated complex. `find-a-loop.md`
  hand-filters `dim <= 1` because of it.
- **Two zero-length H₁ bars `(1, 1.414, 1.414)`.** They come from tied filtration values. The MATLAB facade hides
  them; the Scala API doesn't.

Separately, `quickstart.md:79` calls `state.advanceAll()` before `barcodeAt(∞)`. That's redundant, but nothing tells
the user so.

**Proposal: engines return an immutable `PersistenceDiagram` value (uncompiled).**

```scala sc:nocompile
final case class PersistenceDiagram[CellT, C](bars: List[PersistenceBar[Double, Chain[CellT, C]]], maxDimension: Int):
  def dim(k: Int): PersistenceDiagram[CellT, C]         // bars of degree k
  def at(f: Double): PersistenceDiagram[CellT, C]       // pure: the diagram truncated at f, recomputed from `bars`
  def longest: Option[PersistenceBar[...]]              // with its representative
  def significant(fraction: Double = 0.01): PersistenceDiagram[CellT, C]
  def triples: List[(Int, Double, Double)]              // today's diagramAt shape
```

- Computed once to the end, so it's immutable and order-independent.
- Bars above `maxDimension` are dropped by default.
- Zero-length bars are kept in `bars` but hidden by `significant`, matching the facade's policy.
- Representatives are untouched.
- The incremental cursor stays available as `engine.incremental(stream)` for streaming users.
- **Cost:** medium (the result types of five engines, plus tests).
- **Risk:** low (pure addition first, then switch the docs).
- **Do this first.** It's the only item that fixes wrong answers.

### 2. No verb for the common task

Today (`user-guide/quickstart.md:52`):

```scala sc:nocompile
import org.appliedtopology.tda4j.*
given Double is Field = Field.DoubleApproximated(1e-9)
val engine = SimplicialHomologyEngine[Int, Double, Double]()
val stream = VietorisRips(EuclideanMetricSpace(points), maxFiltrationValue = Some(2.0))
engine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
```

Target (uncompiled; `Persistence` is an object with overloaded `apply`, so a lab can re-export it):

```scala sc:nocompile
import org.appliedtopology.tda4j.*
val diagram = Persistence(points, maxDimension = 1)       // VR, F_2, radius = enclosing radius
diagram.dim(1).longest                                     // the loop, with its representative cycle
```

The same verb covers every input:

| task | today | target (uncompiled) |
|---|---|---|
| VR on points | 4 nouns + `given` (above) | `Persistence(points)` |
| image | `CubicalHomologyEngine[Double, Double]().persistentHomology(CubicalImage.fromFlatArray(shape, values, sublevel = true)).diagramAt(∞)` (`cubical-complexes.md:7`) | `Persistence(Image(values, shape))` |
| alpha | `given Epsilon`, `HelixDelaunay(points)`, `FastAlphaHomologyEngine[Double]().persistentHomology(helix)` (`fast-alpha-complexes.md:7`) | `Persistence(points, complex = Alpha)` |
| any stream | `SimplicialHomologyEngine[...]().persistentHomology(stream)` | `Persistence(stream)` |

- **Images need a wrapper.** A 2-D image and a point cloud are both `Array[Array[Double]]`, so overloading alone
  can't tell them apart; hence `Image(...)`.
- **Engine choice is a named parameter**, `engine = Engine.Auto | Ripser | Chunks | Naive | FastCubical | FastAlpha`.
  `Auto` picks what the MATLAB facade already picks (`rules/facade.md`).
- **Precedent:** `matlab.TDA4j.computeFromPoints(points)` already *is* this verb, but stringly typed, for MATLAB.
  `Persistence` is its typed Scala twin, and `TDA4j` could become a thin layer over it.
- **Cost:** medium.
- **Risk:** low (additive).

### 3. Engine type arguments (19 of 41 fences)

`SimplicialHomologyEngine[Int, Double, Double]()` can't infer its types: they belong to the stream, which comes in
later. Leave them off and you get inference by implicit search. That was harmless only by luck, and after the
flatten it briefly inferred `VertexT = BarcodeEndpoint[Cube]`.

- **Fix:** move the type parameters from the class to the method (uncompiled):
  `SimplicialHomology.persistentHomology(stream)`, with
  `def persistentHomology[V: Ordering, C: Field](stream: LevelwiseSimplexStream[V, Double])`. `V` is inferred from the
  stream, and `C` from the field in scope or an explicit argument.
- **Cost:** medium (five engines, every call site; mechanical).
- **Risk:** the generic-given capture gotcha. Each engine must still summon `chainRM` *after* the stream's ordering
  exists, so this belongs in the engines themselves, not a wrapper.

### 4. The coefficient field is a ritual (15 of 41 fences)

`given Double is Field = Field.DoubleApproximated(1e-9)` opens most user-guide fences.

- **Not by a default given.** A lone `Double is Field` in `object Field` would silently decide `CoefficientT = Double`
  whenever a user forgot their `F_p` import. That means real coefficients and wrong torsion answers, with no compile
  error (same bug class as finding 3).
- **The defaults belong in the verb's parameters (finding 2):** `Persistence(points, field = 2)`. There, a default is
  visible and documented, not ambient.
- **Errors that say what to do (spike, works).** `@implicitNotFound` on `trait Field` replaces "No given instance of
  type Double is Field" at an engine's context bound with a message naming the three ways to pick a field, with
  `${Self}` filled in as `Double`. (A bare `summon[...]` keeps Scala's own message.)
  - **Cost:** one annotation each on `Field` and `OrderedCell`.
  - **Risk:** none.
  - **Cheapest item in this audit.**
- **A trap to document:** in a block, `given Double is Field = ...` placed *after* a statement that does implicit
  search fails with "given instance given_is_Double_Field needs result type because its right-hand side attempts
  implicit search". It must come first, or be named with an explicit type. I hit this writing `SimplicialSetApiSpec`.

### 5. `Some(...)` for optional numbers (9 fences, 19 occurrences)

Examples:

- `VietorisRips(ms, maxFiltrationValue = Some(2.0))`
- `PersistenceFilter.significant(bars, scale = Some(r), minPersistence = Some(0.0))` (`quickstart.md:79`)
- `VietorisRips(ms, 1, Some(1.5))`: positional order is dimension-then-radius.

CLAUDE.md prefers `Option` over sentinels, rightly. The Scala 3 way to keep `Option` and still let users write
`maxFiltrationValue = 2.0`:

- an `into` parameter type plus a `Conversion[Double, Option[Double]]`, scoped to that parameter (the codebase already
  uses `into class Chain`); or
- a tiny ADT, `radius: Radius = Radius.Auto` with `Radius(2.0)`.

Either needs a spike before committing to it. **Cost:** small per signature; **risk:** low.

### 6. Three point-cloud types

- `EuclideanMetricSpace` takes `Array[Array[Double]]` or `Seq[Seq[Double]]`, but not `Seq[Array[Double]]`, so
  `EuclideanMetricSpace(Seq(Array(...)))` fails.
- `AlphaShapes` takes `Seq[Array[Double]]` (`alpha/AlphaShapes.scala:39`).
- The facade and `CSV.readPointCloud` use `Array[Array[Double]]`.

**Fix:** one `PointCloud` input type, reached by `into` conversions from all three; or at least matching overloads
everywhere. **Cost:** small; **risk:** low.

### 7. Strings where an enum belongs

`AlphaShapes(points, dispatch = "helix")` takes `"default"`, `"helix"` or `"DQP"`; a typo is a runtime error. Turn it
into an enum like `VietorisRips`'s `Implementation`. **Cost:** small.

### 8. Labs: three lines to start, one regression

Every tutorial starts with:

```scala sc:nocompile
import scala.language.experimental.modularity   // likely unnecessary for lab users (they write no `is` syntax) -- check
import org.appliedtopology.tda4j.TDAlab
val lab = TDAlab(2)
import lab.{*, given}
```

**Proposal: prebuilt lab objects, so the whole setup is one line (uncompiled):**

```scala sc:nocompile
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}
```

- `object TDAlab { object F2 extends TDAlab(2); object F3 extends TDAlab(3); object Reals extends TDAlab(0) }`.
- `TDAlab(p)` stays for other primes.
- This fits the lead's idea of several opinionated labs: a `trait Lab` carries the generated re-export block (it's
  lab-agnostic), and `SimplicialLab` (today's `Int` vertices and `Simplex -> Chain` widening) and `CubicalLab` differ
  only in their conveniences.
- **Regression to fix:** the old `TDAlab` exported `asSimplex`/`asCube`; lab-only users lost them in this refactor. A
  re-exported extension is ambiguous for users who import both, but a non-extension spelling on an exported object is
  safe, e.g. `Simplex.fromSortedSet(s)` / `Cube.fromVector(v)`.
- The same holds for the other top-level defs (`simplexIsOrderedCell`, `simplexOrdering`, `cubeIsOrderedCell`): move
  them into the companions (`Simplex.isOrderedCell(...)`) and they ride along with the re-exported objects.

### 9. Smaller items

- **`diagramAt(Double.PositiveInfinity)` is the overwhelmingly common query.** An immutable diagram (finding 1) makes
  it just `diagram`.
- **Inconsistent verbs:** `persistentHomology(stream)` for most engines, `persistentCohomology()` with no argument on
  the Ripser engines. One verb (finding 2) hides this; the engines themselves could be aligned later.
- **Simplicial sets** (consolidated this session): persistent homology still needs `import x.given` before
  constructing an engine. With finding 3 (type parameters on the method), the engine could take the set's cell
  instance from `x.filtered(...)` itself.

## What is already easy (keep it)

- One import (`import org.appliedtopology.tda4j.*`) after the flatten, with default instances found automatically.
- The complex dispatchers: `VietorisRips`, `Cech`, `Witness`, `Dowker`, ... A homological-degree `maxDimension` and
  a sensible default radius (the minimum enclosing radius).
- `CSV.readPointCloud(path)` / `CSV.readEuclideanMetricSpace(path)` (os-lib-grade already).
- `PersistenceFilter.significant` and the facade's validated options, whose errors explain themselves ("--complex is
  not meaningful with --input-format=perseus-cubical: ... remove --complex, or ...").
- The simplicial-set catalog and methods (this session).

## Decisions for the project lead

1. **Default coefficient field.**
   - **Recommendation:** none ambient; `F_2` as the default *parameter* of `Persistence(...)`. `F_2` is the field
     Ripser and GUDHI default to.
   - **Rejected:** `Double`. Approximate arithmetic, and the typeclass-companion trap above.
2. **Result type:** immutable `PersistenceDiagram` (finding 1).
   - **Recommendation:** yes, and soon. It fixes a wrong-answer bug.
   - **Open:** how a coefficient-generic representative appears in a lab-agnostic result.
     - (a) `PersistenceDiagram[CellT, C]`, generic: honest, but the type shows up in user code.
     - (b) the field fixed by the verb's `field` parameter, with `C` path-dependent on it.
3. **Engine type arguments:** move them to the method (finding 3), or leave engines as they are and hide them behind
   `Persistence`.
   - **Recommendation:** both, in that order: verb first (additive), engine signatures later.
4. **Labs:** prebuilt objects (`TDAlab.F2`) plus a `trait Lab` for simplicial and cubical flavours (finding 8)? And do
   `asSimplex`/`asCube` come back as `Simplex.fromSortedSet`/`Cube.fromVector`?
5. **`Some(...)`:** `into`-conversion parameters vs. small ADTs (finding 5); spike either before choosing.

## Suggested order

| order | items | why |
|---|---|---|
| 1 | `@implicitNotFound` messages (4) | an hour |
| 2 | immutable `PersistenceDiagram` (1) | fixes wrong answers |
| 3 | `Persistence(...)` verb (2) | the biggest ergonomic win; additive |
| 4 | prebuilt labs and the `asSimplex` fix (8) | |
| 5 | point-cloud unification (6) and the alpha enum (7) | |
| 6 | `Some` removal (5) | needs a spike |
| 7 | engine signatures (3) | widest blast radius, so last |

Each step can be made test-first:

- **1:** the order-independence check from the spike, as a spec.
- **2:** a docs-fence count after rewriting the user guide with the verb.
