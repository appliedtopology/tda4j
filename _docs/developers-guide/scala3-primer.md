# A Scala 3.7+ primer for this codebase

If you know topology cold but Scala 3's newest context-abstraction features are new to you, read this
before anything else. TDA4j leans hard on Scala 3.7+ syntax that didn't exist a few years ago, and code
samples you find by searching the web (or asking an LLM trained on older Scala) will often look
superficially similar but be subtly wrong for this codebase. Every construct below is demonstrated with
real code from the current source tree, not a simplified stand-in.

## The problem all of this solves

TDA4j wants to write code like "any `CellT` that has a `boundary` operation and a total order can be fed
to the homology machinery" — generic over the *shape* of a capability, not over a fixed class hierarchy.
Scala's traditional answer to this is the typeclass pattern: a trait describing the capability, and
`given`/`implicit` instances witnessing that a particular type has it. Scala 3.5+ added a second, newer
way to write typeclasses (sometimes called "type member style" or "modularity" style, following SIP-64,
<https://docs.scala-lang.org/sips/sips/typeclasses-syntax.html>, and described further at
<https://dotty.epfl.ch/docs/reference/experimental/typeclasses.html>). TDA4j uses this newer style for its
*own* typeclasses (`RingModule`, `Field`, `Cell`, `OrderedCell`, ...) while still using the traditional
`Ordering[T]`-style parametrized typeclasses for standard-library ones. You need to be fluent in reading
both.

## Context bounds, old and new

A context bound like `[CellT: Ordering]` has existed in Scala for a long time — it's sugar for an
implicit/given parameter `(using Ordering[CellT])`. Two things changed recently that show up constantly in
this codebase:

**Named context bounds** (`as`). Instead of writing a context bound and then separately summoning it, you
can name the bound's evidence inline:

```scala 3
trait OrderedCell extends Cell:
  type Self: Ordering as ordering
```

(`Chain.scala`.) This says "`Self` has an `Ordering`, and I'll call that ordering instance `ordering`
inside this trait body" — no `summon[Ordering[Self]]` needed anywhere below. The same pattern appears on
plain type parameters too, e.g. `Chain.apply[CellT: Ordering as ord, ...]` in `Chain.scala`'s `from`.

**Multiple bounds in one clause.** `type Self: {Ordering, Filterable}` (seen in `SimplexStream.scala`'s
`Filtration` trait) bounds one type by several typeclasses at once, replacing the older, clunkier
`[T: Ordering: Filterable]` chained-colon style.

## `Type is TypeClass`: the typeclass-as-type-member pattern

This is the single most important piece of syntax to internalize before reading `Chain.scala` or
`Homology.scala`. A typeclass in this style is a trait with an abstract *type member* called `Self`,
rather than a type parameter:

```scala 3
trait HasDimension:
  type Self
  extension (self: Self) def dim: Int

trait Cell extends HasDimension:
  type Self
  extension (self: Self) def boundary[CoefficientT: Field]: Seq[(Self, CoefficientT)]

trait OrderedCell extends Cell:
  type Self: Ordering as ordering
```

(`algebra/Chain.scala`, current source — note `boundary` returns `Seq[(Self, CoefficientT)]`, not a
`Chain`; more on that in [Architecture](architecture.md).)

To say "type `T` implements `Cell`," you don't write `Cell[T]` — you write **`T is Cell`**, using the
special infix type alias `is` that the typeclasses feature provides (roughly `infix type is[A, C <: {type
Self}] = C {type Self = A}` — a refinement type under the hood, not a real generic parameter). You'll see
this both as a type (`Chain[CellT, CoefficientT] is RingModule`, a type used all over `Homology.scala`) and
as a context bound (`[CellT: OrderedCell]`, which desugars to "there exists a given instance of `OrderedCell
{ type Self = CellT }`" — the compiler treats both spellings, `OrderedCell[CellT]`-shaped and `CellT is
OrderedCell`-shaped, as satisfying the same bound).

A concrete instance looks like this — `SimplexOrderedCell.scala`'s actual construction of `Simplex[VertexT] is
OrderedCell`:

@:snip(/src/main/scala/org/appliedtopology/tda4j/cells/SimplexOrderedCell.scala, given-example)

where `simplexIsOrderedCell` builds an anonymous `new (Simplex[VertexT] is OrderedCell):` instance,
providing the `ordering` member `OrderedCell` requires and, in an `extension (spx: Simplex[VertexT])`
block, concrete implementations of `dim` and `boundary`. Once this `given` is in scope, **any**
`Simplex[Int]` value can call `.dim` and `.boundary[Double]` directly, with no explicit typeclass-dictionary
plumbing at the call site — that's the entire payoff of the pattern. **One gotcha specific to this
codebase's own package split**: a `given` only comes into scope via a wildcard import that explicitly says
so — `import org.appliedtopology.tda4j.cells.{given, *}`, not just `import
org.appliedtopology.tda4j.cells.*`. A plain `import pkg.*` does **not** bring `given` instances into scope
in Scala 3; every file in this codebase that reaches across a subpackage boundary uses the `{given, *}`
form for exactly this reason — see [Architecture](architecture.md)'s package-layout section.

One syntax detail worth flagging because it trips people up: `given [CellT: OrderedCell as oCell] =>
Ordering[CellT] = oCell.ordering` (`algebra/Chain.scala`) is the "anonymous given via arrow" form — a `given`
with no name, whose *value* is given after `=>`, parametrized by a context-bound clause on the left of the
arrow. Read `given [bounds] => Body = value` as "for any type satisfying `[bounds]`, here is a value of
type `Body`." This is how the library bridges its own `OrderedCell.ordering` member back into the
standard-library `Ordering[T]` typeclass, so anything that's `OrderedCell` automatically also participates
in ordinary Scala code (`.sorted`, `SortedMap`, etc.) that expects a plain `Ordering`.

## Extension methods

`extension (self: Self) def dim: Int` inside a trait declares an *abstract* extension method; a concrete
`given` instance provides the method body in its own `extension (...)` block. Outside of typeclass bodies,
plain top-level `extension` blocks are used too, e.g. `SimplexOps.scala`'s entire file is one big
`extension [VertexT](spx: Simplex[VertexT])` block delegating most of `SortedSet`'s API (`.size`, `.map`,
`.filter`, `.union`, ...) to `spx.underlying`. This is why `Simplex` "feels like" a `SortedSet` even though,
as the next section explains, it technically isn't one at runtime.

## Opaque types

```scala 3
opaque type Simplex[VertexT] = SortedSet[VertexT]
```

(`Simplex.scala`.) `Simplex[VertexT]` is *defined to be* `SortedSet[VertexT]` at the bytecode level — zero
runtime wrapper, zero boxing overhead — but outside `Simplex.scala` itself, the compiler treats `Simplex[VertexT]`
and `SortedSet[VertexT]` as distinct, incompatible types. This buys type safety (you can't accidentally pass
a raw `SortedSet[Int]` where a `Simplex[Int]` is expected, so a caller can't build a `Simplex` bypassing
whatever invariants its constructors enforce) at zero runtime cost. The two extension methods `.underlying`
and `.asSimplex` (`Simplex.scala:19,21`) are the sanctioned crossing points between the two views — every
other file that needs to fall back to raw `SortedSet` operations goes through `.underlying`, never an
unsafe cast.

Practical consequence for writing new code: you cannot pattern-match structurally on a `Simplex`'s
contents without going through its extension-method API or the provided `unapplySeq` (`Simplex.from(...)`
constructs; `Simplex.unapplySeq` deconstructs, letting you write `case Simplex(a, b, c) => ...`).

## `given`/summon resolution: static, not dynamic — and why this matters more than usual here

This is ordinary Scala 3 implicit-resolution behavior, but it is unusually load-bearing in this codebase,
enough that it has caused real, confirmed bugs (see [Hard-won invariants](gotchas.md) for the full story).
The short version: when a `given` instance's body summons another `given` (e.g. `Chain[CellT,
CoefficientT] is RingModule`'s implementation needs an `Ordering[CellT]` in scope to build its internal
`SortedMap`/`PriorityQueue`), that inner summon happens **once, when the outer `given` is first
constructed** — not fresh on every later call to the outer instance's methods. If you construct a `given`
too early, before the specific `Ordering` you actually wanted is in scope, the instance permanently closes
over whatever fallback *was* in scope at that moment, and no later import or context change will fix it.
This is exactly why every `HomologyState` in `homology/Homology.scala` declares `given Ordering[CellT] =
stream.filtrationOrdering` as its *first* line, before summoning `Chain[CellT,CoefficientT] is RingModule`
on the next line — order matters, and it isn't stylistic.

## What to read next

[Architecture](architecture.md) walks the algebraic core and complex-construction layers using the
concepts above; [Persistence engines](persistence-engines.md) covers the four homology algorithms and
their trust status; [Hard-won invariants](gotchas.md) is the concentrated list of non-obvious rules (like
the `given`-timing one above) that this codebase depends on and that are easy to violate even once you
know they exist.
