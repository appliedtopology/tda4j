# Products and coproducts for `FiniteSimplicialSet`

Follow-up to `.claude/WORKLOG-simplicial-sets.md` (the `SSetElement`/`FiniteSimplicialSet`/`SimplicialSetStream`
feature), which deliberately deferred "general products/quotients/bar-construction scaffolding" as the broadest
part of the "full construction algebra" scope the user picked. This session: the user asked directly what
products and coproducts would need, and what else was still open. Answered the second question by recap
(unchanged from the prior WORKLOG); this document covers the first, including a real design correction and a
real implementation bug, both caught before/via testing rather than assumed correct.

## The shuffle-theorem misconception, caught by `advisor()` before any code was written

First design attempt indexed the product's generators by Eilenberg–Zilber *shuffles*: pairs of non-degenerate
simplices of dimension `p` (in X) and `q` (in Y) with `p+q=n`, one per `(p,q)`-shuffle. `advisor()` caught this
as wrong, with the precise correction: that shuffle family is the classical EZ *chain map* between
`C_*(X) tensor C_*(Y)` and `C_*(X x Y)` (a tool for relating the two chain complexes), not an enumeration of
`X x Y`'s own non-degenerate simplices.

Concrete counterexample worked through by hand: for `X = Y = ` `minimalSphere(1)`, the pair `(e_X, e_Y)` — both
genuinely non-degenerate, dimension 1 each — is a real non-degenerate 1-simplex of `X x Y` (neither factor is
`s_j`-degenerate, so no common `j` degenerates both). No `(p,q)`-shuffle with `p+q=1` could ever produce it,
since here `p=q=1`, not `p+q=1`. The actual definition is simpler and doesn't need shuffles at all:
`(X x Y)_n = X_n x Y_n` degreewise, and a pair is non-degenerate in the product iff no single `j` degenerates
both sides at once (`s_j` on the product is literally the diagonal `(s_j, s_j)`).

## Design that follows from the corrected definition

- **Non-degeneracy test**: `a` (an `SSetElement`) is `s_j`-degenerate exactly when `j` appears in `a.word` — not
  just the outermost entry, the *whole set*. Verified by hand: `s_1 s_0(v)` (word `[1,0]`) is degenerate at
  `s_1` trivially, and *also* at `s_0` via `s_0 s_0 = s_1 s_0` (`s_1 s_0(v) = s_0(s_0(v))`), matching both
  entries. So a pair `(a,b)` is non-degenerate in the product iff `a.word.toSet.intersect(b.word.toSet).isEmpty`
  — cross-checked in `SimplicialSetConstructionsSpec` against the actual definition (`sOp(j, dOp(j, a)) == a`)
  exhaustively over every element of four fixtures up to a few dimensions past their own top dimension, not
  just spot-checked.
- **`elementsAtDim[G](sset, n)`**: enumerates ALL of `X_n` (not just its generators), by the same
  Eilenberg-Zilber normal-form theorem `SSetElement` itself already relies on — every generator `g` of
  dimension `p <= n` paired with every size-`(n-p)` subset of `{0,...,n-1}` as its word. Needed because
  `product` has to consider every element of `X_n x Y_n`, non-degenerate generators alone aren't enough.
- **`ProductGenerator[GX,GY](x: SSetElement[GX], y: SSetElement[GY])`**: the generator type for the product,
  literally a word-disjoint pair.
- **Top dimension is `maxDim(x) + maxDim(y)`, proved, not assumed**: a non-degenerate pair `(a,b)` at dimension
  `n` needs `a.word`/`b.word` disjoint subsets of an `n`-element set, and each has size `n - dim(generator) >=
  n - maxDim` (since `dim(generator) <= maxDim`). Disjointness forces `(n-maxDim(x)) + (n-maxDim(y)) <= n`, i.e.
  `n <= maxDim(x)+maxDim(y)` — beyond that bound no pair can possibly be non-degenerate, ever again, not just at
  that one dimension. This is a genuine strengthening of the plan (which only asked to verify the bound
  empirically, not derive it) found naturally while implementing.
- **Face maps**: `d_i(a,b) = (faceOf(i, a...), faceOf(i, b...))` on each side independently, reusing `faceOf`
  unchanged.

## A real bug: the stripped remainder needs relabeling, not just deletion — caught by `validate()`

