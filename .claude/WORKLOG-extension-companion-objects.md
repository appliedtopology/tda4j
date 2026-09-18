# Extension methods: moving opaque-type extensions into companion objects

**Question asked**: `.claude/WORKLOG-cubical.md` flagged, but explicitly left unverified, a "more robust" fix for
the same-named top-level extension collisions hit three times while building `Cube` (`underlying`/`show` already
taken by `Simplex[VertexT]`, forcing `encoded`/`describe` instead) -- move each opaque type's extensions into its
own companion object, since Scala's extension search checks a receiver type's companion object specifically. This
session's task: verify that hypothesis empirically, and if it holds, apply it across the whole library.

## Verifying the hypothesis, in isolation first

Built standalone `scala-cli` repros (not part of the tda4j source tree) before touching any real code, since a
wrong assumption here would mean restructuring the whole library for nothing:

1. **Confirmed the original bug reproduces from first principles**, not just from memory of `WORKLOG-cubical.md`:
   two opaque types `A`/`B`, each in its own file, each with a top-level `extension (a: A) def show` /
   `extension (b: B) def show`. Compile error: `"show is already defined as method show in AType.scala ... Note
   that overloaded methods must all be defined in the same group of toplevel definitions"`. This is a sharper
   root cause than `WORKLOG-cubical.md`'s framing ("extension resolution doesn't fall back") -- it's actually that
   Scala 3 does not treat same-named top-level `def`s in *different files* of one package as a legal overload
   group at all, full stop, extension methods included.
2. **Confirmed the companion-object fix works**, including the two structural details that matter for this
   codebase specifically: a *generic* opaque type with a *non-generic* companion (`Simplex[VertexT]`'s own
   shape), and the extension body living in a *separate file* from the opaque type + companion object, mixed in
   via a trait the companion `extends` (`SimplexOps.scala`'s own shape) -- both compiled and ran correctly, two
   unrelated opaque types reusing `underlying`/`show` with zero collision.

## A hazard the isolated repro didn't surface: same-file opaque-type transparency

Applying the fix to the real `Cubical.scala` (moving `encoded`/`describe`/etc. into `object Cube`) broke `sbt
compile` with `"value encoded is not a member of Cube"` -- inside `Cubical.scala` itself, at `cubeOrdering`'s
`x.encoded` call, textually *after* `object Cube` closes.

Root cause, confirmed by a fourth `scala-cli` repro varying exactly one thing at a time: **opaque-type
transparency is scoped to the whole file the `opaque type` is declared in, not just the companion object** --
standard Scala 3 behavior, but not something the first three repros exercised (their "consumer" code always
lived in a separate file). From inside that same file, the receiver's *dealiased* type is what extension-method
implicit-scope search keys off, not the opaque alias -- so `x.encoded` looks for `encoded` on `SortedSet`'s (or
`Vector`'s) own companion, not `Simplex`'s (or `Cube`'s), and fails. Two distinct fmodes depending on the method:
- **Hard compile error** for any method the underlying representation type has no member of that name for
  (`dropIndex`, `encoded`, `nondegenerateAxes`, ...).
- **Silent behavior substitution, no compile error at all**, for any method whose name happens to coincide with a
  real member of the underlying representation (`size`, `zipWithIndex`, `toSeq`, ...) -- `SortedSet`/`Vector`
  already have members by those names, so the dealiased call quietly resolves to *those* instead of the intended
  `SimplexOps`/`Cube` override. This one is the scarier failure mode precisely because it doesn't announce itself;
  it was caught here only because `Simplex_is_OrderedCell`'s `boundary` also called `dropIndex` (no stdlib
  equivalent), which *did* hard-fail and forced a closer look, not because the `size`/`zipWithIndex` substitution
  was independently noticed.

Confirmed via a fifth `scala-cli` repro (`fixed3` vs. `fixed4` in job scratch, not checked into the repo) that
isolates this to exactly "same file vs. different file" as the only varying factor, both otherwise identical.

**Fix**: split each opaque type's `*_is_OrderedCell`/ordering machinery -- the code that actually *calls* the
type's own extension methods -- into its own file, separate from the file declaring `opaque type X = ...` and its
companion object. `SimplexOrderedCell.scala`/`CubicalOrderedCell.scala` are new files created for exactly this.
One additional wrinkle for `Cube` specifically: `Cube_is_OrderedCell`'s `boundary` built new cube values via a raw
`val upperCube: Cube = coords.updated(...)` coercion, which -- unlike an extension-method call -- needs the
transparency directly and can't be satisfied by a companion object at all; fixed by rewriting it as an explicit
`.asCube` call (itself relying on transparency, but only inside `Cubical.scala` where it's defined, same as every
other extension). `Simplex_is_OrderedCell` never had this problem: `SimplexOps.scala` was already written using
`.asSimplex`/`.underlying` conversions throughout (it was *always* a separate file from `Simplex.scala`, so it
never had the option of relying on transparency).

## A second hazard, orthogonal to the first: collision with a wildcard-imported stdlib extension

Fixing the above got `Simplex.scala`/`Cubical.scala` themselves compiling clean, but left two *different* files
broken: `FiniteMetricSpace.scala` (`spx.flatMap(...).max`) and `SimplexStream.scala` (`spx.min`), both with a
bizarre-looking error -- `Found: Simplex[Double] => Simplex[Double], Required: Double`, i.e. `.max`/`.min`
resolving to a *function value* instead of a `Double`.

Root cause: both files do `import math.Ordering.Implicits.*`, which pulls `scala.math.Ordering.Implicits.
infixOrderingOps` -- stdlib's own `def min(rhs: T)`/`def max(rhs: T)` binary extension for any `T: Ordering` --
into *lexical* scope (phase 1 of extension resolution). Once `SimplexOps.min`/`.max` moved into `object Simplex`'s
companion (phase 2 only), extension resolution's "try phase 1 fully before ever trying phase 2" rule (the exact
same "commit to the first candidate, don't fall back" behavior `WORKLOG-cubical.md` already documented for the
original `Cube`/`Simplex` collision, just against the standard library instead of a sibling opaque type this time)
committed to `infixOrderingOps.max(rhs)` -- eta-expanded to a function value for lack of an explicit argument,
since `.max` was called with none -- and never got to try `SimplexOps.max` at all.

