# WORKLOG: whole-project code critique (readability, idiom, organization), 2026-09-22

**Scope.** Every Scala file in `src/main` (44 files, ~10.3k lines) was read, plus `build.sbt`/`.scalafmt.conf`, a
quick pass over `src/test` (§7), and a grep of `src/main/paradox` for the classes §5 proposes to delete or move
(§5.5). The Paradox prose itself was not reviewed. `AlphaComplexDQP.scala`'s numerical core (`CholeskyWorkspace`/`DualQP`, lines
~262–710) was only skimmed, since the settled-numerics section of CLAUDE.md puts it off-limits for retuning anyway.

**Evidence labels** used throughout: **[probe]** = demonstrated by running code (repro in the appendix);
**[grep]** = mechanically verified by search; **[reading]** = from reading the code, not executed; **[estimate]** =
judgment, not measured.

No source was changed. The probe ran from the scratchpad through
`sbt 'set Test / unmanagedSourceDirectories += file(...)'`, so the working tree is untouched.

---

## 0. Ranked summary

1. **Real correctness bug:** `Simplex` boundary faces come out in hash order for simplices with ≥5 vertices, so the
   signs are wrong and `∂∂ ≠ 0`. Over F3 the barcodes differ from F2 on synthetic torsion-free complexes. It was
   *not* reproduced through the MATLAB facade on VR inputs at `maxDimension=3` (0 of 32 inputs). **[probe]** See
   §1.1.
2. **The ordering contract, CLAUDE.md's "#1 historical bug source", is enforced only by convention.** The canonical
   comparator is hand-copied into ~10 places, and several copies deviate. A generic combinator already exists
   (`simplicialSetFiltrationOrdering`), but nothing else uses it. See §2.
3. **Code rationale lives in agent-session logs, not the code.** Comments make up 39% of main-source lines. There
   are 108 `WORKLOG-*` pointers and 33 CLAUDE.md pointers. Rot is already visible: 5 dangling CLAUDE.md section
   names, a cited-as-verification spec that doesn't exist, and comments contradicting the code they sit on. See §3.
4. **There is no common engine interface.** Five engines have five call shapes, which makes the MATLAB facade a 3×4
   string match with near-duplicate arms, and pushes the `+1`-and-drop `maxDim` workaround onto every caller.
   See §4.
5. **Dead, legacy, and experimental code ships in `main`:**
   - ~290 lines of self-flagged legacy Ripser streams;
   - a `???`-bodied public class;
   - an unused `unicode` package;
   - three unused dependencies, one of them a Scala-2.13 cross-build;
   - test oracles living as public API.

   See §5.

---

## 1. Correctness issues found along the way

### 1.1 `Simplex` boundary signs wrong for ≥5 vertices **[probe]**, the headline

**Mechanism.** `cells/SimplexOrderedCell.scala:39`:

```scala
spx.zipWithIndex.map((vtx, i) => spx.dropIndex(i)).toSeq.zip(alternatingSigns)
```

`spx.zipWithIndex` forwards to `SortedSet.zipWithIndex` (`SimplexOps.scala:89`). That is `IterableOps.zipWithIndex`,
whose result collection is the *unsorted* `Set`. For up to 4 elements `Set1..Set4` keep insertion order, so
everything looks right. From 5 elements on it is a `HashSet`: the `.map` produces a `HashSet` of faces, and `.toSeq`
emits them in hash order. The alternating signs are then zipped onto faces in the wrong order.

The probe printed the face order for the 5-vertex simplex as `0234 0134 0124 1234 0123` (expected
`1234 0234 0134 0124 0123`). `∂∂(Δ⁴)` has 4 nonzero terms.

**Barcode impact.**
- **Test setup:** 20 random monotone filtrations of the 5-skeleton of Δ⁶. These are subcomplexes of a simplex, so
  torsion-free, and their F2 and F3 barcodes must agree; F2 is sign-blind. Each engine was also rerun with a
  correctly-ordered `Simplex[Int] is OrderedCell` supplied in scope, as a positive control.

| engine | shipped boundary, F3≠F2 | correct boundary, F3≠F2 |
|---|---|---|
| naive (`CellularHomologyContext`) | 1/20 | 0/20 |
| chunks (`CellularPersistenceInChunksContext`) | **19/20** | 0/20 |
| `CellularCohomologyContext` | 0/20 | 0/20 |

- **Over ℝ:** an earlier run of the same fixtures with `DoubleApproximated` showed naive ℝ≠F2 on 2/20. The bad
  barcodes include impossible bars, such as an essential H⁵ class in a 5-skeleton.
- **Cohomology:** it uses the same (wrong) boundary, so it is affected in principle. These fixtures didn't show a
  difference.

**Scope.**
- **Chain level [probe]:** every boundary of a simplex with ≥5 vertices is mis-signed, so `∂∂ ≠ 0` there. Any
  `Simplex`-based naive/chunks/cellular-cohomology run over a field of characteristic ≠ 2 reduces those wrong
  columns.
- **Facade exposure [reading]:**
  - VR/Cech with `field=R`, or `field=Z` with `prime ≠ 2`, at `maxDimension ≥ 3` (engines build `maxDim+1`, i.e.
    4-simplices);
  - alpha at any `maxDimension` when the ambient dimension is ≥ 4. `AlphaShapeDQP` passes the points' own
    dimension as its `maxDimension`, and Helix's `simplicesMap` spans `0 to ambientDimension`, so alpha never goes
    above ambient dimension through the facade.
- **Facade barcode impact [probe]: not demonstrated.**
  - **Setup:** `TDA4j.computeFromPoints`/`computeFromDistanceMatrix` with `maxDimension=3`, comparing
    `engine=chunks` and `engine=naive` against `engine=ripser`, at `prime=2` and at `prime=3`. Ripser never calls
    `Simplex.boundary`, so it is an independent oracle.
  - **Inputs:** 12 random 12-point 3-D clouds, and 20 tie-heavy random `{1,2}`-valued 9-point metrics.
  - **Result:** 0 disagreements at either prime, for either engine.
  - **Likely reason:** at `maxDimension=3` only the 4-simplex columns (H³ deaths) are touched, and random VR inputs
    have almost no H³ for a sign error to perturb. Bars at dimension ≥ 4 (`maxDimension ≥ 4`), and their
    representatives, are the plausible exposure. This is not probed.
- **Unaffected:** both Ripser engines compute their own coboundary signs by vertex position, not via `boundary`.
  The MATLAB default (`Z`, `prime=2`, `maxDimension=2`) is also unaffected.

**Same bug, second site:** `streams/SimplicialSetStream.scala:58` (`fromStream`) builds `faces(g)` with the same
`zipWithIndex` → `Set` pattern. `faces(g)(i)` is not `d_i` for ≥5 vertices **[probe]**, so both
`FiniteSimplicialSet_is_OrderedCell`'s signs and `validate()`'s identity check are wrong on those generators.

