# Compiler warnings cleanup (2026-09-19)

Asked to go through the codebase's compiler warnings, fix as many as possible, and comment on any left unresolved
with why -- with an explicit constraint: no fix in packed-ripser-relevant code that could degrade performance.

## What was actually visible, and how it was found

A default `sbt compile`/`sbt test` only prints warning *counts* for `-feature`/`-deprecation` categories ("there
were N feature warnings; re-run with -feature for details"), not the warnings themselves -- neither flag was
enabled in `build.sbt` before this pass. Re-ran `Compile/compile`/`Test/compile` with
`-feature -deprecation -unchecked` (and, separately, `-explain`) added via `sbt -no-colors 'set ... scalacOptions
++= ...'` (not committed to `build.sbt` until the fixes were confirmed) to get full detail: **102 warnings total**,
spanning 8 distinct categories.

## Fixed

**Feature warnings, 89 of 102 -- `implicitConversions` (~85) and `adhocExtensions` (~4).** Not fixed file-by-file:
enabled `-language:implicitConversions`/`-language:adhocExtensions` project-wide in `build.sbt`, since both fire on
intentional, already-established patterns, not accidental risky conversions:
- `implicitConversions`: almost entirely specs2's own matcher/prop DSL (`asResultToProp`, `matcherIsValueCheck`,
  `typedValueCheck`) firing on ordinary, idiomatic use of the test framework across ~15 spec files, plus this
  project's own deliberate `Simplex -> Chain` conversion (`TDAContext`, `package.scala`).