**Fix**: `min`/`max` (and *only* those two -- `infixOrderingOps` also defines `<`/`<=`/`>`/`>=`/`equiv`/`compare`,
none of which `SimplexOps` happens to define) stay as a plain top-level extension clause, not moved into the
trait/companion. General lesson recorded in `SimplexOps.scala`'s own comment: a method name that collides with a
common, wildcard-importable stdlib extension is *safer* left at the top level (phase 1, where ordinary overload
resolution -- not phase priority -- picks the actually-applicable candidate) than moved to a companion object.
The companion-object fix targets collisions between two of *this codebase's own* opaque types; it does not help,
and can actively hurt, against a same-named stdlib extension already competing for the same phase-1 slot.

## What actually changed

- `Simplex.scala`: `underlying` moved into `object Simplex` (now `extends SimplexOps`); `asSimplex` stays
  top-level (its receiver is `SortedSet[VertexT]`, not `Simplex[VertexT]` -- companion-object lookup is keyed by
  receiver type, so it could never be found there regardless of collision risk); `simplexOrdering`/`∆` stay in
  this file (the former needs the file's own transparency for a `SortedSet`->`Simplex` return-type coercion, the
  latter is a plain qualified call, neither makes an extension-method call on a `Simplex[VertexT]` value).
- `SimplexOps.scala`: everything except `min`/`max` wrapped in `trait SimplexOps`, mixed into `object Simplex`;
  `min`/`max` stay a top-level extension clause (stdlib collision, see above).
- `SimplexOrderedCell.scala` (new): `Simplex_is_OrderedCell` + its `given`, moved out of `Simplex.scala` (same-file
  transparency hazard, see above).
- `Cubical.scala`: `encoded`/`ambientDim`/`isNondegenerate`/`isDegenerate`/`lowerCoordinate`/`nondegenerateAxes`/
  `latticeCoordinates`/`isVertex`/`isTop`/`describe` moved into `object Cube`; `asCube` stays top-level (receiver
  is `Vector[Int]`, same reasoning as `asSimplex`). Names were **not** renamed back to `underlying`/`show` --
  the fix removes the *need* for distinct names, but renaming touches every existing call site and is left as a
  separate, independent decision for the maintainer.
- `CubicalOrderedCell.scala` (new): `cubeOrdering`, `Cube_is_OrderedCell` + its `given`, moved out of
  `Cubical.scala` (same hazard as `Simplex_is_OrderedCell`, plus the raw-coercion wrinkle above).

## Verification

