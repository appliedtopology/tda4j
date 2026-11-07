# Audit of `.and(` chains in the specs (#99), 2026-11-07

Point-in-time snapshot. The question (#99): in a specs2 `mutable.Specification` a statement `(a must X).and(b must Y)` that is not
the last expression of its example checks only `a`. How many of the specs' `.and(` chains are like that?

## Result

None. `src/test` has 521 uses of `.and(` in 60 files (the issue counted 329 in 40, at 2026-10-16). A lexical scan (strings and
comments blanked, brackets counted, the next statement of the same block compared by indentation) reports 37 chains that are
followed by something; every one of the 37 is followed by the header of the next example (`"..." >> {`, `in`, `should`), so each is
the whole body of its example, which specs2 evaluates. No chain is followed by another statement of its own block.

## The premise, checked

A planted example `(1 must beEqualTo(1)).and(2 must beEqualTo(3))` followed by `3 must beEqualTo(3)` PASSES: the trap is real in
this specs2 (5.9.1). The same example with the chain last fails.

## The guard

`SpecHygieneSpec` (JVM only, it reads `src/test`) runs the scan over every spec, so a later spec cannot bring the trap back. Its
detector is checked on synthetic sources (braces, an indented block, a chain over several lines, a chain that is last, a chain
inside a lambda, `.and(` in a string and in a comment) and mutated three ways (no report, no blanking of strings, no exemption
for the next example's header): each fails an example. With the planted example above in the tree, the repository scan reports
`PlantedAndSpec.scala:7`.

A chain wrapped in parentheses, `((a must X).and(b must Y))`, was first missed (review of the PR): the scan took the
wrapping parenthesis for a block of the chain's own and found the next statement outside it. The detector now counts the
parentheses that open at the very start of the statement and are still open at the `.and(` as grouping, not as a block (a
paren after an identifier, as in `foreach(x => ((a).and(b)))`, is a call and still is one). Fixtures: wrapped on one line, over
two lines, wrapped twice with the arguments broken inside (reported); wrapped and last, wrapped inside a call's argument
(accepted). Mutants: the wrapper count ignored (the wrapped fixtures report nothing, as the first version did) and every open
bracket counted as a wrapper (the argument and the lambda fixtures are reported): each fails an example.

The exemption for the next example's header is a heuristic: a chain followed by `step(...)`, `sequential` or another statement at
the same indentation outside an example would be reported, and the fix is then to write the spec so that it is not.
Not covered, checked with a planted spec: a chain inside a lambda whose result is dropped (`Seq(1).foreach(x => (a).and(b))`) passes
vacuously (the same `foreach` with a plain `must` fails), and the scan accepts it on purpose; `src/test` has none (the specs use
`Result.foreach` / `TestResult.foreach`, which evaluate each result): #322. Not covered either: `.and(` written as the infix `and` on matchers, and `.or(` (a non-final `(a must X).or(b must Y)` is too strict, not
vacuous: `a` throws before `or` is reached).
