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

**specs2 gotcha**: in a class mixing `ScalaCheck`, give a `Seq[Simplex[_]]` an explicit type ascription before
`.forall` — otherwise it can resolve to specs2's `ValueCheck` extension with confusing errors.

**specs2 traps in the plot specs** (`WORKLOG-plot-grammar.md`):
- In a non-final statement `(a must X).and(b must Y)` checks only `a`; the by-name `b` is never forced. Write separate `must`
  statements (#99 has the unaudited rest of the repo).
- `must throwAn[IllegalArgumentException]("text")` takes a REGEX: `(`, `)` and `.` in the message must be escaped or left out.
- An example that THROWS is reported as an error (`!`, "N errors"), not a failure (`x`, "N failures"): a script that breaks the
  code and counts failures misses a mutation the example does catch.

**A property that hunts rare inputs** (2000 cases because a bug showed once in thousands of clouds) takes its count from
`TestPlatform.propertyCases(n)`: all `n` on the JVM, a tenth on Scala.js (`rules/scalajs.md`). A spec that shared code
runs must stay cheap on JS, where it is 3 to 60 times slower than on the JVM.
