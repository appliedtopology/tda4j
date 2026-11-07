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

The exemption for the next example's header is a heuristic: a chain followed by `step(...)`, `sequential` or another statement at
the same indentation outside an example would be reported, and the fix is then to write the spec so that it is not.
Not covered: `.and(` written as the infix `and` on matchers, and `.or(` (a non-final `(a must X).or(b must Y)` is too strict, not
vacuous: `a` throws before `or` is reached).