- `adhocExtensions`: `TDAContext` (`package.scala`) extends `SimplicialHomologyContext`, and
  `CubicalHomologyContext` (`streams/CubicalStream.scala`) extends `CellularHomologyContext` -- both genuine,
  permanent, already-documented architectural relationships (CLAUDE.md's "Persistent homology"/"Cubical
  complexes" sections), not one-off test conveniences. Deliberately did NOT mark either parent class `open`
  instead (the more "principled" per-class fix): that's a real API-surface decision (declaring a class part of
  the extensible public surface) left to the project lead, not made unilaterally here, especially with two
  test-file `given ctx: TDAContext[...]()`/`given bc: BarcodeContext[...]()` instantiations also triggering the
  same warning (via Scala 3's given-with-constructor-args desugaring) that `open` wouldn't obviously cover anyway.

**Deprecation warnings, 16 of 102 -- all `copyArrayToImmutableIndexedSeq`** (the deprecated implicit
`Array -> immutable.IndexedSeq` conversion). Fixed at each call site with an explicit `.toIndexedSeq` (matching
the deprecation message's own suggestion, and an idiom this codebase already uses elsewhere, e.g.
`AlphaComplexSpec.scala:364`'s pre-existing `Alpha(points.toIndexedSeq, dispatch)`): 2 in main source
(`AlphaComplexDQP.scala:1033` -- `Simplex.from(Array(x))` simplified to `Simplex.from(Seq(x))`, no Array involved
at all since it's a one-element literal; `AlphaShapes.scala:107` -- `pts.map(Point.apply).toIndexedSeq`), 14 in
test sources (`AlphaComplexSpec.scala` x12, `HomologySpec.scala` x2), all `Alpha(points, ...)`-shaped calls passing
an `Array[Array[Double]]` where `Alpha` wants a `Seq`.

**Type/pattern-match warnings, 3 -- `Chain.equals`/`PackedRipserCohomologyContext.DiameterIndex.equals`.**
Scala 3's Matchable safety check flags `obj match { case other: T => ... }` when the match selector's static type
is `Any` (true for any `override def equals(obj: Any)`, since that signature is fixed by `java.lang.Object`).
Fixed per the compiler's own `-explain` guidance: `obj.asMatchable match { ... }` (`.asMatchable`, imported from
`scala.compiletime`, is a compile-time-only cast satisfying the check -- confirmed zero runtime cost, not just
assumed, and re-measured the packed engine afterward to be sure: `sphere3_96` still medians ~740ms, within this
session's own established noise band, no regression). `Chain.equals`'s `Chain[CellT, CoefficientT]` pattern
additionally needed `@unchecked` (not `Chain[?, ?]`, which would stop compiling: the method's own body calls
`other.collapseAll()`, which needs a `Field` instance for `other`'s coefficient type -- only resolvable by
assuming, as the original code already unsoundly-but-intentionally did, that `other`'s type parameters are the
same as `this`'s). `PackedRipserCohomologyContext.DiameterIndex` needed only `.asMatchable`, no `@unchecked`
(non-generic, so no erasure issue) -- this one IS on the packed engine's hot path (`equals`/`hashCode` run on
every `basis`/`generators`/`cleared` map/set operation in `persistentCohomology`), which is exactly why the fix
was checked to be zero-cost rather than assumed safe.

**Syntax warnings, 3 -- `method X must be called with () argument`.** `Chain.scala`'s two
`entries.dequeue`/`entries.dequeue()` (identical bytecode either way, pure syntax) and
`APISpec.scala`'s `scala.util.Random.nextDouble` -> `nextDouble()`.

**Potential-issue warning, 1 -- `FiniteField.scala`'s dead `Fp.toString` extension.** The compiler's own message
was direct: "Extension method toString will never be selected from type Fp because Fp already has a member with
the same name" -- `opaque type Fp = Int` means `Fp`'s runtime representation is `Int`, whose inherited `toString`
always wins over an extension method of the same name/signature, confirmed unreachable, not just unused. Checked
nothing depends on the intended `"Fp(5)"`-style output (`grep` for `.toString`/`show`/`print` near `Fp` usage,
nothing found) before deleting outright, per this project's own "if you're certain it's unused, delete it
completely" convention -- not preserved as dead code, and not reworked into a separate typeclass-based
pretty-printer (a real option, but a bigger design change than a warnings cleanup pass calls for).

## Left deliberately unfixed, with why

**`-Wunused:all` surfaces ~319 further warnings, not touched.** This flag isn't part of the default build (had to
be explicitly added to discover this), so it wasn't among what was actually visible before this pass. The large
majority (~300) are `unused import` -- but this codebase's own documented convention
(CLAUDE.md's "Package layout" section) is **broad wildcard imports by design**
(`import org.appliedtopology.tda4j.<pkg>.{given, *}`, not narrow per-symbol imports), specifically so every file
keeps the same visibility it had before the package split. `-Wunused:imports` doesn't distinguish "this wildcard
brought in one symbol you use" from "this wildcard brought in twenty you don't" -- flagging (and fixing) these
would mean either narrowing every such import to only its used symbols (directly reversing a documented
architectural decision, not a warnings cleanup) or accepting the noise. Left alone rather than deciding this
unilaterally. The remaining ~19 (`unused private member`/`unused local definition`/`unused pattern
variable`/`unused explicit parameter`) are more plausibly genuine dead code, but weren't chased further in this
pass since they'd need the same `-Wunused:all` flag enabled to even see, and enabling it selectively while
leaving the dominant `imports` case is an awkward middle ground -- worth a dedicated look if wanted, not folded
into this pass. Not enabled in `build.sbt`.

## Made permanent, not just used for this pass

`-language:implicitConversions`, `-language:adhocExtensions`, `-feature`, `-deprecation`, `-unchecked` are now in
`build.sbt`'s `scalacOptions` permanently (previously only `-source:future`/`-language:experimental.modularity`
were) -- so a future deprecated-API use or unchecked type test shows up in ordinary `sbt compile`/`sbt test`
output going forward, not just when someone thinks to re-enable these flags by hand. None of these fail the
build on their own (no `-Xfatal-warnings`), only print.

## Validation

Full `sbt test` clean throughout (237 examples, 232/0/5/1, unchanged), `scalafmtAll` applied, a clean
`Compile/compile`/`Test/compile` with every flag above enabled now prints **zero warnings**. Packed engine
re-measured after the `DiameterIndex.equals` fix specifically (the one packed-ripser-relevant change) to confirm
no regression, per the standing instruction that no fix here should degrade performance.