Full grep confirmed the ENTIRE scope of top-level `extension` clauses in `src/main/scala` was exactly these two
opaque types (`Simplex`, `Cube`) -- `RingModule`/`Field`/`Chain`/`Barcode` etc. use extension methods declared
*inside trait bodies* (`HasDimension`/`Cell`/`OrderedCell`), a different, already-safe mechanism (resolved via
`given`-instance lookup on the trait's abstract `Self` type, not top-level/companion extension search), so no
other file needed touching.

One more opaque type exists in the library and was correctly, deliberately left untouched: `Fp`
(`FiniteField.scala:9`), nested inside `class FiniteField(val p: Int)`. It never had this problem in the first
place -- an opaque type nested inside a class/object, rather than declared at the top level of a file, is scoped
to that enclosing class; its `extension (fp: Fp) ...` block is a member of `FiniteField`, not a package-level
top-level definition, so it was never eligible to collide with anything package-wide to begin with. Worth noting
explicitly as the *most* robust of the three patterns in this codebase, not just an exclusion.

`FiniteField.scala:26`'s pre-existing compiler warning (`Extension method toString will never be selected from
type Fp because Fp already has a member with the same name`) is a superficially similar-sounding but unrelated,
pre-existing hazard -- a real member (`Any.toString`) always outranks an extension of the same name, nothing to
do with companion-object placement -- and was not touched by this session.

The stdlib-wildcard-import hazard (item 2 above) was also checked on the test side, not just assumed clean because
`sbt test` compiled: `grep -rln "Ordering.Implicits\|Numeric.Implicits" src/test/scala/` finds three files
(`ChainSpec.scala`, `SimplexSpec.scala`, `SymmetryGroupSpec.scala`); none combine a *wildcard* `Ordering.Implicits.*`
import with a `.min`/`.max` call on a `Simplex[VertexT]` value specifically (`SimplexSpec`/`ChainSpec` import only
the named `sortedSetOrdering`; `SymmetryGroupSpec` does wildcard-import `Ordering.Implicits.*` and calls `.min`,
but on a hypercube-symmetry orbit collection, not `Simplex[VertexT]`) -- consistent with, not merely implied by,
the clean full-suite compile.

`sbt clean compile`: clean, identical warning set to the pre-change baseline (verified by `git stash -u` +
clean-compiling the original tree side by side). Full `sbt test`: 204 examples, 0 failures, 0 errors (same as
baseline count) -- including targeted reruns of `CubicalSpec` (9/14), `CubicalStreamSpec` (8/331),
`CubicalImageSpec` (12/22), `SimplexSpec` (2), `SimplexStreamSpec` (7), `AlphaComplexSpec` (1/2000),
`HomologySpec` (11/110), `VietorisRipsSpec` (2/900) -- exact match to their pre-change example/expectation counts.
`scalafmtAll`/`scalafmtCheck`/`scalafmtSbtCheck`: clean.

**Specifically on the "silent wrong-method substitution" risk flagged in the transparency hazard above** (a real
concern raised on review, not something the passing test count alone settles by itself -- most of this codebase's
homology specs default to `Field.DoubleApproximated` or F2, and F2 famously cannot distinguish a correct
alternating sign from a constant one): `SimplexSpec.e2` pins `Simplex(1,2,3).boundary` over a SIGNED field
(`Field.DoubleApproximated`) to the *exact* order and sign -- `Simplex(2,3)->1.0, Simplex(1,3)->-1.0,
Simplex(1,2)->1.0` -- which depends directly on `spx.zipWithIndex`/`spx.dropIndex` (now moved into `object
Simplex`'s companion) resolving inside `SimplexOrderedCell.scala` to the intended `Set`/`Simplex`-returning
`SimplexOps` versions rather than silently falling back to `SortedSet`'s own same-named members (which would
reorder or misassociate the alternating-sign zip). It passed. `HomologySpec`'s "Barcode is independent of the
coefficient field for these torsion-free complexes" test (F2 vs. F3 vs. Q agreement on triangle/tetrahedron/torus
fixtures) is the same check at larger scale and also passed. Between the two, both order and sign are pinned, not
merely "some barcode came out and didn't crash."

## Verdict

**Feasible, and now applied across the whole library** -- but not a mechanical "just move it" fix. Three distinct
failure modes had to be found and handled, each confirmed empirically rather than assumed from the first
successful repro:
1. The core hypothesis itself (companion-object placement avoids cross-opaque-type name collisions) -- **true**.
2. Same-file opaque-type transparency defeats companion-object extension lookup for any consumer code living in
   the *same file* as the opaque type declaration -- real, required splitting `*_is_OrderedCell` machinery into
   separate files for both `Simplex` and `Cube`.
3. A companion-object extension can lose to a same-named, wildcard-imported stdlib extension at a call site that
   never had that problem while the extension was top-level -- real, required keeping `min`/`max` as an
   explicit, documented exception.

Given #2 and #3, this is not a fix to apply reflexively to *every* future opaque type without checking both
hazards again: watch for (a) any code in the *same file* as `opaque type X = ...` that calls `X`'s own extension
methods by dot-syntax (route it through a separate file, or an explicit qualified/`.asX`-style call, instead), and
(b) any extension method name that might collide with a common stdlib extension already reachable via a wildcard
import somewhere the type is used (leave that specific name at the top level).
