---
layout: main
title: Adding an engine or a complex
---

The two usual extensions are a new **complex** (a stream of cells with filtration values, and a name users call it by) and a
new **engine** (an algorithm that turns a stream into bars with representatives). Both are finished when the `Persistence`
verb, the facades and the guide know them, and when a test that their plausible wrong answer fails has passed. Read
[Contracts](contracts.md) first: both extensions are bound by it.

## A new complex

### 1. The stream

Subclass a stream that is close to yours: `CofaceSimplexStream` for a complex built by adding vertices to simplices (it gives
you the coface loop, `keepCriterion`, `filtrationValueOverride` and the array layers), or build a stratified stream by hand
(`ExplicitStreamBuilder` for a small one). Whichever you pick:

- build the ordering with `FiltrationOrdering.canonical`, and sort each dimension with that ordering reversed;
- report `homologyDegreeLimit` if your stream is cut at a dimension;
- keep `filtrationValue` cheap, since the reductions call it inside comparisons;
- make the values monotone along faces to the last bit.

A stream does not use an engine. Streams are generation; engines are reduction.

### 2. The entry point

Users do not call the stream class. They call an object (`VietorisRips`, `Cech`, `Witness`, `Dowker`, `DtmRips`, `SparseRips`,
`Truncated`), with `maxDimension` as the top **homological** degree, `Optional[Double]` for thresholds, and a
`LevelwiseSimplexStream` as the result. Add an object that does the same, with the stream's `+ 1` in one place.

If the complex is built from a point cloud, the object extends `PointCloudComplex` (`fromPoints(points, maxDimension,
maxFiltrationValue)`), which makes it a valid `complex = ...` argument of `Persistence`. If its natural input is something
else (a relation, landmarks), build it directly and pass the stream to `Persistence`.

To let `Persistence` take a whole new kind of input, give a `Conversion[YourInput, Persistence.Input[CellT]]`: it supplies
`stream`, `scale` (what `longerThanFraction` measures against) and `cells`. The remaining members of `Input` are shortcuts
to faster engines, and are off by default.

### 3. The test

**Compare against an independent construction, cell for cell and value for value**: the object against the hand-wrapped stream
class (`ComplexesSpec`) or against a slow reference (`VietorisRips`'s Enumerating implementation is the reference for the
others). Betti numbers cannot catch a wrong `+ 1` or a tie broken the other way. Add `respectsOrderingContract` on tie-heavy
fixtures, and a discriminating example for the field: compare over `F₂` and `F₃`.

If your stream provides array layers (`flatFiltration`), add it to the list of `FlatLayersSpec`: it compares the layers with
the stream built from cells, bit for bit.

### 4. The facades

A complex name in `matlab.TDA4j` (`ComplexKind`) and its options; the same in `cli.TDA4jConf`; the typings; Python gets it
free. See [Extending the facades](facades.md).

## A new engine

### 1. The engine

An engine is generic over `Field` and returns, for every bar, a representative (a `Chain` over the stream's cells, or the
engine's packed cell type). If you cannot yet return representatives, you have a research prototype: keep it out of the verb
until you can.

Decide what the engine takes: a `StratifiedCellStream`, or a particular stream (the packed Ripser engine takes a metric
space, the packed cubical engine a `CubicalGridStream`). The narrower the input, the faster the engine can be, and the more
the verb has to know about when it applies.

Give an engine `includeZeroLength` (default `false`), stop it at the stream's `homologyDegreeLimit`, and give it one **switch
for each optimisation** that changes no output.

### 2. The oracle and the switches

This is the pattern of the repository: **every fast engine is checked against a slower one that is easy to believe**, and
the slower one stays in the tree.

- The reduction of the packed Ripser engine must equal `ChainPackedRipserReference` term for term, essentials included
  (`PackedWorkingColumnSpec`); the dense-number cohomology reduction must equal the cell-keyed one
  (`CellKeyedCohomologyReference`, `CohomologyNumberingSpec`); the fast cubical and alpha engines equal an eager reference
  (`EagerFastCubicalReference`, `EagerFastAlphaReference`).
- **Each optimisation has a switch that turns it off**: `useApparentPairs`, `useEmergentPairs`, `useUnionFind`,
  `packSimplices`, `neighbourLists`, `wideIndices`. The switch serves two readers: a spec compares the engine with the
  optimisation on and off on the same inputs, and a benchmark does the same for time and memory. A switch that no spec flips
  is an untested optimisation.
- A reference that shares code with the engine proves less than it seems: the oracle should be written independently, and
  the hand-derived fixture is the last oracle.
- Fixtures: signed fields need at least five vertices to expose a sign error; a uniform sign flip of every coboundary changes
  nothing, so a sign mutation has to be non-uniform; use tie-heavy filtrations, where Vietoris-Rips complexes have structural
  ties.
- When an optimisation changes the representatives but not the bars (a different tie direction), compare the bars exactly
  and the representatives for validity: a cycle's boundary is zero, a cocycle is a cocycle, and it is present at the bar's birth.

The cycles and cocycles of an engine can both be had from one pairing: `Involution` derives the one the engine does not
compute natively, at the cost of one reduction of the birth or death cells only. Use it before writing a second engine
for the other kind.

### 3. The verb

`Persistence` has an `Engine` enum, and a `match` in `Persistence.compute` that resolves `Auto` and then runs the choice.

- Add the case, and a branch to the exhaustive matches (the compiler lists them).
- **`Auto` is decided in one place**: the `chosen` match. The present rule is the order in which the cheap and specific
  engines are asked: Ripser for the Vietoris-Rips complex of points or a metric space (`ParallelRipser` instead when there is
  more than one thread and it applies), `FastCubical` for an image of dimension two and up when cycles are asked for,
  `Cohomology` for everything else. A new rule goes where its input is recognised and says in the `Engine` scaladoc when it
  applies; if the choice depends on the input, ask the `Input` (`ripserApplies`, `parallelRipserApplies`, `cubicalGrid`),
  rather than match on its class.
- **Change `Auto` only with a measurement.** An engine that is faster on one input is an opt-in until a sweep shows where it
  wins, and the sweep is how its threshold is chosen (`bench/run.sh sweep-cubical`, `sweep-threads`;
  [Measuring performance](performance.md)).
- State what the engine cannot do and refuse it with a message that says what to call instead (`FastCubical` gives cycles
  only; `ParallelRipser` needs a prime coefficient field, no zero-length bars).

### 4. The surfaces

The engine's name in `EngineKind` of the facade, with its refusals; in the CLI's `--engine`; in the npm typings; in the
[persistence engines](persistence-engines.md) page (what it is, what it takes, the row in the streams-by-engines table); and
in `bench/` if it belongs in a comparison.

## Check before you open the pull request

- The new stream passes `respectsOrderingContract`, and equals its reference cell for cell and value for value.
- The new engine equals its oracle with every switch on and off, over `F₂`, `F₃` and a larger prime, on tie-heavy input, and
  agrees with at least one independent engine on the bars.
- Both platforms run the specs (`sbt tda4j/testFull`, `sbt tda4jJS/test`; [JVM and Scala.js](jvm-and-js.md)).
- A performance claim has an isolated A/B behind it, and an effect inside the noise is reported as unconfirmed.
- The surfaces are updated and a changelog fragment is in `changelog.d/` ([Pull request workflow](pr-workflow.md)).
