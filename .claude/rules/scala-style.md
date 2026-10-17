---
paths:
  - "src/**/*.scala"
---

# Scala style used throughout

Loads when you read or write Scala. Project-wide rules are in `.claude/CLAUDE.md`; the three that matter most for every
edit (the newest syntax is deliberate, optional parameters, no non-ASCII top-level names) are repeated there.

Uses Scala 3.7+'s newest context-abstraction syntax — don't "correct" it to older idioms:

- `Type is TypeClass` for context bounds/givens (`Chain[CellT, CoefficientT] is RingModule`).
- `type Self: Ordering as ordering` — named context-bound aliasing inside trait bodies.
- Unicode algebra operators: `⊠` (scalar action), `∆(...)` (simplex literal), `<*`, `|*|` (`RingModule.scala`/
  `Field.scala`).
- `opaque type Simplex[VertexT] = SortedSet[VertexT]` / `opaque type Cube = Vector[Int]` — no runtime wrapper; API
  is extension methods.
- Optional parameters, never sentinels: `Optional[Double]` (below) for public ones, `None` + `.getOrElse(...)` inside
  (a default cannot reference an earlier parameter of the same list).
- A method's context bounds desugar to a `using` clause AFTER every explicit parameter list, so an earlier default
  cannot use that given: make it a `using` clause of its own ahead of the parameter (`Chain.reduceByUntil`).

**Opaque-type extension methods** live in the type's companion (`object Simplex`/`Cube`), found by implicit scope.
Hazards: opaque transparency is file-scoped (so `simplexIsOrderedCell`/`cubeIsOrderedCell` live in their own files), and
a wildcard-imported stdlib extension of the same name wins (so `min`/`max` stay top-level).
`WORKLOG-extension-companion-objects.md`.

**No top-level `object`/`class` with a non-ASCII name**: scaladoc writes one page FILE per such type (`∆$.html`), and a
JVM under a POSIX locale cannot encode it (`sbt doc` dies with `InvalidPathException`). `∆` is therefore `val ∆ :
Simplex.type = Simplex` (`WORKLOG-package-flatten.md`); unicode extension methods and vals are fine.
