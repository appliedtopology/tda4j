# Real filtration + a chunks engine for `FiniteSimplicialSet`

Follow-up to `.claude/WORKLOG-simplicial-sets.md` and `.claude/WORKLOG-simplicial-set-constructions.md`. The
user asked directly: introduce real filtration for simplicial sets, and try building a
`PersistenceInChunksContext`-based engine for them -- "there's no reason it shouldn't work, right?"

## Investigation before writing anything

Read `PersistenceInChunksContext` (`Homology.scala:279-536`) in full before answering. Finding:
`class PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field](maxDim: Int = 5)` is parameterized on
`VertexT`, not `CellT: OrderedCell` like `CellularHomologyContext` -- a different starting point from the naive
engine, which was already fully generic before `Cube`/`FiniteSimplicialSet` needed only thin wrappers. But
reading the body found **zero actual `Simplex`-specific behavior anywhere** -- every operation goes through the
generic `OrderedCell` interface (`.dim`, `.boundary[CoefficientT]`) or `CellT`-typed containers; `Simplex[VertexT]`
appears 30 times, always as a type annotation, never as a call to `SimplexOps` or anything vertex-set-specific.
So "no reason it shouldn't work" was essentially correct, but the class needed genericizing first, not just
wrapping -- unlike the naive engine, this one actually required a real (if mechanical) change.

Two additional facts, found by reading rather than assumed: (1) `PersistenceInChunksContext` needs
`StratifiedCellStream[CellT, Double]`, not the looser `CellStream[G, Int]` `SimplicialSetStream` currently
provides -- `FiltrationT` is hardcoded to `Double` throughout (birth/death sentinels, `diagramAt(f: Double)`),
and `iterateDimension` is used directly for chunk-boundary bookkeeping (`allCells`, `cellIndex`, the `stop`
predicate). (2) The exact `filtrationOrdering` convention needed was already pinned down, twice-debugged, in
`EnumeratingCofaceSimplexStream.filtrationOrdering` (`SimplexStream.scala:305-318`) -- reused line-for-line
rather than re-derived, on the theory that a comparator this codebase has broken and fixed twice is worth
copying exactly, not reinventing.

## `advisor()` corrections before writing code

Three corrections to the initial plan, all adopted:

1. **Don't rename `PersistenceInChunksContext` and update ~9 call sites.** Genericize the body under a NEW name
   (`CellularPersistenceInChunksContext[CellT: OrderedCell, CoefficientT: Field]`, matching the
   `Cellular*`/`Simplicial*` naming split `CellularHomologyContext`/`SimplicialHomologyContext` already
   established), and make `PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field]` a one-line
   `extends` subclass. Zero call sites change; the full existing chunks test suite then exercises the generic
   engine unchanged, which is much stronger regression evidence than a rename-plus-9-site diff (a mechanical
   slip in that diff would look identical to a real behavior change).
2. **The discriminating check for the stream isn't "is `filtrationOrdering` a total order" -- it's whether
   `allCells` (dimension-major concatenation of `iterateDimension` buckets) is consistent with the chunk `stop`
   predicate's assumption that positional index approximates filtration age.** Three separate files
   (`VietorisRips.scala:141`, `AlphaShapes.scala:322`, `AlphaComplexDQP.scala:939`) carry exactly this warning:
   value-only sortedness passes a stream's own spec but breaks chunks, because chunk logic relies on
   `iterateDimension`'s own emission order standing in for filtration position. Assert this directly rather than
   inferring it from a passing barcode.
3. **The hand-picked fixture needs non-dimension-aligned values.** A filtration assigned purely by dimension
   band (all edges tied, say) can't discriminate a reversed primary key from a correct one, since dimension
   order and filtration order agree everywhere. Assign genuinely different values to generators of the SAME
   dimension so the two orders actually disagree somewhere.

## What shipped

