# Implementing the API audit: field fixes, the cursor contract, the `Persistence` verb, labs (2026-10-03)

Point-in-time record. The audit is `DESIGN-api-audit.md`; the project lead accepted it except (1) the default field --
F₁₇, not F₂ (65537 considered, "a little bit provocative") -- and (2) the cursor: kept on purpose (a days-long run must
be inspectable and keep its output if it dies); the audit's order-dependence finding is a presentation bug.

## Fields (`5bc78cb`)

- `@implicitNotFound` on `Field` and `OrderedCell`: spiked first -- works at context bounds (an engine's constructor),
  not for a bare `summon[...]`, which keeps Scala's own message. `${Self}` interpolates.
- `FiniteField.times` multiplied two `Int`s. Normalized residues keep `|x| <= (p-1)/2`, but `Fp(a)` (`a % p`) and the
  inverse table were never normalized, so `divide` could compute 32768 × 65536 = 2³¹ at p = 65537. Now a `Long` product;
  inverses normalized. `LargePrimeFieldSpec` (BigInt oracle, deliberately unnormalized inputs at 65537 and 46349) fails
  4/5 against the old code.
- **F₂ bug found by that spec**: `norm`'s upper bound was `(p-1)/2`, i.e. 0 at p = 2, so it mapped 1 → -1 → 1 and
  `isEqual(1, -1)` was false over F₂. Zero tests were unaffected (so homology was right), which is why it survived; it is
  the root of the docs rule "`Map[G, Fp]` equality: -1 vs 1 over F₂ differ". Bound is now `p/2` (same for odd p).

## The cursor contract (`a476fd1`)

`diagramAt(f)` = the diagram truncated at `f` (born `<= f`, deaths capped at `f`, a class alive at `f` dies "at `f`"
unless no cell enters after `f`), regardless of cursor position. Two bugs:
- naive: "essential at `f`" was decided by `cellIterator.hasNext` -- the cursor's position, not the stream. A cursor that
  had run to the end reported a class alive at 0.5 as essential. Now decided by the stream's last filtration value.
- chunks: essential bars were not filtered by `lower <= f` at all (classes born after `f`, at +∞) -- the likely reason
  the tutorial rule banned `barcodeAt(f)` at an intermediate `f` (rule lifted).
`DiagramQuerySpec` (12 random clouds × shuffled queries: long-lived cursor == fresh; chunks == naive at every `f`)
failed 3/5 before the fix. Added `advanceFor(budget)` (wall-clock slices, always progresses), `processedCells`,
`totalCells`.

## `Persistence` verb, `PersistenceDiagram`, `Optional`, `PointCloud` (`30205df`)

Spikes (scratch project, 3.9.0, `-feature`): `into case class` / `into final case class Optional[+A]` with conversions
in the companion apply at exactly those parameter types, no feature warning; `Optional` from `A` and from `Option[A]` lets
old `Some(...)`/`None` call sites compile unchanged; a bare `3` needed an explicit `Int -> Optional[Double]`. Scala 3
forbids default arguments on more than one overload, so the verb takes one `into sealed trait Input[CellT]` with
conversions from every input kind; the cell type (and so the diagram's) follows from the input.

Design choices: coefficient type as a member of `PersistenceDiagram` (chosen at runtime by `characteristic`;
`import d.given` for arithmetic on representatives). `Engine.Chunks` default, not Ripser: the packed Ripser engine's
representatives are over `DiameterIndex`, not simplices. Alpha takes no threshold (builds the whole complex) and says to
use `diagram.at(f)`. The facade's default prime moved 2 → 17 with it (no tested output changed).

`PersistenceVerbSpec` oracles: verb == explicit engine over F₁₇; every input shape and threshold spelling agree; naive ==
chunks; `d.at(f)` == cursor `diagramAt(f)`; RP² Betti (1,1,1) over F₂ vs (1,0,0) by default and over the reals;
representatives are cycles. One wrong expectation of mine, corrected: a sublevel image filtration ends contractible, so
a ring is a finite H₁ bar [0, 1), not essential.

## Labs (`1f56497`), typed alpha / Optional / inferred engines (`8becccc`)

`abstract class Lab` + `TDAlab`/`CubicalLab` + prebuilt `F2/F3/F17/Reals`; companion spellings for the top-level
helpers lab users could not reach. `Optional[Double]` threaded through the public dispatchers with a body rewrite
(`param` → `param.toOption`, excluding named-argument left-hand sides). Inferring companion forms of the engines: the
coefficient type comes from the one `Field` in scope -- safe because `Field`'s companion holds no instances (two fields
in scope: compile error; none: the new message).

## Downstream check: the modularity requirement (not changed; for the lead)

A scratch project compiled against the packaged jar with NO flags fails: "object Persistence is marked @experimental:
Added by -language:experimental.modularity". Everything compiled under that flag is `@experimental`, and Scala refuses
non-experimental use. Compiling the library without the flag: ~100 errors, all `C: Field`-style context bounds on
`Self`-member typeclasses ("Illegal context bound: Field does not take type parameters") -- the experimental part of the
typeclass syntax this codebase chose. A non-experimental facade module cannot help (non-experimental code cannot call
experimental definitions). With `import scala.language.experimental.modularity` at the top, the same plain project needs
nothing else: verb, `into` conversions (no `-preview`), `import TDAlab.F2.{*, given}` all work. So user setup is two
lines, and doc fences now show both.

## Tooling note

After `sbt package` and a `TDA4J_SCALA_VERSION=3.8.4 sbt doc`, `sbt testFull` failed to compile tests with every main
class "Not found"; `sbt clean Test/compile` had 0 errors. Stale incremental state.

## Verification

testFull 859 → 864 → 875 → 880 → 883, 0 failures at every commit; final docs build (3.8.4) clean; lint clean.
