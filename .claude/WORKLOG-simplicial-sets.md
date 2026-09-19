# WORKLOG: Finitely-generated simplicial sets

Point-in-time record of the session introducing finite simplicial sets to tda4j. Not retroactively edited
later (per the project's worklog convention) -- see CLAUDE.md's own "Simplicial sets" section for the final
shipped state.

## Ask

> I still want something like the old (deleted) `SimplicialSet.scala`, but designed fresh, not copied. Finite
> generators, face maps defined per generator, should be enough to infer the rest. Ask questions, then plan.
> Eventually: a cell structure inferred from the simplicial-set structure (feeding the existing homology
> engines), and a builder that turns any simplicial stream into this representation.

## Orientation (before any design)

Read `Chain.scala` (the `Cell`/`OrderedCell`/`OrderedBasis` contract: `dim`, `boundary[CoefficientT: Field]`, a
total `Ordering`), `Simplex.scala`/`SimplexOrderedCell.scala` (the `Simplex_is_OrderedCell` parameterized-given
pattern, the model to mirror), and `SimplexStream.scala` (`CellStream`/`SimplexStream`/`StratifiedCellStream`).

Recognized the right primitive up front: this is exactly the classical Eilenberg-Zilber presentation used by
Kenzo/EAT for effective homology -- a finite simplicial set given by non-degenerate generators plus, per
generator, its `n+1` faces as `(degeneracy word, target generator)` pairs. Everything else (the full simplex
set in every dimension, arbitrary `d_i`/`s_j`) is inferable from that via the simplicial identities.

## AskUserQuestion: three scope decisions

1. **Scope**: homology-only boundary rule vs. full operator algebra (`d_i`/`s_j` on arbitrary elements) vs.
   full construction algebra (+ products/quotients/bar constructions). User picked **full construction
   algebra** -- the broadest option.
2. **Filtration**: ordinary homology of one fixed simplicial set vs. filtered/persistence-ready from the
   start. User picked **ordinary homology only**.
3. **Fixtures**: which hand-built models to pin. User picked **all of them**: minimal S¹, S², S³, RP², and a
   torus.

## Plan-mode derivation (before writing code)

Did the actual math by hand rather than trusting recall, since this is exactly the kind of subtle
convention-direction problem (normal-form ordering, sign, tie-break) that has repeatedly cost sessions in this
codebase. Three `advisor()` passes shaped the final plan (`/Users/mik/.claude/plans/fuzzy-cooking-elephant.md`,
approved before any code was written):

**Pass 1** (after a first design draft): corrected five things --
- The degeneracy-word normal form is **strictly decreasing**, not increasing as first assumed. Derived from
  `s_i s_j = s_{j+1} s_i` (`i <= j`): `s_0 s_0 (v) = s_1 s_0 (v)`, so the unique normal form for "apply `s_0`
  twice" is `[1, 0]`, never `[0, 1]`.
- A homology-only design (drop degenerate faces, no operator algebra) can't validate its own input: checking
  `d_i d_j = d_{j-1} d_i` needs `d_i` on the frequently-degenerate `d_j(g)`, which needs `faceOf` on an
  arbitrary element, not just generators. This is what justified building the full operator algebra even
  though the `OrderedCell` boundary itself only ever needs generators.
- `fromStream` exercises **zero** of the degeneracy machinery (every face of an honestly-ordered simplex is
  automatically non-degenerate), so it can validate plumbing but is no evidence `faceOf`/`insertOuter` are
  correct. Needed independent hand-built fixtures for that.
- `Chain.collapseHead` had never been exercised on a *repeated* cell before (`Simplex`/`Cube` boundaries never
  repeat a cell within one boundary); the minimal S¹ model's `∂e = v - v = 0` is the first real test of that
  path, and was pinned explicitly rather than left as an implied consequence.
- RP² (over F2 vs F3) is the sign-discriminating fixture: `2·e_1` vanishes over F2 (invisible sign bug) but is
  injective over F3 (any sign bug shows up as a wrong Betti number).

**Pass 2** (after fixing pass 1's issues): found that `CellularHomologyContext` (`Homology.scala:39`) takes a
`stream: CellStream[CellT, FiltrationT]`, not a bare `OrderedCell` -- there is no engine entry point that skips
the stream interface. This meant "ordinary homology only" (scope decision 2) still needed a `CellStream`
adapter, just a trivial constant-filtration one (`FiltrationT = Int`, every generator at value `0`) -- a
structural fact about the existing code, not a walk-back of the user's decision. Also derived, by tracing
`Homology.scala:80-94`'s own `processingOrder` comment rather than guessing: the adapter's `filtrationOrdering`
should be `Ordering.by(dimOf)` **ascending** (matching the established convention, not a fresh interpretation)
-- the same comment that explains why an earlier, different session's attempt at `.reverse`-ing a whole
comparator wholesale was reverted.

**Pass 3** (after adding RP³ and correcting the torus fixture): found three more issues --
1. No planned fixture reached `faceOf`'s `i > w1+1` branch at all (a third of the recursion untested). Fixed
   by extending RP² to RP³ (`e_3`'s faces reach it via `faceOf(2, [0], e_1)`), with all six `d_i d_j = d_{j-1}
   d_i` identity instances on `e_3` hand-traced before writing any code.
2. The torus fixture's first description was simply wrong: claimed 2 degenerate 2-cells; the actual classical
   model (Hatcher, *Algebraic Topology*, Example 2.4) is 1 vertex, 3 loop-edges (`a,b,c`, `c` the diagonal), 2
   triangles with identical face assignment `d_0=b,d_1=c,d_2=a`, and **no degeneracy anywhere** -- its real
   value is being a genuine Δ-complex (loops at one vertex) that `fromStream` can never produce from an actual
   `Simplex[VertexT]`, not degeneracy coverage.
3. `validate()`'s structural checks needed to be spelled out as exact arithmetic (`dimOf(target) + word.length
   == n - 1`), check `word` is honestly strictly-decreasing/non-negative (user data can violate the normal-form
   invariant directly), and check `target` is a registered generator (otherwise `faceOf`'s base case throws an
   unhelpful bare `NoSuchElementException`).

## What got built

- `algebra/SSetElement.scala`: `case class SSetElement[G](word: List[Int], generator: G)`, `insertOuter`
  (composes a new outermost degeneracy into a normalized word via `s_i s_j = s_{j+1} s_i`), `faceOf` (`d_i` on
  an arbitrary element, three branches per the simplicial identities: `i < w1` shrink-and-recurse, `i ∈
  {w1,w1+1}` cancel, `i > w1+1` shift-and-recurse).
- `cells/SimplicialSet.scala`: `FiniteSimplicialSet_is_OrderedCell` (generators are the cells; boundary is the
  normalized-chain-complex rule -- only non-degenerate faces contribute, alternating sign; this is the *only*
  place degeneracy matters for homology, no recursive `faceOf` needed), `FiniteSimplicialSet[G]` (generator
  storage, precomputed `dimOf` via a `Map`, `sOp`/`dOp`, `validate()`).
- `streams/SimplicialSetStream.scala`: `SimplicialSetStream[G]` (the trivial `CellStream[G, Int]` adapter --
  `G is OrderedCell` threaded explicitly via the companion `apply`, not resolved as an ambient global given,
  since unlike `Simplex`/`Cube` a `FiniteSimplicialSet`'s `OrderedCell` instance depends on that one instance's
  own `faces` data, not on `G` alone), `fromStream`.

**A real signature correction found while writing the cross-validation test, not anticipated in the plan**:
`fromStream`'s parameter was originally typed `SimplexStream[VertexT, ?]` (matching the plan's pseudocode
literally), but the actual Vietoris-Rips streams in this codebase (`EnumeratingCofaceSimplexStream` and
relatives) are `CofaceSimplexStream`/`StratifiedCellStream[Simplex[VertexT], _]` -- a *sibling* of
`SimplexStream` under `CellStream`, not a subtype of it. `SimplexStream[VertexT, ?]` would have silently
rejected every real VR stream and only accepted `ExplicitStream`-built ones. Fixed by widening to
`CellStream[Simplex[VertexT], ?]`, the actual common ancestor -- caught by trying `fromStream` against a real
`LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(...), ...)` while writing `SimplicialSetStreamSpec`,
not by re-reading the type hierarchy in the abstract.

**A real validator bug caught by the validator's own "no errors on any fixture" test**: `validate()`'s
structural arity check first said "every generator of dimension `n` needs `n+1` faces," universally -- but
dimension-0 generators have **zero** faces (face maps target dimension `n-1`, which doesn't exist below 0; the
same convention `Simplex`/`Cube` already use, and the exact convention `FiniteSimplicialSet_is_OrderedCell`'s
boundary rule and `SSetElement.scala`'s own doc comment already assumed). `minimalSphere(1).validate()` failed
immediately with "Vertex (dim 0): expected 1 faces, found 0" -- the fixture was right, the validator was
wrong. Fixed by special-casing `n == 0` to expect 0 faces.

## Fixtures (`cells/SimplicialSetFixtures.scala`, test sources)

- `minimalSphere(n)` (S¹, S², S³ from one generic builder): 1 vertex, 1 top `n`-cell, all `n+1` faces the same
  maximally-degenerate `(n-1)`-simplex over the vertex for `n >= 2` (word `[n-2,...,0]`, verified by induction
  via `insertOuter`); no degeneracy needed at all for `n=1`.
- `realProjectiveSpace(topDim)` (RP², RP³ from one generic builder): the reduced-bar-construction model of
  `B(Z/2)`, one non-degenerate generator per dimension.
- `torus`: Hatcher's minimal Δ-complex model, 1 vertex/3 edges/2 triangles, no degeneracy.

**Verified against real answers, not just self-consistency**: RP² gives `H_1=H_2=F2` over F2 but `H_1=H_2=0`
over F3 (matches the known fact that odd-primary coefficients see even-dimensional real projective space as
acyclic); RP³ gives an essential `H_3=F` over *every* field (a closed orientable 3-manifold) alongside the same
F2-vs-F3 split at `H_1`/`H_2`; the torus gives Betti numbers `(1,2,1)` for every field (torsion-free). All
hand-derived by direct chain-complex computation before writing any fixture code, then confirmed by running the
real `CellularHomologyContext` engine through the `SimplicialSetStream` adapter -- agreement between the two is
the actual evidence `faceOf`/`insertOuter` are correct, not merely self-consistent.

## Verification

- `SSetElementSpec`: direct `insertOuter`/`faceOf` traces -- the `s_0 s_0` normal-form example, all six RP³
  `d_i d_j = d_{j-1} d_i` identity instances (including the one hitting the `i > w1+1` branch), the sphere
  staircase-word induction, and two dedicated abstract (non-topological) examples specifically constructed to
  exercise `insertOuter`'s own cascading branch from inside a `faceOf` rewrap -- not just its prepend branch,
  which every fixture-derived trace happened to land on.
- `SimplicialSetSpec`: `validate()` clean on every fixture; two deliberately-broken variants (a swapped face
  pair, a malformed non-decreasing word) confirmed caught.
- `SimplicialSetHomologySpec`: full homology of every fixture via the real engine, over F2/F3/F11 as
  appropriate, checked against the hand-derived answers above (exact bar lists for S¹/S²/S³/RP²/RP³, Betti-
  number counts for the torus since its specific tie-broken edge/triangle pairing is order-dependent while the
  Betti numbers are not); plus a bars-account-for-cells structural check on every fixture.
- `SimplicialSetStreamSpec`: `fromStream` cross-validated (Betti numbers) against `SimplicialHomologyContext`
  run directly on the same stream, on hand-built fixtures and on random Vietoris-Rips point clouds via
  `LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(...), 2)` -- exercising the real, non-`SimplexStream`
  VR stream type the signature-widening fix above was specifically for.

Full `sbt test`: 223 total, 218 passed, 0 failed, 5 skipped, 1 pending (the pre-existing skip/pending baseline,
unaffected). `sbt scalafmtAll` run before this.

## Deliberately deferred, not attempted this session

- **General products, quotients, and bar-construction scaffolding** -- the broadest part of the "full
  construction algebra" scope option the user picked. Flagged explicitly in the plan as a conscious scope
  reduction (not a silent one): the operator algebra (`faceOf`/`insertOuter`, needed for self-validation) was
  judged the right-sized, well-bounded core for one session; general constructions are open-ended enough that
  bundling them in risked not finishing either cleanly. A genuine simplicial-set product would have made the
  torus fixture "free" (as `minimalSphere(1) x minimalSphere(1)`, via Eilenberg-Zilber shuffles) instead of a
  separately hand-built model -- a concrete, scoped follow-up if products turn out to be independently useful.
- **Filtered/persistence-ready simplicial sets.** The user explicitly chose ordinary (unfiltered) homology for
  this session. `SimplicialSetStream` could grow a real per-generator filtration value later without touching
  `SSetElement.scala`/`FiniteSimplicialSet`'s own structure at all -- the filtration lives entirely in the
  `CellStream` adapter layer.
- **`PersistenceInChunksContext`/`SimplicialHomologyByDimensionContext` were not generalized** to consume
  `FiniteSimplicialSet` generators -- both remain hardcoded to `Simplex[VertexT]` (pre-existing, unrelated to
  this session; the same scope boundary `CubicalHomologyContext` hit and documented).
