# WORKLOG: Cubical complexes and persistence

Point-in-time record of the overnight session introducing cubical complexes to tda4j. Not retroactively
edited later (per the project's worklog convention) -- see CLAUDE.md's final summary for the shipped state.

## Ask

> Introduce cubical persistence to the library. Cellular homology algorithm already exists; cubical cells
> should slot in cleanly with that. Cubical complexes are unions of products of unit intervals [i,i+1] or
> degenerate intervals [i,i] -- a globally chosen dimension controls how many factors each product has, and
> the number of unit intervals in a product is the cell's dimension. Boundary: collapse each unit interval
> to a degenerate interval both ways, opposite signs, once per unit-interval factor. Then: convert greyscale
> images (2D)/voxel grids (3D) into cubical complexes, pixel/voxel intensity as filtration value.
>
> Ask questions now, then prioritize progression overnight; log decisions separately.

## Orientation (before any code)

Read `Chain.scala`, `Simplex.scala`, `SimplexStream.scala`, `Homology.scala` (first ~260 lines),
`SimplexOps.scala`, `FiniteField.scala`, `Field.scala`, and the `SimplexSpec`/`ChainSpec`/`FiniteFieldSpec`
test files, plus `build.sbt`, before deciding anything. Key finding: `CellularHomologyContext[CellT:
OrderedCell, CoefficientT: Field, FiltrationT: Ordering]` (the "naive" engine, aka `SimplicialHomologyContext`
when instantiated at `Simplex[VertexT]`) is *already* fully generic over `CellT: OrderedCell` --
`persistentHomology(stream: => CellStream[CellT, FiltrationT])` needs nothing cubical-specific. By contrast
`PersistenceInChunksContext` and `SimplicialHomologyByDimensionContext` are hardcoded to `Simplex[VertexT]`,
not generic -- confirming "slot in cleanly with [the cellular homology algorithm]" means the naive engine
specifically, not a rewrite of the chunked/MST engines to be generic. That's the whole scope for tonight: a
`Cube` cell type + `OrderedCell` instance, and a `CellStream`/`StratifiedCellStream[Cube, FiltrationT]`
implementation (dense grid, for images; explicit/sparse, for general use and fixtures).

## Advisor consult (before writing any code)

Called `advisor()` after orientation, before design decisions. Corrections taken on board, all followed:

1. **Don't use `AskUserQuestion`** -- it blocks, and the user is going to sleep. Post questions + chosen
   defaults as prose instead, then start immediately. Done -- see the chat transcript for the posted message
   (representation, image filtration convention, no new deps, no specialized fast engine tonight, validation
   order).
2. **`filtrationOrdering` direction is a proven trap in this codebase**, hit three separate times already
   (`RecursiveStackVietorisRipsSimplexStream`, `HelixDelaunay`, `AlphaShapeDQP` -- see CLAUDE.md's "Bug found
   while cross-validating" / "two distinct defects" sections) by writing a plain ascending `Ordering.by(fv)`
   with no reversal, or reversing the *whole* comparator (which also flips the dimension tie-break -- see
   `Homology.scala:76-89`'s note on `CellularHomologyContext.processingOrder`). Mandated: copy
   `EnumeratingCofaceSimplexStream.filtrationOrdering`'s *exact* shape (explicit negated fv comparison, then
   dimension, then a total order on the cell itself), not `.reverse` of something built ascending. Ties on fv
   are *guaranteed* here (every face of a top cube shares its value under min-over-cofaces), so getting this
   wrong is not a corner case.
3. **Cube identity must be structural, not reference equality.** An opaque type over a raw `Array`/`IArray`
   would silently fail to collide structurally-identical cubes in `Chain`'s `mutable.Map`/`SortedMap`-based
   pivot tables. Used `Vector[Int]` (structural equals/hashCode for free) instead.
4. **Boundary sign**: for nondegenerate factors at positions p_1 < ... < p_d, sign for collapsing p_j is
   `(-1)^(count of nondegenerate factors strictly before p_j)` -- alternating over the *rank among
   nondegenerate factors*, not the raw coordinate position. Upper face +, lower -. Verify over a *signed*
   field (Double and/or a small p != 2), not just F2 -- F2 hides every sign bug, and F2 is this library's own
   facade default.
5. **Monotonicity (`fv(face) <= fv(cube)` for every face) is forced by `processingOrder`'s ascending sort**,
   not a style choice -- get it backwards and every cell hits the same `IllegalStateException: reduction
   pivot ... was not a recorded open class` this exact mistake has produced three times already in this repo.
   Pin it as a property test.
6. **Validation order, reordered from my first instinct**: don't lead with a cubical-to-simplicial
   triangulation cross-check -- an unvalidated oracle can make a real cubical bug look like a triangulation
   bug or vice versa (CLAUDE.md's own precedent: "a real Helix bug would otherwise masquerade as a DQP
   regression"). Lead instead with, in order: dd=0 over a signed field; monotonicity property; the
   `totalBarsAccountForAllCells`-style structural invariant already used elsewhere in this codebase; hand-
   derived fixtures; independent H0 via union-find. Triangulation cross-check only as a later bonus.
7. Build order: cell type + spec first (dd=0/monotonicity/structural), *then* the stream, *then* plug into
   `CellularHomologyContext`, *then* image loading, *then* measure real scaling numbers (don't infer -- a
   256x256 image is ~263k cells, and this repo's own history (`PersistenceInChunksContext`'s 100k-scale
   stall, RipserCohomologyContext's ~20us/cell constant factor) says expect a real ceiling on the naive
   engine at that size).

## Status at end of session

Shipped: `Cube` cell type + boundary + `OrderedCell` instance (`Cubical.scala`), `CubicalGridStream` +
`ExplicitCubicalStream` + `CubicalHomologyContext` (`CubicalStream.scala`), greyscale image / voxel grid
loading (`CubicalImage.scala`), and four passing specs (`CubicalSpec` 9 examples, `CubicalStreamSpec` 8
examples/331 expectations -- including `ExplicitCubicalStream` coverage added after the advisor's "done"
review caught it missing, see below -- `CubicalImageSpec` 12 examples, `CubicalBenchmarkSpec` — a timing
script, not a correctness check). Full `sbt test`: 203 total, 198 passed, 0 failed, 0 errors, 5 skipped, 1
pending (same skip/pending counts CLAUDE.md already documents pre-existing — nothing new skipped). `scalafmtAll` run
(reformatted 3 files: the 3 new ones, plus incidentally reflowed doc-comment line-wrapping in
`PackedRipserCohomology.scala`/`PackedRipserCohomologySpec.scala`/`RipserPaperBenchmarkSpec.scala` -- confirmed
via `git diff` to be pure comment/whitespace reflow, no logic changes, before leaving them modified).
`scalafmtCheck`/`scalafmtSbtCheck`/`mimaReportBinaryIssues` all clean (mima has no previous artifact pinned, so
it's a no-op check currently, same as before this session). CLAUDE.md updated with a new "Cubical complexes and
persistence" section reflecting this final state.

Not done, and explicitly not claimed as done: a specialized fast cubical engine (CubicalRipser /
Wagner-Chen-Vuçini) — flagged as a real future direction with measured numbers behind it (3D scaling is the
concrete case), not attempted. The 3D per-cell-cost growth (190->340->850us/cell, n=8->32) is measured but NOT
root-caused — a genuine open question for a future profiling pass. The opaque-type "unwrap" extension naming
question (companion-object routing as a more robust fix than picking distinct names each time) is flagged but
not resolved/tested. No cubical-to-simplicial triangulation cross-check was built (deliberately deprioritized,
see the advisor-consult section) — if a future session wants one, treat it as its own correctness problem, not
a quick addition.

## Design decisions

### `Cube` cell type (`Cubical.scala`)

`opaque type Cube = Vector[Int]`, doubled-coordinate encoding (`2k` = degenerate point `{k}`, `2k+1` = unit
interval `[k,k+1]`), one entry per ambient axis -- the standard Kaczynski-Mischaikow-Mrozek representation.
Ambient dimension = `coords.length`, fixed per complex (asserted implicitly: every cube built by the same
stream shares the same length; nothing in `Cube` itself enforces cross-cube consistency, same as `Simplex`
doesn't enforce a global vertex set).

Boundary: KMM's formula exactly, sign alternating over the RANK of a non-degenerate axis among the OTHER
non-degenerate axes (not its raw coordinate-vector position) -- see the advisor correction above. Verified by
`CubicalSpec`: dd=0 exhaustively over Double and F3 in ambient dimensions 2 (25 cubes), 3 (27 cubes), and 4 (81
cubes). All 9 examples, 14 expectations pass.

`Cube is OrderedCell` mirrors `Simplex.scala`'s `Simplex_is_OrderedCell` parameterized-given pattern exactly
(a `setOrdering` parameter defaulting to a canonical lexicographic-on-encoding order, `cubeOrdering`), so a
stream can inject its own filtration-aware ordering the same way `EnumeratingCofaceSimplexStream` does.

**Two real naming collisions hit and fixed, both the same failure mode**: a top-level `extension` for `Cube`
sharing a NAME already used by an unrelated top-level `Simplex[VertexT]` extension in this package
(`underlying`, then separately `show`) breaks every `Simplex[VertexT]` call site of that name elsewhere in the
codebase (`SimplexOps.scala`, `AlphaComplexDQP.scala`, the matlab facade) -- Scala 3 extension-method
resolution, when multiple same-named top-level extensions are in scope for DIFFERENT unrelated receiver types,
does not fall back to try the other one once the first candidate found fails to unify; it just fails hard.
Confirmed empirically (compile errors naming `Required: org.appliedtopology.tda4j.Cube` at `Simplex[VertexT]`
call sites), not reasoned through in advance -- worth remembering for any future opaque type added to this
package: don't reuse an existing top-level extension name for a new, unrelated receiver type. Renamed to
`encoded` (was `underlying`) and `describe` (was `show`).

Also hit: `Seq[Int]` and `Int*` overloads of the same method (`fromEncoded`, `vertex`, `unitCube`) erase to
the identical signature after varargs desugaring (`Seq`) -- fixed with `@targetName` on the varargs overload
of each, rather than dropping the varargs convenience constructors.

### `CubicalGridStream`/`ExplicitCubicalStream`/`CubicalHomologyContext` (`CubicalStream.scala`)

T-construction dense grid stream: `topCellValue: IndexedSeq[Int] => Double` gives pixel/voxel values directly;
every lower cell's value is `min` over ALL top cells containing it, computed DIRECTLY (cartesian product over
degenerate axes' +-1 choices) rather than via a recursive immediate-cofaces walk -- proved equivalent (every
top cell containing an immediate coface of `c` also contains `c`, so the two definitions agree) and cheaper.
Monotonicity (`fv(face) <= fv(coface)`) falls out of this directly: `{top cells containing D}` is always a
SUBSET of `{top cells containing c}` when `c` is a face of `D`, so a min over fewer things is never smaller.
`filtrationOrdering` copies `EnumeratingCofaceSimplexStream`'s exact shape (explicit negated-fv comparison,
then dimension, then `cubeOrdering`) per the advisor's mandate -- NOT `.reverse` of an ascending-built
ordering. `totalCellCount = prod_i (2*shape(i)+1)`, derived via the "sum over subsets of a product" identity,
O(ambientDim), no enumeration needed (confirms the ~263k figure for a 256x256 image cited earlier).
`ExplicitCubicalStream` (sparse/arbitrary cube sets, mirrors `ExplicitStream`) and `CubicalHomologyContext`
(thin wrapper, mirrors `SimplicialHomologyContext`) came along for free -- `Cube` needed nothing new from the
generic engine.

**Third instance of the same same-named-top-level-extension collision, in a NEW shape**: `cell.boundary[Double]`
called directly on a concrete `Cube` value, OUTSIDE `Cubical.scala` and with no expected-type pressure on the
call, resolved to the WRONG `boundary` (Chain.scala's unrelated `extension [CellT: OrderedCell, CoefficientT:
Field](z: Chain[CellT, CoefficientT]) def boundary` instead of `Cube_is_OrderedCell`'s), silently inferring the
pattern-matched face's type as the opaque type's raw `Vector[Int]` representation rather than `Cube`. Confirmed
NOT a `Prop.forAll` overload-resolution artifact (first suspected, since the error first appeared inside a
`Prop.forAll(...)` call): switching to the codebase's proven `prop { (img: TestImage) => ... }` +
`Arbitrary` pattern did NOT fix it, so the real cause is the SAME class of bug as `underlying`/`show`, just
triggered by an ambiguous call site rather than a literal definition-site name clash. Confirmed the fix by
elimination: `CubicalSpec.scala`'s own successful `cube.boundary[CoefficientT]` call had EXPECTED-TYPE
pressure from its enclosing `Chain.from[Cube, CoefficientT](...)` call; adding the same kind of pressure here
(`val faces: Seq[(Cube, Double)] = cell.boundary[Double]` before pattern-matching its elements) fixed it.
**Reassuring, and checked before trusting the rest of the session's design**: `CellularHomologyContext`'s own
production calls to `.boundary` (`Homology.scala`'s `sigma.boundary[CoefficientT]`) go through the GENERIC
`CellT: OrderedCell` type parameter, not a concrete type -- Scala has no choice but to dispatch via the
`OrderedCell`/`Cell` typeclass evidence there (no concrete-type ambiguity possible when the receiver's own
identity is abstract), so this bug class only bites CONCRETE-type call sites outside a type's own defining
file, never the actual homology engine. Practical rule added to the naming-convention note above: even a
DISTINCT extension name isn't a full guarantee once a method NAME is reused for an unrelated receiver type
anywhere in the package -- ascribe an expected type at any call site that invokes a `Cell`/`OrderedCell`
extension (`boundary`, `dim`) directly on a concrete cell type from outside its own file, or route through
generic `CellT: OrderedCell` code instead.

**Verified end-to-end, `CubicalStreamSpec.scala`, all 7 examples / 328 expectations passing**: monotonicity
(exhaustive on random small 2D/3D images), the bars-account-for-cells structural invariant (random images), an
independent union-find H0 cross-check (random images, Moore/Chebyshev adjacency -- derived and double-checked
against the hand fixtures below, see the spec's own comment for why 4-connectivity would be a WRONG oracle
here), and three fully hand-derived fixtures whose EXACT bar-count breakdown (not just presence of the
meaningful features) was pinned via a general planar-graph argument (spanning-tree/cycle-rank: for a grid's
full 1-skeleton, exactly `V-1` edges merge components and the remaining `E-(V-1)` edges each birth a new H1
class, and by Euler's formula that count always equals the pixel count exactly, so every pixel is guaranteed
to kill exactly one H1 class) -- constant 2x2 image (25 cells: 1 essential + 8 zero-length dim0 + 4 zero-length
dim1, all bars accounted), a single bright center pixel in a dark 3x3 image (49 cells: the exact 16/15/9/8
split derived below, plus the one genuine (1, 0.0, 1.0) bar for the hollow center), and two 1D blobs merging
(7 cells: 1 essential + 2 zero-length + 1 genuine (0, 0.0, 2.0) merge). These fixtures were chosen SPECIFICALLY
tie-heavy (few distinct filtration values) because that is exactly the regime this codebase's `filtrationOrdering`
tie-break bugs have historically hidden in (see CLAUDE.md's extensive history) -- a fixture with all-distinct
values would not have exercised that risk at all.

### Image/voxel loading (`CubicalImage.scala`)

`fromFlatArray(shape, flatValues, sublevel)` is the one real implementation everything else (`fromGrayscale2D`,
`fromVoxelGrid3D`, `fromBufferedImage`, `fromFile`) reduces to -- row-major strides via
`shape.scanRight(1)(_*_).tail`. No new library dependency: `javax.imageio.ImageIO` (JDK-builtin) for 2D image
files; 3D voxel grids take a plain in-memory `Array[Array[Array[Double]]]` (or the general flat-array form) --
there's no single standard JDK-readable volumetric format, so a caller with a specific one (NRRD, NIfTI, a raw
slice stack) is expected to load it upstream with whatever library that needs and hand tda4j the array.
`sublevel = false` negates values on load (the standard "sublevel of -f is superlevel of f" trick) rather than
`CubicalGridStream` itself carrying a direction flag -- keeps the core stream's "min over cofaces, always" logic
free of a second code path to verify.

Grayscale conversion is the standard ITU-R BT.601 luma formula (`0.299R+0.587G+0.114B`), applied
unconditionally (harmless identity on an already-grayscale image, R=G=B) rather than special-casing
`BufferedImage`'s several possible underlying color types.

**Two of my own test assertions, not the implementation, were wrong on the first run**: `beEqualTo(128.0)` /
`beEqualTo(255.0)` on a computed luma value fail intermittently-by-construction, since `0.299+0.587+0.114 !=
1.0` exactly in binary floating point (confirmed: `127.99999999999999 != 128.0`) -- fixed to `beCloseTo`.
Worth remembering as a general lesson for any future test asserting an exact value computed via a multi-term
floating-point formula rather than passed through unchanged.

**Verified**: 12/12 `CubicalImageSpec` examples, including a real PNG file written via `ImageIO.write` and read
back via `fromFile` (not just an in-memory `BufferedImage`), and an end-to-end test that builds the exact
hand-derived "hollow 3x3" fixture from `CubicalStreamSpec` through the ACTUAL `BufferedImage`/luma path (not by
constructing `CubicalGridStream`'s `topCellValue` function directly) and reproduces the same barcode structure
-- closes the loop on the literal ask ("convert greyscale images ... pixel intensity for the persistence
parameter"), not just the underlying scaffolding.

### Real gap caught by advisor's "done" review, fixed before actually declaring done

At the "I believe this is done" advisor call, a real gap surfaced: `ExplicitCubicalStream` was shipped
(`CubicalStream.scala`) and exported but NEVER exercised by any test -- every spec up to that point went
through `CubicalGridStream` only. This mattered specifically because `ExplicitCubicalStream` takes filtration
values from a caller-supplied `Map` with NOTHING enforcing monotonicity (`CubicalGridStream` gets monotonicity
for free, by construction, from min-over-cofaces -- see above), and it has its OWN independently-written
`filtrationOrdering` block, textually duplicated from `CubicalGridStream`'s rather than shared -- exactly the
kind of duplicated comparator this file's own "Bug found while cross-validating" section documents drifting
out of sync in a way that shows up only as a corrupted reduction, never a compile error. Fixed by adding a
test that builds the already-hand-derived, already-independently-cross-checked two-blobs fixture as an
`ExplicitCubicalStream` (explicit per-cell values, matching `CubicalGridStream`'s computed ones exactly) and
asserts the two streams produce the SAME barcode -- verifies both `ExplicitCubicalStream`'s own reduction AND
that its `filtrationOrdering` agrees with `CubicalGridStream`'s on identical input, not just each
self-consistent in isolation. Passes (`CubicalStreamSpec`, now 8 examples / 331 expectations).

Also added, a one-sentence doc note on `CubicalGridStream.iterateDimension` (advisor's second, minor point):
it recomputes and fully re-sorts each dimension's bucket on EVERY call, no memoization -- fine for
`CellularHomologyContext` (calls `.iterator` once per run) but worth flagging for a future caller that might
iterate dimensions repeatedly and directly, the way some alpha-complex specs do for their own streams. Not
measured as an actual problem; flagged rather than silently left unmentioned, per the advisor's framing.

### Naive-engine scaling, measured (`CubicalBenchmarkSpec.scala`)

Matches `SparseRipsBenchmarkSpec`'s own convention (`Arguments`-driven config, small defaults so `sbt test`
stays cheap, prints a timing table, not a correctness check). Measured directly, not inferred -- run via
`sbt -DminN=... -DmaxN=... -Ddims=... "testOnly org.appliedtopology.tda4j.CubicalBenchmarkSpec"`, random
(not tie-heavy) pixel values on purpose, since `CubicalStreamSpec`'s fixtures deliberately stress the opposite
regime.

**2D (square images), n = side length in pixels, cells = `(2n+1)^2`:**

| n   | cells   | time (ms) | us/cell |
|-----|---------|-----------|---------|
| 8   | 289     | 128.5     | 444.7 (JIT warmup, not steady state) |
| 16  | 1,089   | 99.6      | 91.5    |
| 32  | 4,225   | 272.7     | 64.6    |
| 64  | 16,641  | 972.8     | 58.5    |
| 128 | 66,049  | 5,272.3   | 79.8    |
| 256 | 263,169 | 25,234.8  | 95.9    |

Roughly FLAT per-cell cost (~60-95us/cell once past JIT warmup) across a 900x range of complex sizes -- a real,
usable result: a genuine 256x256 photo (263,169 cells, not 65,536 -- see `CubicalGridStream`'s own doc for why)
processes in ~25 seconds on the naive engine. Not fast, but not the catastrophic blowup `PersistenceInChunksContext`
hits at comparable simplicial-complex scale (see CLAUDE.md's cross-engine-benchmark section) -- this is the
single-pivot-table naive algorithm, deliberately the least-optimized of the three live persistence engines,
handling a real image size in double-digit seconds without any cubical-specific optimization at all.

**3D (voxel grids), n = side length in voxels, cells = `(2n+1)^3`:**

| n  | cells   | time (ms) | us/cell |
|----|---------|-----------|---------|
| 4  | 729     | 293.7     | 402.9 (warmup) |
| 8  | 4,913   | 931.7     | 189.6   |
| 16 | 35,937  | 12,227.8  | 340.3   |
| 32 | 274,625 | 233,474.0 | 850.2   |

**A materially different, and worse, shape than 2D**: per-cell cost GROWS with `n` (190 -> 340 -> 850 us/cell)
rather than staying flat -- a 32-cubed voxel grid (274,625 cells, barely more than the 256x256 2D case's
263,169) takes ~3.9 MINUTES, not ~25 seconds. Not yet root-caused (a genuine next-session profiling target, not
guessed at here) -- plausible contributors, stated as hypotheses only: a 3D complex has cells up to dimension 3
(vs. 2 for 2D), so `Cube.boundary` produces more terms per cell (`2*dim` each) and `CellularHomologyContext`'s
single shared pivot table has more concurrent structure to reduce against; `containingTopCells`'s
O(2^(ambientDim - dim(c))) per-call cost also grows with ambient dimension. This is EXACTLY the real, measured
ceiling this session's CLAUDE.md notes predicted ("expect a real ceiling," not "if") -- and is the concrete,
now-quantified case for the deferred fast-engine work below, not just a theoretical nice-to-have.

### Deferred fast cubical engine -- flagged explicitly per mid-session request, now with real numbers behind it

A specialized, grid-structure-exploiting persistence algorithm -- **CubicalRipser** (Kaji/Kaczynski et al.'s
line of work reproducing Ripser's clearing/apparent-pairs optimizations for cubical complexes) or the
**Wagner-Chen-Vuçini** "Efficient Computation of Persistent Homology for Cubical Data" approach (union-find for
dimension 0, discrete-Morse-style reductions for higher dimensions) -- was deliberately NOT attempted this
session (see the earlier note in this file). The 3D scaling numbers just measured make the case concretely: a
modest 32^3 voxel grid already costs ~4 minutes on the naive engine, and the per-cell cost is GROWING with `n`
in 3D specifically, not flat the way it is in 2D -- real volumetric data (medical imaging, materials science,
the actual use case voxel support exists for) will commonly exceed 32^3. Recommended next step for a future
session, in order: (1) profile the 3D case specifically to root-cause the growing per-cell cost (allocation
profiler, not another timing table -- same lesson `RipserCohomologyContext`'s own ~20us/simplex tax learned
the hard way, see CLAUDE.md); (2) THEN decide whether a targeted fix within the generic engine closes the gap,
or whether a genuinely specialized cubical algorithm (CubicalRipser-style clearing + apparent pairs, or
Wagner-Chen-Vuçini's discrete Morse reduction) is warranted -- mirroring how `RipserCohomologyContext` was
only built as its own dedicated engine after the naive one was fully validated and its limits understood, not
before.

### Naming convention for opaque-type "unwrap" extensions -- flagged mid-session, not resolved tonight

Project lead asked whether the `underlying`/`show` collision above is going to keep recurring as more opaque
types get added, and floated a systematic naming scheme (`underlying_simplex`-style suffixes). My assessment,
posted in chat: yes, it will recur under the current pattern (plain top-level `extension` clauses, generic
names) -- Scala 3's extension-method search does not appear to fully overload-resolve across same-named
top-level extensions for unrelated receiver types; it commits to one candidate and fails hard rather than
trying alternatives (see the two confirmed collisions above). Two fixes at different cost:
- Cheap (what's used for `Cube` tonight): give each opaque type's unwrap a name specific to what it actually
  represents (`encoded`, not `underlying_cube`) -- no suffix hack needed, but relies on remembering to pick a
  distinct name each time a new opaque type is added.
- More robust, NOT attempted/verified tonight: move each type's extensions into its own companion object.
  Scala's extension search checks a receiver type's companion object specifically (the same mechanism implicit
  search uses), which should route lookup by nominal receiver type before names can collide -- meaning two
  types COULD then legitimately reuse the same name like `underlying`. This is a hypothesis, not confirmed by
  running it.

Deliberately NOT renaming `Simplex.underlying`/`Simplex.show` tonight -- that touches every existing call site
(`SimplexOps.scala`, `AlphaComplexDQP.scala`, the matlab facade) for a stylistic/robustness win, not a
functional fix, and is unrelated to cubical persistence. Flagged here as a real, worthwhile follow-up
(recommend trying the companion-object approach first, empirically, before any codebase-wide rename) rather
than silently dropped or rushed into this session's unrelated diff.

### Deferred, flagged so it doesn't get lost (per explicit ask mid-session)

A specialized, grid-structure-exploiting fast cubical persistence algorithm -- CubicalRipser
(Kaji/Sudoh/Ahara/... ) or the Wagner-Chen-Vuçini "Efficient Computation of Persistent Homology for Cubical
Data" approach -- is NOT attempted this session. Tonight's scope is deliberately limited to slotting `Cube`
into the existing generic `CellularHomologyContext` (the naive reduction engine), matching "we have a cellular
homology algorithm, this should slot in cleanly with that." A specialized engine exploiting the grid's regular
structure (union-find-based dimension-0/1 handling analogous to `SimplicialHomologyByDimensionContext`, or a
fully cubical-specific reduction like real CubicalRipser) is a natural, real future direction once real image
sizes are measured against the naive engine's ceiling (see the scaling-measurement task below) -- flagged here
explicitly so it isn't forgotten, the same way `RipserCohomologyContext`'s emergent-pairs optimization was
flagged and deliberately deferred rather than silently dropped.