**Why tests missed it.** Every signed-field (ℝ/F3) fixture has ≤4 vertices: triangle, tetrahedron, torus, RP². The
standing "F2 hides sign errors" lesson was applied, but only below the size where `Set` stops preserving order.

**Fix (not applied):**
- Use `(0 until spx.size).map(i => (spx.dropIndex(i), sign(i)))`, or `spx.toIndexedSeq.indices`, in both sites.
- Separately, make `SimplexOps.zip`/`zipWithIndex`/`zipAll` return `IndexedSeq`, or delete them. A `Set`-returning
  `zip` on an *ordered* vertex type is a trap by construction.
- Add a regression test: a ≥5-vertex `∂∂ = 0` check over F3, plus the F3-vs-F2 torsion-free barcode check from the
  appendix.

### 1.2 `Ordering[BarcodeEndpoint]` violates the `Ordering` contract **[reading]**

`barcode/Barcode.scala:40–41`: `case NegativeInfinity() => -1` and `case PositiveInfinity() => +1` fire regardless
of `y`. So `compare(+∞, +∞) = +1` and `compare(−∞, −∞) = −1`, which is not reflexive or antisymmetric.

`Barcode.imageMatrix`/`cokernel` sort endpoint lists with `sortBy(_._1)`. With several essential bars this gives an
unspecified order, and on large inputs TimSort can throw "Comparison method violates its general contract".

The fix is to match on `(x, y)` pairs, with equal infinities returning 0.

### 1.3 `Alpha(pts, dispatch)` has no default case, so a `MatchError` reaches users **[reading]**

`alpha/AlphaShapes.scala:31–38` matches only `"default"`, `"helix"` and `"DQP"`. `matlab/TDA4j.scala:244` is the one
option the facade does *not* lowercase: `alphabackend=dqp` from MATLAB/CLI is a `scala.MatchError`, not an
`IllegalArgumentException`. The CLI catches only the latter, so the user sees a stack trace.

Also, the `"default"` arm has three branches that all return `"helix"`: dead branching.

### 1.4 `SymmetricZomorodianIncremental.apply` is `???` **[reading + grep]**

`streams/SymmetryGroup.scala:267/270`. The public API throws `NotImplementedError` on first call. Its only test
(`SimplexIndexingSpec:206`) is `.pendingUntilFixed`, so it "passes" by throwing. The class scaladoc links
`[[ZomorodianIncremental]]`, which doesn't exist.

### 1.5 Legacy `zeroApparentFacet` compares the wrong index **[reading]**

`streams/RipserStream.scala:462` (`RipserStreamSparse`) and `:561` (`RipserStreamBase`): the check is
`facet <- zeroPivotFacet(index); cofacet <- zeroPivotCofacet(facet); if facet == index`. The intended check is
`cofacet == index`.
- **Why it misfires:** `facet` and `index` are combinatorial indices from *different* dimensions, and those can
  coincide numerically. For example, edge {0,1} has index 0 and so does its facet {0}.
- **Result:** the function returns `None` or a wrong cofacet. These are dead legacy classes (§5.1), but this is
another reason to delete rather than keep them.

### 1.6 Latent hazards **[reading]**, not live on current call paths

- **`ExplicitStreamBuilder` fallback `Filterable` (`SimplexStream.scala:118–123`).** When no `Filterable` given
  exists, the anonymous class evaluates `filtrationValues.maxBy` during construction. `filtrationValues` is declared
  *after* `filterable`, so it is still `null`, giving an NPE. Even if it weren't, the map would be empty. Reachable
  only for a filtration type with no `Filterable` instance. It exists solely to support a global
  `given ... => Option[Filterable[F]]` (§6.3).
- **`CofacetIterator.finishInit` (`Cofacets.scala:90–95`)** removes from `vertexCache` while iterating
  `vertexCache.keys`, which is undefined for `mutable.HashMap`. Only its own spec uses this class.
- **`Chain.items` returns uncollapsed entries.** Deferred arithmetic means `(a + a).items` has two `a` terms.
  `CellularCohomologyContext.coboundaryOfChain` does `chain.items.toMap` (`Cohomology.scala:209`), which silently
  keeps one coefficient per cell on an uncollapsed chain. Every current caller passes collapsed V-columns, so it is
  not live, but `items` should collapse first or be renamed `rawEntries`.

### 1.7 Stale "representative gap" claims in user-facing text **[grep]**

Every engine now emits `Some(rep)` on every bar. `PackedRipserCohomology.scala:378/391/397` has `Some(vcol)` on all
three `bars.append` sites, including the apparent-pair branch. No test requires the throw either: `TDA4jSpec:370`
only tolerates it.

Yet three places still describe the gap:
- `matlab/TDA4j.scala:599`: the `UnsupportedOperationException` message, now in a dead `None` branch;
- `cli/TDA4jConf.scala:47`: the `--representatives` help text says ripser has unrepresented bars;
- `.claude/CLAUDE.md:114`, "Current gaps".

Worth a second look only if the apparent-pair representative `Chain(sigma)` isn't considered a real
representative. As a cohomology V-column it is the standard one.

---

## 2. Structural: the ordering contract lives in convention, not code

CLAUDE.md's stream rules 1–4 are the most-documented invariant in the project, and the #1 source of historical
crashes. They are enforced by every stream re-implementing them correctly.

**Copies of the stream `filtrationOrdering` convention** **[grep + reading]**. The rule is primary key = filtration
value reversed, then dimension, then a canonical tie-break.

| site | status |
|---|---|
| `EnumeratingCofaceSimplexStream.filtrationOrdering` (`SimplexStream.scala:358`) | canonical (colex) |
| its `sortedByFiltration.memoOrdering` (`:397`) | copy-paste of the above, with memoized lookups |
| `CubicalGridStream.filtrationOrdering` (`CubicalStream.scala:112`) | copy |
| `ExplicitCubicalStream.filtrationOrdering` (`:178`) | copy (CLAUDE.md: "independent duplicate, cross-checked") |
| `simplicialSetFiltrationOrdering` (`FilteredSimplicialSetStream.scala:15`) | **the generic combinator: `(fv, dimOf, tieBreak) => Ordering`**, used only by its own file |
| `FilteredSimplexOrdering` (`SimplexStream.scala:150`) | lex tie-break; its tie-break block is duplicated in two branches |
| `RecursiveStackVietorisRipsSimplexStream` (`VietorisRips.scala:129`), `HelixDelaunay` (`AlphaShapes.scala:342`) | `Ordering.by(fv).reverse.orElse(simplexOrdering)`, **no dimension key**, contrary to rule 1 |
| `AlphaComplexDQPBuilder.cellOrdering` (`AlphaComplexDQP.scala:956`) | must be the exact reverse of `AlphaShapeDQP.filtrationOrdering`, **defined in a different class** |
| `AlphaComplexDQP.cells` (`:757`) | a third alpha ordering, **tie-broken on `c.show` (a string)**, the exact thing rule 2 warns against. Its doc calls it "Filtration order"; unused by engines |

