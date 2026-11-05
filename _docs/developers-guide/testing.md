---
layout: main
title: Testing conventions
---

Tests are [specs2](https://etorreborre.github.io/specs2/) specifications, with ScalaCheck for properties. The rule behind
all of them: **write the spec first, and make it discriminating**, so that the plausible wrong answer fails it. A spec that
a wrong implementation also passes is a comment.

## What to check

- **The invariant, not only the Betti numbers.** For a construction or an engine, name what it preserves (`∂∂ = 0`, monotone
  filtration, the pairing, a valid representative) and test that. Betti numbers cannot see a wrong `+ 1`, a tie broken the
  other way or a sign error.
- **Two fields at least.** Compare over `F₂` and `F₃` (and a larger prime). `F₂` hides sign errors and odd torsion. Signed
  fixtures need five or more vertices: a smaller set of vertices is iterated in the same order whatever the hash.
- **Representatives**: a cycle has boundary zero and is present at its birth; a cocycle is closed and does not vanish before
  its birth. Different engines report different valid representatives, so compare validity across engines and exact terms
  only between a fast engine and its own reference.
- **A fast engine against a reference, a stream against an independent construction** (cell for cell, value for value),
  with every optimisation switch on and off. [Adding an engine or a complex](adding-engines-and-complexes.md) has the pattern.
- **Hand-derived values** where values matter. A property that checks structure (closure, monotonicity) cannot see a sign
  error in the values: give it a few examples whose answers you derived on paper.
- **Stream contract**: `HomologyFixtures.respectsOrderingContract(stream)` checks that every face is present, values are
  monotone along faces, and each dimension's bucket is sorted. Do not use `#bars == #cells` as an oracle: zero-length
  bars are dropped by default. Opt in with `HomologyFixtures.totalBarsAccountForAllCells` when that is the claim.
- **Fixtures with ties.** Vietoris-Rips complexes have structural ties (every simplex of dimension two or more ties with its
  longest edge), and random clouds often have none. A fixture where vertex 1 is born early and vertex 9 late separates the
  cell order from the filtration order.
- **Mutation-check the spec.** Flip a comparison or a sign in the code under test, and see the spec fail. If it does not,
  it is not testing that line. A uniform sign flip of every coboundary changes no output, so a sign mutation has to be
  non-uniform.

## Where specs live

- `src/test/scala/...` mirrors the main tree, and runs on both platforms ([JVM and Scala.js](jvm-and-js.md)).
- `src/test/scala/tda4juser/` is **deliberately outside the library's package**: it checks what a user's own code sees
  (`UserImportsSpec`: one import is enough, no given leaks into scope). A user-facing guarantee goes here.
- Shared generators (`matrixGen`) live in `src/test/.../streams/Generators.scala`, never in a spec; fixtures in
  `HomologyFixtures`.
- **Before creating a test file, `ls` for its name:** `Write` overwrites silently.
- Facade specs call `matlab.TDA4j` directly. A spec asserting on the complete barcode uses the test-only shims `FullBarcode`
  (facade) or `CliFull` (command line), which turn off the facade's 1% persistence threshold; a plain call hides
  short bars.
- Benchmark specs (`*BenchmarkSpec`) only print tables, and are skipped unless `-DrunBenchmarks=true`; CI never runs them.

## Docs are tests

The user guide and the tutorials are checked, so that a page cannot drift from the code.

- **Every Scala fence in `_docs/` is compiled** by `sbt tda4j/doc`, each fence on its own, so each has its own imports and
  data. A fence that only restates a declaration is marked `scala sc:nocompile`. (`scala 3 nocompile` is silently ignored.)
- **A tutorial page ends with a "The whole script" fence.** The build copies the first Scala fence after that heading into a
  generated `object <Page>Script`, and joins the page's `scala sc:nocompile` narrative fences into a function that is
  never called but compiles, so a narrative that drifted fails `Test/compile`. The page's spec, `tutorial/<Page>Spec`, asserts
  **every number the prose quotes**, on `<Page>Script.<val>`. Never retype page code in a spec: if the spec needs a value,
  add that `val` to the script.
- **Compute first, then write.** Run the code, read the output, then write the prose around it. If a dataset does not show
  the effect, change the dataset; never change the claim.
- **Language tabs**: where the facade supports the task, the code appears in tabs in the order Scala, MATLAB, Python. A MATLAB
  tab has a spec that calls `matlab.TDA4j` with the same options and asserts the numbers the tab quotes. The Python
  tabs run: `python/tests/test_docs.py` executes every Python fence of `_docs/` and the READMEs and checks the quoted
  numbers, one `ORACLES` entry per fence, so a new fence without an entry fails.
- Shared data for the pages is `_docs/tutorials/data/`, written by the seeded `TutorialData`; a spec guards its drift.
- Pages say what the code does and what to call. No history ("used to", "was fixed"), no worklog references, no timings in prose.

## Cheap specs

The suite shares one 2 GB JVM and runs on Scala.js, where it is much slower.

- **Compare big values with `==`, and report the first difference.** `must beEqualTo` renders both sides into its message,
  and `Result.foreach` keeps every message, so thousands of comparisons of whole listings exhaust the heap (and cost 3.5
  seconds for 5,000 pairs). Assert on an `Option[String]` (`must beNone`) or on the first few failures
  (`wrong.take(3) must beEmpty`).
- **Size loops with the platform seams.** `TestPlatform.propertyCases(n)`, `PlatformScalaCheck`, and
  `TestPlatform.perPlatform(jvm, js)` ([JVM and Scala.js](jvm-and-js.md)). The time of the suite is in about fifty
  examples that repeat a check over seeds and options, not in redundant specs, so that is where to look.
- **Do not build a complex bigger than the check needs.** An enumeration test of `BarcodeRegressionSpec` runs on 25 to 30 points
  in 2 to 6 dimensions, because larger clouds cannot be enumerated.
- The tutorial specs add about 90 seconds to `testFull`.

## specs2 traps

- **`===` is typed** in specs2 5.9: `Int === Long` and `Fp === Int` do not compile. Fix the operands (`.toLong`, wrap in
  `Fp`); do not loosen the comparison.
- **`(a must X).and(b must Y)` in a statement that is not the last checks only `a`.** The `b` is by-name and never forced.
  Write two statements.
- **`must throwAn[E]("text")` takes a regex**: escape `(`, `)` and `.`. On Scala.js use `SpecThrows.throwWith[E]("text")`.
- **An example that throws is an error, not a failure.** A script that mutates the code and counts failures misses a
  mutation the example does catch.
- In a class that mixes in `ScalaCheck`, give a `Seq[Simplex[_]]` an explicit type before `.forall`; otherwise it may
  resolve to specs2's `ValueCheck` extension with a confusing error. A pattern match on an `Any` selector takes
  `c.asMatchable` first.
- **A spec never swaps `System.out` or `System.err`**, or any other process-wide state: sbt runs spec classes concurrently in
  one JVM, so a swapped stream captures another spec's output. `TDA4jCLI.run(args, out, err)` takes its streams; pass a buffer.
- `Map[G, Fp]` equality compares raw representatives (`-1` and `1` differ over `F₂`): compare cochains with
  `CupProduct.isCoboundary`.
- Test compiles are warning-free. `Test / scalacOptions` allows implicit conversions because specs2 needs them; main code does not.