- **`CellularPersistenceInChunksContext[CellT: OrderedCell, CoefficientT: Field]`** (`Homology.scala`): the
  genericized engine, mechanical `Simplex[VertexT] -> CellT` rename throughout the body, `VertexT: Ordering ->
  CellT: OrderedCell` on the class itself. `PersistenceInChunksContext[VertexT: Ordering, CoefficientT: Field]
  extends CellularPersistenceInChunksContext[Simplex[VertexT], CoefficientT](maxDim)` preserves every existing
  call site unchanged (`Simplex[VertexT] is OrderedCell` resolves automatically from `Ordering[VertexT]` alone,
  the same mechanism `SimplicialHomologyContext` already relies on).
- **`streams/FilteredSimplicialSetStream.scala`**: `simplicialSetFiltrationOrdering` (the
  `EnumeratingCofaceSimplexStream` convention, generalized past `Simplex[Int]`'s colex tie-break to a
  caller-supplied `Ordering[G]`), `validateMonotoneFiltration` (checks the one precondition every engine needs:
  a face's filtration value never exceeds its coface's -- checked only against BARE, `word = Nil` direct faces,
  since those are the only faces `FiniteSimplicialSet_is_OrderedCell.boundary` ever looks at), and
  `FilteredSimplicialSetStream[G]` itself, a real `StratifiedCellStream[G, Double]` (unlike the constant-`0`
  `SimplicialSetStream`). Filtration lives entirely in this adapter layer, per the architecture note already in
  CLAUDE.md's "Simplicial sets" section -- `FiniteSimplicialSet`/`SSetElement` were not touched at all.

## Verification

- `validateMonotoneFiltration` accepts a hand-picked monotone filtration and rejects a deliberately broken one
  (a triangle assigned a SMALLER value than one of its own edges).
- Direct structural check on `FilteredSimplicialSetStream.iterateDimension`: each dimension's bucket is
  ascending by filtration value, and every generator's bare direct faces sit at an earlier position than the
  generator itself in the dimension-major concatenation -- the exact invariant the three warning comments
  above are about, asserted directly rather than inferred.
- **The discriminating cross-validation**: `torus` with `Vertex=0, A=1, B=2, C=3, U=4, L=5` (edges of the SAME
  dimension given different values, so filtration order and dimension order disagree). Hand-derived structure
  (without guessing the pivot-selection tie-break): `U` (older) must kill a 1-cycle among `{A,B,C}` since its
  raw boundary is nonzero and nothing has been paired yet when it's processed; `L` (younger) then reduces to
  zero against `U`'s own recorded boundary (identical face data) and survives as the essential H_2 class. So:
  essential H_0 born at `0.0`; essential H_2 born at `5.0`; exactly one finite H_1 bar, dying at `4.0`; its
  birth and the two essential H_1 births partition `{1.0, 2.0, 3.0}` exactly. `CellularHomologyContext` and
  `CellularPersistenceInChunksContext` (via the same `FilteredSimplicialSetStream`) agree on this **exactly**,
  matching both the hand-derived structure and each other.
- Randomized cross-validation: a dimension-band-plus-jitter filtration (`fv = 1000*dim + jitter, jitter in
  [0,1)`) on `minimalSphere(1..3)`, `realProjectiveSpace(2..3)`, and `torus`, 5 seeds each. Soundness of the
  band construction rests on `structuralErrors`' own enforced invariant (`dimOf(target) + word.length == n - 1`
  for a bare face, `SimplicialSet.scala:70-72`), so a bare face's dimension is always exactly one less --
  verified directly by asserting `validateMonotoneFiltration` is empty on every generated filtration, not
  assumed. `CellularHomologyContext` and `CellularPersistenceInChunksContext` agree exactly on every case.

`sbt test`: 235 total, 230 passed, 0 failed, 0 errors, 5 skipped, 1 pending (unchanged skip/pending baseline) --
+4 over the prior baseline, no regressions from genericizing `PersistenceInChunksContext`.

## What's still deferred, unchanged

`SimplicialHomologyByDimensionContext` remains hardcoded to `Simplex[VertexT]`, not attempted this session (a
separate, unrelated algorithm from the two touched here). Quotients/attaching maps and the bar construction
remain unstarted, per `.claude/WORKLOG-simplicial-set-constructions.md`.