The Ripser engines' ascending cohomology comparators (`compareFvThenIndex`, `compareDiamThenIndex`) are deliberately
a different convention, already shared within each engine, and are not counted here.

**Comments already testify to the cost.** The `memoOrdering` doc says it "delegates to the exact same compare logic
above … so it cannot silently diverge from it". It doesn't delegate; it is a copy. It can diverge.

**Recommendation.**
1. Promote `simplicialSetFiltrationOrdering` to a named home, e.g.
   `object FiltrationOrdering { def canonical[C](fv: C => Option[Double], dim: C => Int, tieBreak: Ordering[C]) }`
   in `SimplexStream.scala`. Replace every copy above with it. `sortedByFiltration` passes its memoized `fv`/`ix`
   into the *same* combinator instead of re-typing the comparator.
2. Make rule 2 unviolable. `StratifiedCellStream` owns the bucket sort; implementations supply unsorted cells. For
   example:

   ```scala
   def maxDimension: Int
   protected def cellsOfDimensionUnsorted(d: Int): IterableOnce[CellT]
   protected def sortBucket(cells: IterableOnce[CellT]): Vector[CellT] =   // override hook for memoized sorts
     cells.iterator.toVector.sorted(using filtrationOrdering.reverse)
   final def cellsOfDimension(d: Int): Iterator[CellT] =
     if d < 0 || d > maxDimension then Iterator.empty else sortBucket(cellsOfDimensionUnsorted(d)).iterator
   override final def iterator: Iterator[CellT] = (0 to maxDimension).iterator.flatMap(cellsOfDimension)
   ```

3. Make rule 4 unrepresentable. The `iterateDimension: PartialFunction[Int, Iterator]` + `isDefinedAt` design is
   exactly what produced the contiguity/`Int`-wraparound/infinite-loop bugs that `StratifiedCellStream`'s 20-line
   doc now warns about. `maxDimension: Int` + `cellsOfDimension` has no way to express a gap. `iterateDimension` can
   stay as a deprecated derived member while tests migrate.
4. The same `PartialFunction` pattern on `filtrationValue` is already hollow in practice:
   - `AlphaShapeDQP.filtrationValue = alphaComplexDQP.radiusOf(_)` is an eta-expanded function, so `isDefinedAt`
     is always true (the same hazard its own `iterateDimension` comment warns about).
   - `HelixDelaunay`'s `{ case spx => … }` is the same.
   - Every comparator still pays `isDefinedAt` + `apply` per comparison.
5. Optional and bigger: the "generic-given capture" gotcha exists because `Chain`'s `Ordering[CellT]` is a plain
   implicit that a global `given [CellT: OrderedCell] => Ordering[CellT]` can satisfy. A distinct wrapper type for
   the filtration order, which `Chain.reduceBy` requires inside engines, would turn that bug class into a compile
   error. The ripple is large; only worth it if the bug class recurs.

---

## 3. Structural: code rationale lives in agent-session logs

**Numbers** **[grep]**:
- Comments are 39% of non-blank `src/main` lines (3,677 comment vs 5,687 code).
- Per file: `Homology.scala` 57% (916 vs 699); `CechStream.scala` 68%; `Cubical.scala` 63%; `Cohomology.scala` 60%.
- `src/test` is 22%.
- Production source contains 108 `WORKLOG-*` references, 33 `CLAUDE.md` references, 7 "advisor", 6 "this
  session", and "last session's version" (`Homology.scala`, `persistentCohomology`).

**The problem is not verbosity per se.** The comments narrate *how things were found*: profiler percentages, which
spec caught what, rejected drafts, which session changed what. That is the content CLAUDE.md itself says belongs in
worklogs. It pushes the invariants a reader needs (which are in there) below a lot of history. It is also invisible
to scaladoc readers and outside contributors, who don't know `.claude/` exists.

**Rot is already visible** **[grep + reading]**:
- **Dangling CLAUDE.md section names.** Five references point at sections the 2026-09-22 condensation removed:
  "Bug found while cross-validating" (×4) and "Naive-engine scaling" (×1).
- **Verification cited to a spec that doesn't exist.** `RepCycleAuditSpec` (`Homology.scala:455, 547, 624, 651`) is
  cited as the check behind chunks' representatives (e.g. "verified by `RepCycleAuditSpec`"), but it was a deleted
  throwaway.
- **`sortedByFiltration`'s doc (`SimplexStream.scala:378–391`)** says it is "deliberately NOT a stream-lifetime
  filtrationValue cache". The same class grew exactly such a cache 60 lines above it (`:320`), so the per-call
  `fvCache` is now redundant on the default path.
- **`containingTopCells` (`CubicalStream.scala:65–71`)** says "not memoized here on purpose (see class doc's note on
  deferring optimization until measured)". The class doc has no such note, and `filtrationValue` *is* memoized
  (`:98`).
- **Location pointers that no longer point anywhere.** "line ~86 above" (`Homology.scala:167`) points at nothing
  (the fold is ~60 lines further down). "the class doc below" (`RipserStream.scala:110`) refers to a class doc that
  doesn't exist.
- **The facade and CLI help text claim a representative gap** that no longer exists (§1.7).
- **Stacked scaladoc.** Three places have two or three consecutive `/** */` blocks, and scaladoc attaches only the
  last:
  - `RipserCohomologyContext` (`Homology.scala:1109–1207`): ~70 lines orphaned from the generated docs;
  - `SimplexIndexing.binomialRows` (`RipserStream.scala:85–115`);
  - `PackedRipserCohomologyContext.insertionDiameter` (`PackedRipserCohomology.scala:~124–156`).
- **Size mismatches:**
  - `binomial` has a 40-line doc on a 9-line function;
  - the top-level `min`/`max` have a 23-line comment on 2 lines;
  - `object Cube` has a 25-line naming-history comment before its first extension.

**Recommendation.** Do one pruning pass per file with one rule.
- **Keep:** the invariant or contract; a *why* that isn't obvious from the code; paper citations and equation
  numbers; a one-line `See WORKLOG-x.md` pointer where the derivation matters.
- **Move to the worklog (or drop, since git has it):** how the bug was found, measurements, session references,
  rejected alternatives, and "confirmed empirically, not just reasoned through".
- **Priority order:** `Homology.scala` → `SimplexStream.scala` → `RipserStream.scala` → `PackedRipserCohomology.scala`
  → `CechStream.scala` → cells.
- **[estimate]** More than half of the 3,677 comment lines are history rather than invariant. `Homology.scala` alone
  could plausibly lose ~500 lines without losing any rule a maintainer needs.
- **Caution:** some load-bearing invariants are *only* stated inside the narrative, e.g. `vcol.collapseAll()` must
  precede the writes, and `eliminationFallback`'s paired branch must use a real V-column. Extract those as short,
  standalone statements before deleting the story around them.

---

## 4. Structural: no common engine interface

