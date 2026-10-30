---
layout: main
title: The public API: the verb, diagrams and labs
---

This page covers the layer a user meets first: the `Persistence` verb, the
`PersistenceDiagram` it returns, and the labs for writing chain algebra by
hand. It also states the conventions every new public signature follows.
Everything here sits on top of the engines in
[Persistence engines](persistence-engines.md); none of it adds an algorithm.

## Easy, not simple

The user-facing API follows the ideals of Li Haoyi's libraries, with os-lib
(`os.read(path)`, `os.proc(cmd).call()`) as the model. They distill to five
rules:

1. **One obvious verb per task**, and the most common call is the shortest.
2. **Defaults for everything else**, as named parameters, not as extra objects
   to build first.
3. **Plain, immutable data back**, whose answers don't depend on the order you
   ask.
4. **Errors that say what to do.**
5. **The machinery stays reachable** for experts, but is never mandatory.

The measure is the first few lines a newcomer writes, and the documentation's
own code snippets are the sample. A first answer should not need to name a
stream, an engine with type arguments, a state object or a hand-written field
`given`. Those are the machinery of rule 5, there for whoever needs them.

Each rule maps onto a piece of the API:
- **Rules 1 and 2** gave the `Persistence` verb, with defaults for the degree,
  field, complex and engine.
- **Rule 3** gave `PersistenceDiagram`.
- **Rule 4** gave the messages that name the engine or parameter to use
  instead, and `@implicitNotFound` texts on the typeclasses.
- **Rule 5** kept the engines, streams and cursor public. It is also the
  reason for the visibility policy below. The engines' companions have
  inferring forms, such as `SimplicialHomologyEngine.persistentHomology(stream)`
  and `CellularPersistenceInChunksEngine.persistentHomology(stream, maxDim)`:
  the cell and filtration types come from the stream, and the coefficient type
  from the one `Field` given in scope.
- **The `into` parameter types and the prebuilt labs** remove the `Some(...)`
  wrappers and set-up lines.

"Easy" is not "simple". The ease for the caller is paid for inside the library:
- conversions at every input;
- a field chosen at runtime by an `Int`;
- a diagram whose coefficient type is a type member.

Two project constraints bound all of it: every computation stays generic over
the coefficient field, and every bar carries a representative. A convenience
that would drop either is not added.

When you add public API, write the first five lines a user would type, put them
in the docs, and count the ceremony.

## `Persistence`: one call from input to diagram

`Persistence(input, maxDimension, maxFiltrationValue, complex, characteristic,
engine, includeZeroLength, representatives)` (`homology/Persistence.scala`)
builds a complex, picks an engine, runs it to the end and returns an immutable
`PersistenceDiagram`. The defaults are degrees `0..2`, coefficients in `F_17`
(`FiniteField.DefaultPrime`), cycles as representatives, and no zero-length
bars.

**Inputs.** `input` is a `Persistence.Input[CellT]`, an `into` trait, so a
point cloud, a `FiniteMetricSpace[Int]`, an `Image` or any
`StratifiedCellStream[CellT, Double]` converts to one at the call site. The
conversions are givens in `object Input`. An input implements three members:
- `stream(maxDimension, maxFiltrationValue, complex)`, the filtered complex;
- `scale`, the size `longerThanFraction()` measures against (a point cloud's minimum
  enclosing radius);
- `cells`, the cell type's `OrderedCell` instance.

Two optional members open the fast paths:
- `ripserApplies` with `ripser`, for Vietoris-Rips on points or a metric space;
- `cubicalGrid`, for images.

To teach the verb a new kind of input, give a
`Conversion[YourInput, Persistence.Input[CellT]]`:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

final case class DistanceTable(rows: Seq[Seq[Double]])

given Conversion[DistanceTable, Persistence.Input[Simplex[Int]]] = t =>
  Persistence.Input.fromMetricSpace(ExplicitMetricSpace(t.rows))

