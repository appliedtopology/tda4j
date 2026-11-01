---
paths:
  - "src/test/**"
---

# Writing specs

Loads when you read or write a test. Project-wide rules are in `.claude/CLAUDE.md` (tests first: write the *discriminating*
spec, one the plausible wrong answer fails). Tests are specs2.

**Shared test generators** (`matrixGen`) live in `src/test/.../streams/Generators.scala`, never in a spec. Before
creating a test file, `ls` for its name: `Write` overwrites silently.

**specs2 5.9 types `===`**: `Int === Long` or `Fp === Int` no longer compiles (5.5 compared with `==` and hid two
always-true properties, #79). Fix the operands (`.toLong`, wrap in `Fp`), do not loosen the comparison.

**Test compiles are warning-free** (`WORKLOG-test-warnings.md`): `Test / scalacOptions` has `-language:implicitConversions`, because
specs2's matchers (`must contain(x)`, `(a > b) ==> ...`, a `Boolean` as a `Result`) are implicit conversions; main does not, so a
conversion of our own still warns. A pattern match on an `Any` selector (`case x: Foo =>`) takes `c.asMatchable` first.
A bare identifier in a narrative fence of a tutorial page ("pure expression does nothing") gets a method call (`circleBars.toVector`).

**specs2 gotcha**: in a class mixing `ScalaCheck`, give a `Seq[Simplex[_]]` an explicit type ascription before
`.forall` — otherwise it can resolve to specs2's `ValueCheck` extension with confusing errors.

**specs2 traps in the plot specs** (`WORKLOG-plot-grammar.md`):
- In a non-final statement `(a must X).and(b must Y)` checks only `a`; the by-name `b` is never forced. Write separate `must`
  statements (#99 has the unaudited rest of the repo).
- `must throwAn[IllegalArgumentException]("text")` takes a REGEX: `(`, `)` and `.` in the message must be escaped or left out.
  In a spec that also runs on Scala.js use `SpecThrows.throwWith[E]("text")` instead: specs2's message matcher reports a
  mismatch there for messages that do match (PR #295's JS failure).
- An example that THROWS is reported as an error (`!`, "N errors"), not a failure (`x`, "N failures"): a script that breaks the
  code and counts failures misses a mutation the example does catch.

**A spec never swaps `System.err`/`System.out`** (or any other process-wide state): sbt runs spec classes concurrently in one
JVM, so a swapped stream captures another spec's output (and its restore can restore the wrong stream). The CLI takes its
error stream (`TDA4jCLI.run(args, out, err)`); pass a buffer (`WORKLOG-cli-spec-stderr-race.md`).

**A property that hunts rare inputs** (2000 cases because a bug showed once in thousands of clouds) takes its count
from `TestPlatform.propertyCases(n)`: all `n` on the JVM, a tenth on Scala.js (`rules/scalajs.md`). A spec that takes
ScalaCheck's default mixes in `PlatformScalaCheck` instead of `ScalaCheck` (100 cases on the JVM, a tenth on JS; a
spec's own `given Parameters` wins; the command-line `scalacheck.mintestsok` has no effect here). A loop over seeds,
primes or option combinations takes its size from `TestPlatform.perPlatform(jvm = .., js = ..)`. A spec that shared
code runs must stay cheap on JS, where it is 3 to 60 times slower than on the JVM.

**Shrinking a grid on the JVM needs a mutation argument; shrinking it on JS does not.** The JVM run is the search, the JS
run only has to show a platform difference, which any input shows. Before a JVM-side reduction, mutate the code under
test (flip a comparison or a sign in the file the spec is about), run the old and the reduced spec, and keep the
reduction only if the reduced spec fails on every mutant the old one failed on (`WORKLOG-test-suite-audit.md`). A
property that checks only structure (closure, monotonicity) cannot see a sign error in the values: give the spec a few
examples with values derived by hand.

**Compare big values, then report the first difference**: `must beEqualTo` renders both sides into its message and
`Result.foreach`/`forAll` keep every message, so thousands of comparisons of whole listings or arrays exhaust the shared
2 GB test JVM (CI's OOM on #186: one spec needed more than 256 MB alone). Compare with `==` and assert on the first
difference as an `Option[String]` (`must beNone`), or collect the failures (`wrong.take(3) must beEmpty`)
(`WORKLOG-layered-inorder.md`).
The rendering also costs time: `beEqualTo` on a 5,000-element listing took 3.5 s; where the failure text is not needed,
`(a == b) must beTrue` (`WORKLOG-test-suite-audit.md`).