| engine | construction | input | `maxDim` | output |
|---|---|---|---|---|
| naive | `CellularHomologyContext[CellT, C, F]()` | `CellStream[CellT, F]` (by-name) | none: caller wraps `LimitedCofaceSimplexStream(…, k+1)` and drops `dim == k+1` | incremental `HomologyState` → `diagramAt` tuples / `barcodeAt` bars |
| chunks | `CellularPersistenceInChunksContext[CellT, C](maxDim = 5)` | `StratifiedCellStream[CellT, Double]` (Double only) | `maxDim`, default **5** | `HomologyState` → `diagramAt` / `barcodeAt` |
| by-dimension | `SimplicialHomologyByDimensionContext[V, C]` | `StratifiedCellStream[Simplex[V], Double]` | `advanceTo(dim, f)` | `HomologyState` (reps known wrong) |
| Ripser ×2 | `…Context[C](metricSpace, maxDimension, …)` | a metric space, not a stream | `maxDimension` | `persistentCohomology(): List[PersistenceBar]` |
| cellular cohomology | `CellularCohomologyContext[CellT, C, F]()` | `CellStream[CellT, F]` (by-name) | none: same caller-side `+1`/drop | `List[PersistenceBar]` |

**Consequences** **[reading]**:
- **`matlab/TDA4j.computeGeneric`/`computeCubicalGeneric` is a 3-complex × 4-engine string match.** The
  naive+`Limited(+1)` arm appears 3×, cohomology 3×, chunks 2×.
- **Complex/engine compatibility is validated twice:** up-front `if`s, then the fallthrough `case other`.
- **The `maxDim`-semantics workaround is a caller responsibility for two engines.** That is how
  `WORKLOG-maxdim-semantics-fix.md`'s bug class arose in the first place.
- **`persistentHomology(stream: => …)` by-name parameters are evaluated more than once.** The naive engine reads
  `stream` and `stream.smallest` (`Homology.scala:253–261`), so a caller passing a constructor expression builds the
  stream twice. By-name buys nothing here.

**Recommendation** (sketch, not a design):
- **A shared trait** along the lines of
  `trait PersistenceEngine[CellT, C] { def barcode(stream: StratifiedCellStream[CellT, Double], maxDim: Int): List[PersistenceBar[Double, Chain[CellT, C]]] }`
  with a small adapter per engine. The naive and cellular-cohomology adapters own the `+1`/drop, and the incremental
  API stays available on the concrete class.
- **Ripser doesn't fit a stream-in interface.** It consumes a metric space. Its adapter sits at the
  "VR-from-metric-space" level, and that is an honest asymmetry, not a flaw.
- **Parse facade options into `enum Complex`/`enum Engine`/`enum Coefficients` once at the boundary**, then dispatch
  on types. The facade should shrink substantially **[estimate]** and the CLI stays a translator.

---

## 5. Organization: dead, legacy, experimental and oracle code in `main`

### 5.1 Delete (or move to an `experimental`/test tree)

All usage checked by grep.