val square = DistanceTable(
  Seq(
    Seq(0.0, 1.0, 1.4, 1.0),
    Seq(1.0, 0.0, 1.0, 1.4),
    Seq(1.4, 1.0, 0.0, 1.0),
    Seq(1.0, 1.4, 1.0, 0.0)
  )
)
val diagram = Persistence(square, maxDimension = 1)
```

**Complexes.** For a point cloud, `complex` chooses the construction.
`VietorisRips`, `Cech` and `AlphaShapes` implement `PointCloudComplex`, so the
dispatcher objects double as the choice. Complexes that need more than points,
such as witness landmarks or a Dowker relation, are built first and passed in
as a stream.

**Degrees.** A truncated stream reports the degree it was built for
(`homologyDegreeLimit`). The verb uses that degree when `maxDimension` is not
given, and refuses a larger one: the stream's top cells would look essential.

**Engines.** `engine = Engine.Auto` resolves before any work is done:
- `Ripser` (the packed engine) when `input.ripserApplies(complex)`, which is
  Vietoris-Rips on points or a metric space;
- `FastCubical` when cycles are asked for and the input is a grid of dimension
  2 or more;
- `Cohomology` otherwise. On a `CubicalGridStream` this runs
  `PackedCubicalCohomologyEngine`, everywhere else `CellularCohomologyEngine`.

An explicit engine that cannot take the input (`Ripser` on an image,
`FastCubical` on points, or `FastCubical` with cocycles) throws an
`IllegalArgumentException` that names the engines that can.

**Coefficients at runtime.** The field is chosen by an `Int`, so
`Coefficients(characteristic)` (`algebra/Coefficients.scala`) carries the
coefficient type as a type member `C` with its `Field` given. `0` gives
`Double` compared within a precision; a prime `p` gives `FiniteField(p)`.
Anything else throws.

**Representatives.** Each engine computes one kind of representative
natively: cocycles for `Ripser` and `Cohomology`, cycles for the others. It
derives the other kind from its pairing through `Involution`; see
[Persistence engines](persistence-engines.md#cycles-and-cocycles-from-one-pairing-involution).

## `PersistenceDiagram`

`PersistenceDiagram[CellT]` (`barcode/PersistenceDiagram.scala`) is a trait
with a type member `Coefficient` and a deferred `given` for its field. The
coefficient type is decided at runtime, so it cannot be a type parameter the
caller writes. `import d.given` brings the field into scope for arithmetic on
the representatives. `PersistenceDiagram.Of[CellT, C]` names a diagram with a
known coefficient type, and `PersistenceDiagram(bars, maxDimension,
lastFiltrationValue, scale)` builds one.

The queries return new diagrams or lists and never mutate:
- `dim(k)`, `at(f)` (the diagram truncated at `f`) and `essential`;
- `longest`, `longest(n)`, `longerThan(x)` and `longerThanFraction()`;
- `bettiNumbers` and `triples`.

`lastFiltrationValue` lets `at(f)` tell a class alive at the end from one cut
off at `f`.

Besides the verb, the cursor's `snapshotAt(f)`, `SimplicialSet.persistentHomology`
and `ClassifyingSpace.persistentGroupHomology` return diagrams. A new
computation that reports bars should return one too, with a representative on
every bar.

## Labs

A lab is for writing chain algebra by hand. `abstract class Lab(characteristic,
precision)` (root `package.scala`) holds a `Coefficients`, `Fp(n)`, the field's
`given`, cats' `.show` syntax, and flat re-exports of every public top-level
type of the core and `sset`. Its two subclasses add chain arithmetic and an
implicit widening for their own cell type:
- `TDAlab` works on `Chain[Simplex[Int], _]`;
- `CubicalLab` works on `Chain[Cube, _]`.

Each has prebuilt instances `F2`, `F3`, `F17` and `Reals`, so
`import TDAlab.F17.{*, given}` is a user's only import.

The re-export list is generated. After adding or opening a public top-level
type, run `python3 .claude/scripts/tdalab-exports.py`; `TDAlabExportsSpec`
fails until you do. No engine ever consults a lab.

## Conventions for new public API

**Optional and point parameters.** A public optional number is an
`Optional[Double]` (`algebra/Optional.scala`), not an `Option`. Point inputs
are a `PointCloud` (`streams/PointCloud.scala`). Both are `into` case classes
with their conversions in their companions:
- `Optional` takes `2.0`, `2`, `Some(2.0)` or `None`;
- `PointCloud` takes `Array[Array[Double]]`, `Seq[Seq[Double]]` or
  `Seq[Array[Double]]`.

The call site needs no import. Inside a method body, read the value with
`.toOption`.

**`Show`.** Every user-facing data type has a cats `Show` in its companion,
next to its other default instances (see the givens rule in
[Architecture](architecture.md#package-layout)). cats' `Show` is invariant, so
an instance for a hierarchy is typed for its subtypes:
`given endpointShow: [F: Show, E <: BarcodeEndpoint[F]] => Show[E]`. With that,
`ClosedEndpoint(1.0).show` resolves without an ascription. A user's own
`Show[Double]` then changes how every bar prints.

**Visibility.** Classes are public and concrete classes are `open`. Users
compile without `-language:adhocExtensions`, and extending a non-`open` class
warns. Which class to reach for first is said in scaladoc, not enforced by
access modifiers. A `private[tda4j]`, `sealed` or `final` carries a comment
naming its reason; the accepted reasons are listed in `CONTRIBUTING.md`:
- invariant-bearing state;
- the representatives guarantee;
- a generic helper name that would join every user's wildcard import;
- the internals of the MATLAB and CLI facades.