The result of `faceOf` on each side can come back sharing common word entries even when the input pair didn't
(the pair is non-degenerate but a face of it isn't). Handling this needs a "strip the common degeneracy set `J`
from both sides" step. **First implementation attempt just deleted `J`'s entries from each side's word, keeping
the remaining entries' absolute values unchanged — wrong**, and caught immediately by
`FiniteSimplicialSet.validate()`'s own `d_i d_j = d_{j-1} d_i` check throwing `IndexOutOfBoundsException` deep
inside `faceOf`'s recursion on `product(minimalSphere(1), minimalSphere(2))` (the first case big enough to
actually exercise the stripping path — `product(minimalSphere(1), minimalSphere(1))`, the torus-matching case,
never hits it at all, by luck of that particular example's dimensions).

Root cause: `rawX.word`/`rawY.word` are both subsets of the *same shared* gap-position domain
`{0,...,n-2}` (both sides are simplices of the same dimension `n-1`). Deleting the shared positions `J` shrinks
that domain to size `m = (n-1)-|J|`, so the *remaining* (private) entries on each side must be relabeled via the
rank function — the standard order isomorphism from `domain \ J` down to `{0,...,m-1}` (`e -> e - |{j in J : j
< e}|`) — not left at their original absolute values, which can (and, in the triggering example, did) land
outside the smaller domain's valid range entirely. The *outer* wrapping word (`J` itself, used to redegenerate
the stripped pair back up to dimension `n-1`) needs no such relabeling — it's already expressed in the final
`(n-1)`-dimensional domain's own coordinates, exactly what an outer word is supposed to be; only the inner,
private remainder needs compressing into the smaller domain it now lives in.

Hand-verified on the triggering example (`d_0` of `g = (a=([1,0],e), b=([2],f))` in
`minimalSphere(1) x minimalSphere(2)`, dimension 3 -> 2): `rawX = ([0], e)`, `rawY = ([1,0], v)`,
`commonJ = {0}`. Naive deletion gives `strippedY = ([1], v)` — word entry `1`, but the target domain only has
size `m=1` (valid entries `{0}`), out of range. Relabeled: entry `1` has rank `1 - |{0}| = 0` among the
remaining domain, giving the correct `strippedY = ([0], v)`.

## Verification

- `product(minimalSphere(1), minimalSphere(1))` generator counts by dimension, hand-derived before writing any
  code and confirmed by the implementation: `(1, 3, 2)`, terminating exactly at the proved bound
  `maxDim+maxDim=2`. **Not** the minimal 2-edge Δ-complex torus (3 edges, not 2) — the raw categorical product
  is a strictly larger, non-minimal simplicial set, only homotopy equivalent to the hand-built `torus` fixture,
  not isomorphic to it; Betti-number agreement is therefore the correct cross-validation target, which it hits:
  `(1, 2, 1)`, matching the independently hand-built `torus` fixture via a completely different construction.
- `product(minimalSphere(1), minimalSphere(2))` matches Künneth's theorem for `S^1 x S^2`: Betti numbers
  `(1, 1, 1, 1)`. This is also the case that exercises the stripping/relabeling bug above, so it's simultaneously
  the strongest correctness evidence available (an independent classical theorem, not just "the identities
  hold") and the regression pin for the relabeling fix.
- `coproduct(minimalSphere(1), minimalSphere(2))`: Betti `(2, 1, 1)` — unreduced `H_0` adds directly across a
  disjoint union of connected spaces (this holds for *unreduced* homology, which is what this engine computes
  throughout; it's *reduced* `H_0` that would need the "-1" adjustment, not the ordinary case here).
- `validate()` clean on all of the above, which for `product` is real evidence: `identityErrors` computes nested
  `dOp(i, dOp(j, ...))`, directly exercising the stripping/relabeling logic through real recursion, not just the
  top-level `boundary` rule homology alone would touch.

`sbt test`: 231 total, 226 passed, 0 failed, 0 errors, 5 skipped, 1 pending (unchanged skip/pending baseline
from the prior session) — a clean full run, +8 examples over the previous baseline.

## Deliberately deferred, still not attempted

Unchanged from `.claude/WORKLOG-simplicial-sets.md`'s own list, now narrower since products/coproducts are
shipped:
- **Quotients / attaching maps** — identifying generators (or generators across a coproduct) under a gluing
  relation, respecting face compatibility. Not attempted. Probably the single most useful remaining
  construction for hand-building models directly, and a prerequisite for the bar construction below.
- **Bar construction / classifying spaces** — explicitly named as part of the original "full construction
  algebra" scope; would lean on `product` and quotients as building blocks once quotients exist. Not started.
- **Real multi-scale filtration / persistence support** — unchanged, lives entirely in the `CellStream` adapter
  layer, untouched by this session's work.
- **`PersistenceInChunksContext`/`SimplicialHomologyByDimensionContext`** — still hardcoded to
  `Simplex[VertexT]`, unrelated to this session.