| what | where | used by |
|---|---|---|
| `RipserCliqueFinder`, `RipserStreamSparse`, `RipserStreamBase`, `RipserStream`, `object RipserStream {}`, `RipserStreamOf`, `SymmetricRipserCliqueFinder`, `SymmetricRipserStream`, `MaskedSymmetricRipserVR`, `MaskedSymmetricRipserStream` (~290 lines under the file's own "Maybe @deprecate or outright everything below here?" banner) | `RipserStream.scala:411–699` | `SimplexIndexingSpec`, `BarcodeSpec` only; nothing in main |
| `binomialApache`, `binomialBigint` | `RipserStream.scala:15–29` | nothing |
| `SymmetricZomorodianIncremental` (`???` body) | `SymmetryGroup.scala:234` | one `pendingUntilFixed` test |
| `CofacetIterator` | `Cofacets.scala` | its own spec |
| `unicode` package (`PrintingHelper.scala`) | whole package | nothing |
| `ScalaPointSet` and the `Miniball`/`ArrayPointSet`/`QRDecomposition`/`Random`/`java.util.concurrent` imports it drags in | `AlphaShapes.scala` | nothing. The reason CechStream gives for duplicating it (`MiniballPointSet`) is the dependency direction, and that direction *permits* alpha to reuse streams' copy |
| `pruneAllCofaces`, `finishedCurrent` | `SimplexStream.scala` | never read |
| `ExplicitStream`'s IDE-stub comments ("Members declared in org.appliedtopology.tda4j.SimplexFiltration", which is not a type that exists) and fully-qualified types | `SimplexStream.scala:89–104` | n/a |

**Dependencies** (`build.sbt`) **[grep]**:
- `kiama` + `kiama-extras`: zero imports anywhere.
- `scala-graph` `graph-core` (a `for3Use2_13` Scala-2.13 cross-build, the riskiest kind of dependency to carry): its
  only appearance is three imports in `VietorisRips.scala:11–13`, and no symbol from them is used.

Removing all three is presumably free. Verify with `sbt compile`; this review didn't.

### 5.2 Oracles shipped as public API

- **`RipserCohomologyContext` (a decision for the project lead).** It is "test/reference oracle only" by its own
  doc and CLAUDE.md, and has no non-comment reference in `main` **[grep]**. But its class doc also records a
  deliberate call: a legible paper-algorithm reference implementation has standing value of its own.
  - **Move it to `src/test`, next to `PackedRipserCohomologySpec`,** if that value is for developers only.
  - **Keep it in `main` with a prominent "reference implementation, not for production" scaladoc header** if users
    should be able to read it.
- **`SimplicialHomologyByDimensionContext`** is "unwired, kept purely as an independent birth/death oracle" and its
  representatives are known wrong. Publishing a class whose chains are documented as incorrect is worse than not
  publishing it. Move it to `src/test`.
- **The alternate VR constructions** (`InorderCofaceSimplexStream`, `RecursiveStackVietorisRipsSimplexStream`,
  `IncrementalVietorisRipsSimplexStream`) are legitimately cross-validation baselines. Keep them, but say so in
  their scaladoc headers rather than in CLAUDE.md only. The alpha streams also use `RecursiveStack…`, so it can't
  move anyway.

### 5.3 File/package placement

- **`RipserStream.scala`'s real content is `SimplexIndexing` + `binomial` + cursors.** Once §5.1 is done, rename the
  file to `SimplexIndexing.scala`.
- **`CubicalHomologyContext` lives in `streams/CubicalStream.scala`.** It is a one-line subclass of a `homology`
  class, and the *only* `streams → homology` edge in the package graph. Moving it to `homology` makes the layering
  acyclic.
- **`Cell`/`OrderedCell`/`OrderedBasis`/`HasDimension` live in `Chain.scala`.** Move them to their own
  `algebra/Cell.scala`.
- **Generic top-level names sit in packages that everything wildcard-imports:** `product`, `coproduct`, `quotient`,
  `identify`, `elementsAtDim` (cells), `fromStream`, `binomial` (streams), `type Point`, `Alpha` (alpha). Given the
  documented top-level-name collision hazards (CLAUDE.md, extension companions), these belong in objects, e.g.
  `FiniteSimplicialSet.product`, `SimplexIndexing.binomial`, `AlphaShapes.apply`.
- **The simplicial-set constructions are mathematically clean but are free functions.** An
  `object SimplicialSetConstructions`, or the companion, would scope them.

### 5.4 `SymmetryGroup.scala` (503 lines) is a research sketch in the production `streams` package

- `ExpandList` is O(#representatives) per `apply` and O(orbit²) per iteration: `currentOrbit.toList(currentElement)`
  runs per element.
- `orbit = orbitPar` spawns one `Future` per group element on the global EC and `Await.result(…, Duration.Inf)`s. For
  hypercube symmetries that is n! tiny futures per orbit.
- `HyperCubeBitSet` and `HyperCube` are parallel `BitSet`/`Int` duplicates; the `//123 //132 …` scratch comment is
  still there.
- `HyperCubeSymmetryGeneratorsBitSet.generators` includes a swap of bit `bitlength-1` with the nonexistent bit
  `bitlength`; the `Int` twin correctly stops at `bitlength - 1`.

If `HyperCubeSymmetry` is the one thing CLAUDE.md cares about, split it out and move the rest to an `examples`/test
tree.

### 5.5 Docs depend on §5.1/§5.2 targets, and the user guide leads with legacy code **[grep]**

Under CLAUDE.md's four-surface rule, every deletion or move in §5.1/§5.2 needs a matching docs edit. Paradox hits:

| class | Paradox references |
|---|---|
| `RipserStreamBase` | `architecture.md`, `class-diagrams.md` ×2, `gotchas.md`, `persistence-engines.md` |
| `RipserStreamSparse` | `class-diagrams.md` |
| `RipserCliqueFinder`, `SymmetricRipser…` | `architecture.md` |
| `RipserStream(` | **`user-guide/index.md:50`** |
| `SimplicialHomologyByDimensionContext` | `class-diagrams.md`, `persistence-engines.md` ×2, `user-guide/index.md:250` (framed correctly, as a cross-validation class) |
| `RipserCohomologyContext` | 11 developer-guide references, plus 1 in the user guide |
| `InorderCofaceSimplexStream` | `architecture.md` ×2, `class-diagrams.md` |

**User-facing finding:** the user guide's *headline* "full Vietoris-Rips persistence computation" example
(`user-guide/index.md:40–53`) builds a legacy `RipserStream`. That class is in the "Maybe @deprecate" block (§5.1)
and is not one of the streams CLAUDE.md lists as satisfying the ordering contract. The next paragraph then explains
how its `maxFiltrationValue` default differs from the maintained streams'.
- **Result:** new users are routed to the least-maintained VR construction.
- **Fix:** switch the example to `EnumeratingCofaceSimplexStream` (or `RipserCofaceSimplexStream`) before deleting
  the legacy classes.
- **Not checked:** whether Paradox snippets are compiled. They are plain fenced blocks, not `@@snip`, so probably
  not, which means the example may already have drifted.

---

## 6. Idiom findings (Scala 3), by package

### 6.1 algebra

- **`FiniteField`** carries dead `Numeric`/`Fractional`-era members:
  - `compare`, `fromInt`, `parseString`, `toFloat`, `toLong`;
  - `toInt(x: Fp): Fp`, which returns `Fp`, not `Int`;
  - "Members declared in java.util.Comparator" comments;
  - `val xx = x` no-ops;
  - unused imports.

  Its `op2(_ + _)(x, y)` allocates a closure per arithmetic operation on the hottest path (`Chain` reduction); JIT
  may or may not remove it, unmeasured. Write `norm(x + y)` directly. `Fp.apply` doesn't normalize, so `Fp(-1)` and
  `Fp(p-1)` are different representations until `norm`.
- **`Field.DoubleApproximated`** routes every operation through a `Fractional[Double]` instance for no reason, and
  its epsilon is absolute. It is named like a type but is a factory `def`.
- **`RingModule`** has five spellings of scalar multiplication: `<*`, `mul`, `|*|`, `⊠`, `scale`. Its doc still
  says "the [Numeric] (ie ring-like) type R". Pick one symbolic and one alphabetic.
- **`Chain`'s internals are public and mutable, and query-looking methods mutate:**
  - `var entries: mutable.PriorityQueue` is public mutable state;
  - `isZero()` and `equals` mutate their receivers (collapse), and `equals` also mutates its *argument*;
  - `items` exposes uncollapsed duplicates (§1.6);
  - `leadingTerm: (Option[CellT], CoefficientT)` should be `Option[(CellT, CoefficientT)]`, and its implementation
    (`{ (x: …) => x.copy(…) }.apply(…headOption.unzip)`) is needlessly obscure;
  - `OrderedBasis` is a typeclass with exactly one instance;
  - the `reductionLog` parameter still carries a "want to have a default empty here?" note;
  - three unused imports.
- **Naming conventions are mixed:** `chain_is_ordered_basis`, `Simplex_is_OrderedCell`,
  `default_Simplex_is_OrderedCell`, `default_Cube_is_OrderedCell` versus anonymous givens elsewhere. Scala
  convention is lowerCamelCase: `simplexIsOrderedCell`, or simply anonymous.

### 6.2 cells

- **`SimplexOps` forwards ~60 `SortedSet` methods, many as redundant aliases:**
  - `head`/`first`/`firstKey`;
  - `headOption`/`firstOption`;
  - `union`/`|`, `incl`/`+`, `excl`/`-`, `concat`/`++`.

  An opaque type that re-exports its representation wholesale buys zero-cost boxing (legitimate, since `Simplex` is
  a type argument everywhere) but no encapsulation: `underlying` and `asSimplex` are public too. Trim the list to
  what's used. The `Set`-returning `zip*` must go (§1.1).
- `dim` is defined twice: `SimplexOps.dim` and the `OrderedCell` instance's `dim`.
- **Ordering helpers are hand-rolled.** `wordOrdering`, `ssetElementOrdering`, `productGeneratorOrdering` and
  `eitherOrdering` are hand-written comparators. `Ordering.by(p => (p.x, p.y))` over `Ordering.Implicits.seqOrdering`
  would do, and `wordOrdering` uses `x - y` (fine for small ints, but a habit worth dropping).
- **`FiniteSimplicialSet(val ord: Ordering[G])(…)`** takes its ordering as an explicit first parameter list,
  unlike every other class's context bound.

### 6.3 Global givens, and the wildcard-import convention

`import pkg.{given, *}` everywhere means every package-level given is ambient in every file. Currently these include:
- `given Epsilon = Epsilon(1e-5)` (alpha, top level). A numerical tolerance as a global given; the MATLAB facade
  then defines its own private duplicate.
- `given orderingBitSet: Ordering[BitSet]` (streams). An orphan instance for a stdlib type.
- `given [F: Filterable] => Option[Filterable[F]]` (streams). **A given `Option`**, which exists only to fake an
  optional `using` default in `ExplicitStreamBuilder`. Use `scala.compiletime.summonFrom` or `NotGiven` instead.
- `DoubleIsFilterable`/`FloatIsFilterable`/`IntIsFilterable`/`ShortIsFilterable`/`LongIsFilterable`. `Filterable`
  also has an unused `FiltrationT: Ordering` bound, and `Filtration extends Filterable` makes a filtration *be* a
  sentinel provider. That is composition expressed as inheritance: `SimplexStream` also `export`s a separately
  summoned `filterable`, so there are two routes to the same values.

**Recommendation:** keep the wildcard-import convention, but move these instances into companions, which are found
through implicit scope without any import. For tolerances, pass an explicit parameter.

### 6.4 streams

- **Case classes used for stateful iterators and services** make `equals`/`hashCode`/`copy` meaningless or wrong on
  mutable state:
  - `TopCofacetEnumerator`, `RecursiveStackSimplexEnumerator`, `CofacetIterator` (iterators);
  - `JVPTree`, `BruteForce`, `SparseMetricSpace` (services);
  - `HomologyState` in all three homology engines (§6.5).

  Use `final class`.
- **Leaky, mutable stream internals:**
  - `CofaceSimplexStream` exposes its cache internals as its *public interface* (`currentDimension`,
    `lastDimensionCache`, `currentDimensionCache`, `pruneAllCofaces`, `keepCriterion`), and
    `LimitedCofaceSimplexStream` must forward every one of them;
  - `EnumeratingCofaceSimplexStream(var keepCriterion …)` is a public mutable constructor parameter;
  - `LimitedCofaceSimplexStream` is hardcoded to `CofaceSimplexStream[Int, Double]`, so it can't truncate a
    cubical, simplicial-set, or alpha stream. The rule-4 redesign in §2 gives a generic `truncated(maxDim)` for
    free.
- **Implementation inheritance.** `Ripser…`, `Inorder…` and `Incremental…` all extend `EnumeratingCofaceSimplexStream`
  to reuse its filtration and ordering code, and `CechCofaceSimplexStream` extends `RipserCofaceSimplexStream`.
  - `IncrementalVietorisRipsSimplexStream` declares its own `private val resolvedMaxFiltrationValue` (`:635`),
    shadowing the parent's `protected` one (`:271`). The inherited helpers read the parent's (enclosing radius); the
    child's own `isEdge` reads its own. That is two thresholds on one object **[reading]**; harmless today only
    because the child never calls the inherited threshold helpers.
  - Extracting a `VRFiltration` component (fv + memo + ordering + `sortedByFiltration`) and composing it would
    remove the shadowing and the name confusion: "Enumerating" is also the base class of the non-enumerating
    streams.
- **Duplicated "generate the entire previous dimension" block:** Enumerating, Ripser, and Inorder each have one.
  `Some(x).filter(p)` inside `flatMap` is `collect`/`filter`.
- **Side effects inside a `for … yield`.** `InorderCofaceSimplexStream.iterateDimension` mutates
  `currentDimensionCache` there, so the cache fills lazily as the caller consumes. It is fragile if the iterator is
  only partly consumed.
- **Performance traps that come from idiom** **[reading]**, unmeasured:
  - `IntMetricSpace.distance` calls `metricSpace.elements.toIndexedSeq(x)`, an O(n) conversion *per distance
    lookup* (`FiniteMetricSpace.scala:90`);
  - `EuclideanMetricSpace.neighbors` uses `pts.indexOf(_)`, an O(n) reference scan per hit (`:149`);
  - `MaximumDistanceFiltrationValue.apply` builds a `SortedSet[Double]` of all pairwise distances to take its `max`.
- **Kruskal/UnionFind** (`UnionFind.scala`):
  - `union` links `yr` to `x`, not `xr`, and there is no path compression or rank;
  - `var xr/yr` should be `val`s;
  - the MST tie order depends on `HashMap` key iteration;
  - `cycleToChain` duplicates its path-walk for `s` and `t`.

  Only oracles and legacy use it, so this is low priority.
- **Old-style `Iterator` code in `VietorisRips.scala`:** `hasNext()` with parentheses overriding `Iterator.hasNext`;
  `@tailrec` combined with `return`s and an unreachable `return false`; snake_case `neighbor_it`/`edge_it`.
- **`CofacetIterator` uses exceptions for control flow** (`Try { … } match case Failure(_: IndexOutOfBoundsException)`)
  and still contains exploratory comments ("... noooo, that's not right ...").

### 6.5 homology

- **Mutable engine state as a `case class`.** `HomologyState` is a `case class` with `mutable.Map` fields and `var`s
  in all three engines. `copy()` would share the mutable maps.
- **`CellIterator`** is a PascalCase `val`.
- **The reduce → V-column-fold → record pattern appears five times:**
  - naive `advanceOne`;
  - chunks `vcolOf`;
  - both Ripser engines;
  - `CellularCohomologyContext`.

  The V-column fold differs subtly between them (naive and chunks skip fallback-eliminated terms). A shared helper
  with that difference as an explicit parameter would make the difference *visible* instead of rediscovered each
  time. Worth doing only alongside §4.
- **`insertionDiameter`** is byte-identical in both Ripser engines. Make it one top-level `private[homology]`
  function.
- **The chunks `HomologyState`** is a ~650-line class with ~15 mutable fields; the local/global phases, the
  reconciliation step, and `vcolOf` all share them. It's correct and pinned by tests, so don't restructure it for
  its own sake. The comment pass (§3) is the high-value change here.

### 6.6 barcode

- **Side-effecting `for … yield`.** `imageMatrix`, `cokernelMatrix` and `reduceMatrix` use `for … yield
  setEntry(...)` for side effects, which allocates a `Seq[Unit]`. `reduceMatrix` mutates its argument while the
  comprehension's guards read it.
- **`Barcode[F: {Ordering, Numeric}, A]` is a stateless class instantiated only to call methods.** Make it an
  `object` with method-level type parameters.
- **`BarcodeContext`'s scaladoc is scalafmt-reflowed gibberish** ("3 dim 2 clop 5 3 dim 2 clcl 5 …").
- **Unneeded context bounds and redundant `val`s.** `PositiveInfinity[F: Ordering]()` and the other endpoint case
  classes carry an `Ordering` context bound they don't use, and `PersistenceBar`'s `val` parameters are redundant
  on a case class.
- **Unused imports.** `barcode/Barcode.scala` imports `cells`, `streams` and `homology` givens, but only `algebra`
  (`Chain`) is used by the type alias at the bottom.

### 6.7 alpha

- **`HelixDelaunay` runs its whole algorithm in the constructor body**, ~150 lines of `while`/`var` in class
  initialization. The consequences:
  - an `assert` failure surfaces as a construction exception;
  - the initialization order (`filtrationValuesMemo` must precede `simplicesSortedMap`) is load-bearing and
    implicit;
  - it can't be tested piecewise;
  - `Random.shuffle` is **unseeded** (`AlphaShapes.scala:123`). Given the documented ~1/170 order-dependent failure,
    unseeded randomness also makes that failure non-reproducible.
  - `LinkedBlockingDeque` (`:184`), a blocking concurrent queue, is used single-threaded;
  - there are commented-out `println`s throughout.

  A `HelixDelaunay.compute(points): DelaunayResult` factory with an explicit seed would fix this.
- **`AlphaComplexDQP` uses `null` as a sentinel**, contrary to the project's own "Option over sentinels" rule:
  - `weights: Array[Double] = null` (`:156`, `:198`);
  - `witnessOf`/`coordsOf` return `null` (`:1103`, `:1115`).
- **Silent defaults for cells not in the complex:** `filtrationValue`/`radiusOf` return 0.0.
- **The library logs to `System.err`** (`:1084`).
- `AlphaShapes` has two unrelated `Ordering`s it must keep consistent with the builder (§2).

### 6.8 matlab / cli / io

- **matlab:** see §4 for the string dispatch. `alphaBackend` is not lowercased (§1.3). The `private given
  epsilonForAlpha` duplicates the global one.
- **cli:**
  - `perseus-cubical` and `image` load a `CubicalGridStream`, flatten it back into arrays (`flattenGridStream`), and
    the facade then rebuilds a stream. `io.Perseus` lacks the raw-array layer that `io.Dipha.readImageData` has; add
    it and the round trip disappears.
  - The CLI catches only `IllegalArgumentException`, so `FileNotFoundException`/`MatchError` print stack traces.
- **io:** the cleanest package in the codebase (§8). The one inconsistency is parameter types: readers return
  `Array[Array[Double]]`, while writers take `Seq[Seq[Double]]` in some formats and `IndexedSeq[IndexedSeq[Double]]`
  in others.
- **root:** `package object tda4j` is the Scala-3-deprecated form; use top-level definitions. It also imports five
  packages and six barcode names, most unused. `TDAContext` is used by `APISpec` only.

---

## 7. Tests: quick pass only

- 22% comment ratio: healthier than main.
- **The root `SimplicialSetSpec.scala` is 58 lines, all commented out**, referencing types that no longer exist.
  Delete it; git has it. Note that CLAUDE.md lists it as "dead, fully commented out", so it's known, just not
  removed.
- **Duplicated helpers:**
  - `randomCloud` ×4;
  - `asStratified` ×2 *inside `HomologySpec` alone*;
  - `explicitStream` ×2;
  - `naiveBars` ×2.

  Both `asStratified` copies violate stream rule 2 (their buckets come from `groupBy`, not
  `sorted(using filtrationOrdering.reverse)`). Test fixtures that break the contract they exist to test are the
  wrong kind of shortcut. A shared `StreamFixtures` object would fix the duplication and the contract violation
  together.
- **Six `…ProfileDriver` objects with `main`, and seven flag-gated benchmark specs, live in the unit-test tree.** An
  sbt `bench` subproject, or at least a separate source directory, would keep `sbt test` compile time and scope
  honest. The `-DrunBenchmarks` gating works, so this is cosmetic.
- **`SimplexIndexingSpec:206`**'s `pendingUntilFixed` over a `???` implementation is a test that can never pass
  (§1.4).
- **No test builds a simplex with ≥5 vertices over a signed field**, which is how §1.1 survived.

---

## 8. What's good

These are unhedged strengths, not politeness.

- **`io`**: small, uniform, primary-source-verified formats. Shared arithmetic is factored out properly
  (`DistanceMatrices`, `BinaryIO`, `Endpoints`, all `private[io]`). The "refuse rather than guess" stance on
  unverified formats is exactly right.
- **The simplicial-set layer** (`SSetElement`, `FiniteSimplicialSet`, constructions) reads like the mathematics.
  `validate()` returns human-readable violations instead of throwing, and `quotient`'s one-step `require` is the
  right guard.
- **The cubical encoding and `CubicalImage`** are clean, well-typed, and explain their conventions once.
- **The package reorg produced a mostly acyclic layering** (one stray edge, §5.3), and the `{given, *}` convention is
  applied consistently.
- **Modern Scala 3 syntax is used consistently and correctly:** `is`-typeclasses, named context bounds, opaque
  types, `enum`s in the CLI.
- **The comments, for all §3's criticism, do contain the real invariants.** The problem is signal-to-noise and
  location, not absence. That makes the fix cheap.

---

## 9. Recommended order of work

1. **§1.1 fix + tests.** One-line fixes in two sites, drop the `Set`-returning `zip*`, and add ≥5-vertex F3 `∂∂ = 0`
   and F3-vs-F2 barcode regression tests. This is the item that can change users' answers: barcodes on synthetic
   complexes **[probe]**; through the facade only at dimension ≥ 4, which is plausible but not demonstrated.
2. **§1.2, §1.3, §1.7.** Small and user-visible: endpoint ordering, the alpha `MatchError`, stale help text.
3. **§5.1 deletions + unused dependencies,** with the matching Paradox edits (§5.5). Rewrite the user-guide VR
   example first. Mechanical, and they shrink everything that follows.
4. **§2: one ordering combinator; the stream trait owns sorting and bounds.** Highest leverage against the
   historical bug class.
5. **§3 comment pass**, file by file, extracting buried invariants first.
6. **§4 engine trait + enum-typed facade.**
7. **§6 idiom cleanups**, opportunistically, alongside whichever of the above touches the file.

---

## 10. CLAUDE.md drift found (NOT applied: CLAUDE.md is mid-condensation, uncommitted)

- **Line ~257:** "The bottom third of `Homology.scala` is commented-out prior art (`RipserHomology`,
  `computePersistentHomology`)". **Stale.** No such code exists; the file ends at `persistentCohomology`.
- **Line ~114, "Current gaps":** the Packed apparent-pair representative gap appears closed (§1.7).
- **Dangling section names.** The code cites "Bug found while cross-validating" (×4) and "Naive-engine scaling" (×1),
  both removed by the condensation. Fix these in the code comments (§3) rather than restoring the sections.
- **Once §1.1 is fixed:** add to "Testing lessons" that signed-field fixtures must include a ≥5-vertex simplex,
  because `Set1..Set4` preserve insertion order and hide hash-order bugs below that size.

---

## Appendix: probe (scratchpad, not committed)

Run with:

```
sbt "set Test / unmanagedSourceDirectories += file(\"<scratchpad>/probe\")" "testOnly *ProbeSpec"
```

Output:

```
PROBE [shipped default_Simplex_is_OrderedCell] F3-vs-F2 mismatches over 20 torsion-free fixtures: naive=1 chunks=19 cellular-cohomology=0
PROBE [positive control: d_i-ordered boundary] F3-vs-F2 mismatches over 20 torsion-free fixtures: naive=0 chunks=0 cellular-cohomology=0
```

Earlier probe (same fixtures, ℝ via `DoubleApproximated(1e-9)`, naive only): 2/20 ℝ-vs-F2 mismatches, e.g. seed 16
reported `R-only=List((3,3.3,4.4), (4,5.4,Infinity), (5,5.5,Infinity))`. An essential H⁵ class cannot exist in a
5-skeleton with F2 answer `(4,4.4,5.5)`.

**Facade probe (negative result).** This probe compares `engine=chunks`/`engine=naive` against `engine=ripser`
through `matlab.TDA4j`, `maxDimension=3`, `field=Z`, at `prime=2` and at `prime=3`:
- 12 random 12-point 3-D clouds (`computeFromPoints`, default threshold): 0 disagreements for any
  engine/prime pair;
- 20 random `{1,2}`-valued 9-point metrics (`computeFromDistanceMatrix`, `maxFiltrationValue=10`): 0
  disagreements.

The core loop, from `<scratchpad>/probe2/FacadeProbeSpec.scala`:

```scala
for p <- Seq("2", "3"); eng <- Seq("chunks", "naive") do
  val base = Array("maxDimension", "3", "field", "Z", "prime", p, "maxFiltrationValue", "10")
  val rip = diagram(TDA4j.computeFromDistanceMatrix(d, base ++ Array("engine", "ripser")))
  val other = diagram(TDA4j.computeFromDistanceMatrix(d, base ++ Array("engine", eng)))
  if rip != other then disagree = disagree.updated(s"$p-$eng", disagree(s"$p-$eng") + 1)
```

Here `diagram` drops zero-length bars and sorts.

Main probe:

```scala
package org.appliedtopology.tda4j

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.specs2.mutable.Specification

/** Probe: Simplex boundary sign bug for >= 5-vertex simplices. Torsion-free complexes (subcomplexes of a simplex)
  * must give identical barcodes over F2 and F3; F2 is sign-blind, so any F3-vs-F2 mismatch is a sign error. */
class ProbeSpec extends Specification:
  // Correctly-signed instance: faces in d_0..d_n order (positive control).
  val fixedInstance: Simplex[Int] is OrderedCell = new (Simplex[Int] is OrderedCell):
    override lazy val ordering = simplexOrdering[Int]
    extension (spx: Simplex[Int])
      override def dim = spx.size - 1
      override def boundary[C: Field as fr]: Seq[(Simplex[Int], C)] =
        if spx.dim <= 0 then Seq.empty
        else (0 until spx.size).map(i => (spx.dropIndex(i), if i % 2 == 0 then fr.one else fr.negate(fr.one)))

  def stratified(cells: Seq[(Double, Simplex[Int])])(using Simplex[Int] is OrderedCell): StratifiedCellStream[Simplex[Int], Double] =
    val fv: Map[Simplex[Int], Double] = cells.map((v, s) => s -> v).toMap
    val maxD = cells.map(_._2.dim).max
    new StratifiedCellStream[Simplex[Int], Double] with DoubleFiltration[Simplex[Int]]:
      val filtrationValue: PartialFunction[Simplex[Int], Double] = fv
      val filtrationOrdering: Ordering[Simplex[Int]] = simplicialSetFiltrationOrdering(fv, _.size - 1, simplexOrdering[Int])
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if d >= 0 && d <= maxD => cells.map(_._2).filter(_.size - 1 == d).sorted(using filtrationOrdering.reverse).iterator
      }

  type Diagram = Seq[(Int, Double, Double)]
  def norm(bars: Seq[(Int, Double, Double)]): Diagram = bars.filter((_, b, d) => b != d).sorted

  def naive[C: Field](cells: Seq[(Double, Simplex[Int])])(using Simplex[Int] is OrderedCell): Diagram =
    val st = CellularHomologyContext[Simplex[Int], C, Double]().persistentHomology(stratified(cells))
    norm(st.diagramAt(1e9))
  def chunks[C: Field](cells: Seq[(Double, Simplex[Int])])(using Simplex[Int] is OrderedCell): Diagram =
    val st = CellularPersistenceInChunksContext[Simplex[Int], C](5).persistentHomology(stratified(cells))
    norm(st.diagramAt(1e9))
  def cohom[C: Field](cells: Seq[(Double, Simplex[Int])])(using Simplex[Int] is OrderedCell): Diagram =
    import org.appliedtopology.tda4j.barcode.*
    def v(e: BarcodeEndpoint[Double]): Double = e match
      case ClosedEndpoint(x) => x
      case OpenEndpoint(x) => x
      case _: PositiveInfinity[?] => Double.PositiveInfinity
      case _ => Double.NegativeInfinity
    norm(CellularCohomologyContext[Simplex[Int], C, Double]().persistentCohomology(stratified(cells)).map(b => (b.dim, v(b.lower), v(b.upper))))

  def fixtures: Seq[Seq[(Double, Simplex[Int])]] = (1 to 20).map { seed =>
    val rnd = scala.util.Random(seed)
    val w = Array.fill(7)(rnd.nextInt(5).toDouble)
    val raw = (1 to 6).flatMap(k => (0 until 7).combinations(k).map { vs =>
      (vs.map(w).max + (k - 1) * 0.1 + (if k >= 5 then rnd.nextInt(4) else 0), Simplex(vs*))
    })
    val fv = scala.collection.mutable.Map.from(raw.map((v, s) => s -> v))
    for k <- 2 to 6; (_, s) <- raw if s.size == k do
      fv(s) = (fv(s) +: s.toSeq.map(v => fv(Simplex(s.toSeq.filterNot(_ == v)*)))).max
    raw.map((_, s) => (fv(s), s))
  }

  "probe" >> {
    val f2 = FiniteField(2)
    val f3 = FiniteField(3)
    def run(label: String, inst: Simplex[Int] is OrderedCell): Unit =
      given (Simplex[Int] is OrderedCell) = inst
      val (mN, mC, mH) = fixtures.foldLeft((0, 0, 0)) { case ((a, b, c), cells) =>
        (a + (if naive[f2.Fp](cells)(using f2.given_is_Fp_Field) != naive[f3.Fp](cells)(using f3.given_is_Fp_Field) then 1 else 0),
         b + (if chunks[f2.Fp](cells)(using f2.given_is_Fp_Field) != chunks[f3.Fp](cells)(using f3.given_is_Fp_Field) then 1 else 0),
         c + (if cohom[f2.Fp](cells)(using f2.given_is_Fp_Field) != cohom[f3.Fp](cells)(using f3.given_is_Fp_Field) then 1 else 0))
      }
      println(s"PROBE [$label] F3-vs-F2 mismatches over 20 torsion-free fixtures: naive=$mN chunks=$mC cellular-cohomology=$mH")
    run("shipped default_Simplex_is_OrderedCell", default_Simplex_is_OrderedCell[Int])
    run("positive control: d_i-ordered boundary", fixedInstance)
    ok
  }
```
