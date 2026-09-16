# User Guide for TDA4j

TDA4j implements persistent homology and related techniques from computational and applied topology. This
guide assumes you know what a simplicial complex, a filtration, and a persistence barcode are — it does not
assume you know Scala. If you want to understand *why* the library is built the way it is, or you're
planning to write new code against it, see the @ref:[Developer's Guide](../developers-guide/index.md) instead;
this page is about getting things done as a caller.

## Quick-start: Scala

This is the fully worked, verified-against-current-source path. All type and method names below were
checked directly against `src/main/scala` while writing this guide — if you find they've drifted, trust the
source over this page and consider it a bug report.

### Building and taking the boundary of a simplex

```scala 3
import org.appliedtopology.tda4j.*

// Coefficients need an explicit Field instance in scope -- there is no default one for Double.
// DoubleApproximated treats two coefficients as equal within epsilon, which matters for the
// zero-checks that drive chain reduction.
given Double is Field = Field.DoubleApproximated(1e-9)

val triangle = Simplex(1, 2, 3)      // same as ∆(1, 2, 3)
triangle.boundary[Double]            // Seq((Simplex(2,3), 1.0), (Simplex(1,3), -1.0), (Simplex(1,2), 1.0))
```

### A full persistence computation

```scala 3
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)
given ctx: TDAContext[Int, Double, Double]()
import ctx.{*, given}

// A small point cloud: three points roughly forming a triangle
val points: Array[Array[Double]] = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(0.5, 0.8))
val metricSpace = EuclideanMetricSpace(points)

// Vietoris-Rips filtration, connecting points up to distance 2.0, up through dimension 2
val stream = RipserStream(metricSpace, maxFiltrationValue = 2.0, maxDimension = 2)

val state = ctx.persistentHomology(stream)
state.barcodeAt(Double.PositiveInfinity).foreach(println)
```

`TDAContext[VertexT, CoefficientT, FiltrationT]` bundles the naive, reference-grade persistence engine
(`SimplicialHomologyContext`) together with convenient chain-arithmetic operators and an implicit
`Simplex -> Chain` conversion, so `1.0 ⊠ ∆(1,2) - ∆(2,3)` works directly once `ctx`'s members are imported
(`import ctx.{*, given}`). It's a good default for exploration and for anything where you want to query
the diagram at intermediate filtration values or get representative cycles back, via
`state.diagramAt(f)`/`state.barcodeAt(f)` (see the @ref:[Developer's Guide](../developers-guide/index.md) if you
need something `TDAContext` doesn't wrap — larger complexes where chunked parallelism matters, or
cohomology specifically).

### Alpha complexes instead of Vietoris-Rips

```scala 3
val shape = Alpha(points.toSeq, dispatch = "helix")   // or "DQP"
```

**Important**: `Alpha(points)` with no `dispatch` argument, or `dispatch = "default"`, currently always
resolves to `"helix"` regardless of your point cloud's shape or dimension — despite the name, it is not
actually choosing between backends yet. If you want the DQP backend, you have to ask for it by name. See
"Which alpha-complex backend?" below for how to choose.

## Quick-start: Java (11+)

**Honest framing up front**: there is no dedicated Java-facing API in this codebase today, and nothing
below has been exercised by an actual Java caller as part of this codebase's own test suite — a repo-wide
search finds zero `.java` files anywhere in this project. The closest thing that exists is
`src/test/scala/.../APISpec.scala`, whose own doc comment describes itself as "developing the non-Scala
facing API functionality," and which is currently a stub (its persistence-computation test body is a bare
`val homology = ???`). If your project genuinely needs to call TDA4j from Java soon, budget time to either
build that adapter yourself or ask the maintainers where it stands.

That said, here's concretely what makes calling the existing Scala surface directly from Java awkward,
so you know what you're up against rather than discovering it one compiler error at a time:

- **Context (`given`/`using`) parameters have no Java equivalent.** Every method with a context bound —
  which is most of the library, e.g. `boundary[CoefficientT: Field]` — compiles to a method with an *extra
  trailing parameter list* for the typeclass dictionary. From Java, you'd have to construct and pass a
  correctly-shaped `Field`/`RingModule`/`Ordering` instance by hand at every call site. This is possible in
  principle (Java can implement a Scala trait's abstract methods) but is not remotely ergonomic.
- **Most useful behavior lives in `extension` methods**, which compile to static methods on a
  compiler-synthesized module class (not instance methods on the type itself), so IDE method-resolution
  and autocomplete from Java won't find `.boundary()`/`.dim` on a `Simplex` the way you'd expect from a
  normal Java class.
- **Some operators are Unicode symbols that aren't legal Java identifier characters at all** (`⊠`, `∆`).
  The `RingModule` operators do have plain-ASCII, `@targetName`-annotated aliases you can call directly —
  verified in `RingModule.scala`: `+` compiles to `add`, `-` to `subtract`, `<*` to `scalarMultiplyRight`,
  `|*|` to `scalarMultiplyLeft`, and `⊠` to `scalarMultiplyLeft2`. `Field`'s own `+`/`-`/`*`/`/` operators
  (`Field.scala`) carry no `@targetName`, so they'd only be reachable under Scala's standard compiler-
  generated symbolic-name encoding (e.g. roughly `$plus`, `$times`) — usable from Java, but not something
  we'd recommend building against without confirming the exact mangled names via `javap -p` on the compiled
  classes first.
- **`Simplex[VertexT]` is an opaque type** with zero runtime representation distinct from
  `scala.collection.immutable.SortedSet` — from Java's perspective, a `Simplex` you receive back from a
  Scala method call *is* a `SortedSet`, with none of `Simplex`'s own extension-method API attached to it in
  a way Java's type system can see.

**If you need to call this from Java today**, the realistic path is a small Scala-side adapter: ordinary
methods with plain generics, no context parameters (bake in one fixed coefficient choice, e.g. `Double` via
`Field.DoubleApproximated`), ASCII names, and plain Java collections/arrays in and out rather than `Seq`/
`Simplex`/`Chain` directly — essentially what `APISpec.scala` is the seed for, and what the Matlab section
below sketches in more detail (an object like that would very likely serve Java callers too, since Matlab's
own Java bridge has to deal with exactly the same friction points).

## Quick-start: Matlab

Matlab can call into Java/JVM libraries, but through its Java bridge — which, like plain Java, cannot use
Scala's context parameters, extension-method dispatch, opaque types, or Unicode operator names. **No
Matlab-facing entry point exists in this codebase yet** — the previous draft of this guide described one as
though it were already available (`Api` object, "implementations of most of the tasks of immediate
interest"); that was aspirational, not current. A repository-wide search (`grep -rn "object Api" src/main`)
finds nothing.

`src/test/scala/.../APISpec.scala` is the actual, present-day starting point for this work — its doc
comment already frames itself as developing "the non-Scala facing API functionality and the non-expert API
functionality," and its (currently unimplemented) second test case sketches the intended shape: take a
point cloud, build a metric space, run persistent homology, and query the diagram at a filtration value,
all without the caller ever touching a `given`, an extension method, or a `Simplex`/`Chain` directly.

If you're picking this up, a Matlab/Java-facing `Api` object would need to expose, at minimum:

- **Construction entry points that take plain arrays**: a point cloud as `double[][]`, not a
  Scala-idiomatic type.
- **One fixed (or explicitly selected, via a plain `String`/`int` flag rather than a typeclass) coefficient
  choice per call** — e.g. `Field.DoubleApproximated` baked in by default, since asking a Matlab caller to
  supply a `Field` typeclass instance isn't realistic.
- **Persistence computation methods that return plain data**: arrays or lists of `(dimension, birth, death)`
  tuples (or parallel arrays), not a `PersistenceBar[FiltrationT, Chain[...]]` carrying a Scala-side
  representative-cycle annotation.
- **No operator overloading and no Unicode names** — `add`/`subtract`/`scale`-style plain method names
  throughout, matching the `@targetName` aliases the `RingModule` operators already have (see the Java
  section above) rather than inventing new ones.
- **A choice of which persistence engine and alpha-complex backend to use exposed as a plain flag**, with
  sane, explicitly-documented defaults — see "Which persistence engine?" and "Which alpha-complex backend?"
  below for what those defaults should probably be and why.

This is forward-looking design guidance, not a description of anything that works today.

## Which persistence engine?

TDA4j currently has four independently-implemented persistence algorithms (`Homology.scala`) — they are not
variants of one shared engine, and their trustworthiness is not uniform. As a user, not a contributor, here
is what you need to know to pick correctly:

- **Default choice for most use cases**: `TDAContext`/`SimplicialHomologyContext`/`CellularHomologyContext`
  — the naive, reference-grade algorithm. It's what the Scala quick-start above uses. Supports querying the
  diagram at intermediate filtration values and returns representative cycles.
- **Large complexes, want to exploit parallelism**: `PersistenceInChunksContext` — a chunked "clear and
  compress" algorithm, audited and trustworthy, but one-shot (no intermediate querying).
- **You specifically need cohomology, on a Vietoris-Rips/clique complex over integer vertex labels**:
  `RipserCohomologyContext` — trustworthy for cohomology with clearing; one-shot only.
- **Do not use `SimplicialHomologyByDimensionContext`.** As of this writing it crashes unconditionally on
  any complex with more than one connected component's worth of structure (a `NoSuchElementException`
  thrown from its own constructor on ordinary input) and has, as far as anyone can tell, never successfully
  computed a result. A guide that simply listed "four engines, pick one" without this warning would
  actively mislead you into hitting this.

See the @ref:[Developer's Guide's persistence-engines page](../developers-guide/persistence-engines.md) for the
full detail behind each of these claims if you want it.

## Which alpha-complex backend?

`Alpha(points, dispatch)` (see above) chooses between two independent implementations:

- **`"helix"`** (`HelixDelaunay`) — an actual Delaunay triangulation, computed incrementally. This is what
  `dispatch = "default"` currently resolves to, always, regardless of your point cloud. Has a known,
  *quantified* failure mode: zero failures across 20,000-trial fuzz testing at ambient dimension 2 and 5,
  but roughly 1-in-170 at ambient dimension 4 with 20-30 points, on ordinary-looking (not adversarially
  constructed) input with a near-cospherical local cluster. If you're working at ambient dimension 4 or
  higher, don't treat Helix's output as unconditionally reliable ground truth without being aware of this.
- **`"DQP"`** (`AlphaShapeDQP`/`AlphaComplexDQP`) — a from-scratch dual active-set quadratic-programming
  method (Carlsson & Carlsson 2024) that never builds a Delaunay triangulation at all. Its real strength is
  high ambient dimension, where Delaunay-based approaches become infeasible, and getting exact homology
  rather than an approximate persistence diagram. Be aware: **the paper's own published benchmarks are
  mixed** — it loses to Ripser on 2 of 4 of the paper's own persistence examples, and to qhull-based
  Delaunay on some inputs. The honest value proposition is high-dimensional feasibility and exactness, not
  raw speed — don't oversell it as a strict upgrade over Helix.

Both backends agree that in degenerate (cospherical) point configurations — e.g. points sitting on a regular
grid — the alpha complex genuinely contains higher-dimensional simplices than you might expect from a
triangulation-based mental model (a unit grid in the plane produces 3-simplices, one per unit square, not
just triangles). This is mathematically correct behavior, not a bug in either backend, and users coming
from CGAL or GUDHI (which typically report a triangulation, not the true alpha complex, in the degenerate
case) may find it surprising.

## Tutorials

@ref:[Tutorials](../tutorials/index.md) — currently a placeholder; porting Henry Adams' JavaPlex tutorials
to TDA4j is tracked there as future work, not yet done.
