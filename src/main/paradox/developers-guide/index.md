@@@ index

* @ref:[A Scala 3.7+ primer for this codebase](scala3-primer.md)
* @ref:[Architecture: from algebra to a filtration stream](architecture.md)
* @ref:[Persistence engines: what to trust, and why](persistence-engines.md)
* @ref:[Hard-won invariants you must not break](gotchas.md)
* @ref:[Degeneracy behaviors that look like bugs but aren't](degeneracies.md)
* @ref:[Alpha complex: DQP vs Helix](alpha-complex.md)
* @ref:[Class diagrams](class-diagrams.md)

@@@

# Developer's Guide for TDA4j

TDA4j is meant to be a viable platform for algorithm development and research into topological data
analysis, not just a library you call into as a black box. This guide exists to get you from "I understand
persistent homology" to "I can confidently write, review, and extend code in this repository" — which,
if you're new to Scala 3.7+'s newest context-abstraction syntax (as most people are — it's genuinely
recent), is most of the actual gap.

## What you need to deduce before you can contribute fully

A useful way to organize this guide is around the questions a new contributor actually has to answer for
themselves before their first non-trivial change is safe to merge — because that's the order most of this
guide's content was originally discovered in, the hard way, across several sessions of work on this
codebase:

1. **What does this Scala 3 syntax even mean?** `Type is TypeClass`, `type Self: Ordering as ordering`,
   `given [bounds] => Body = value`, opaque types, extension methods. None of this is Scala-2-style or
   older-Scala-3-style code that "should" already look familiar — it's genuinely new syntax, introduced
   across recent dotty releases. Start with the @ref:[Scala 3 primer](scala3-primer.md).
2. **Where is any given piece of functionality actually defined, and what layer is it in?** The library is
   built in clear layers — algebra (`RingModule`/`Field`/`Chain`), then complex construction (streams),
   then the persistence engines that consume a stream — and knowing which layer you're in tells you what
   invariants you can rely on and which file to go read. See @ref:[Architecture](architecture.md).
3. **Which of the four persistence engines in `Homology.scala` can I actually trust for the thing I'm
   trying to do?** They are independently implemented, not layers on one shared core, and their trust
   status is not uniform — one of the four is currently non-functional. This is not something you can infer
   from the fact that all four compile and are exported from the same file. See
   @ref:[Persistence engines](persistence-engines.md).
4. **What are the traps that look fine, compile fine, and only produce a wrong *answer* on specific
   inputs?** This codebase has a real, documented history of exactly this failure mode — code that type-
   checks, runs, and passes a test suite that simply never happened to exercise the input where it breaks.
   See @ref:[Hard-won invariants](gotchas.md), and read it before you assume your own "this looks obviously
   correct" judgment is enough on a change to reduction or ordering logic.
5. **Is this surprising output a bug, or is it the library doing the mathematically correct thing on a
   degenerate input?** See @ref:[Degeneracies](degeneracies.md) before you "fix" something that isn't broken.

If you take one thing away from this guide before diving into the rest: **in this codebase, "compiles and
has a passing test suite" has not historically been a reliable signal of correctness for the persistence
and ordering machinery.** Every subtle bug found so far was caught by a specifically-constructed
discriminating test fixture, not by the existing suite noticing on its own, because the existing tests
mostly didn't happen to exercise the specific tie or label-order mismatch that triggers the bug class. When
you're about to trust your own "this seems obviously sound" reasoning about a change to reduction or
ordering code, build the cheap discriminating fixture first — see the closing section of
@ref:[Hard-won invariants](gotchas.md) for what that looks like in practice.

## Naming convention: `tda4j`/`TDA4j`, never `Tda4j`

"TDA4j" is the acronym TDA ("topological data analysis") plus the conventional `4j` suffix (`log4j`,
`slf4j`) — not an ordinary English word, so don't let a titlecasing habit turn it into `Tda4j`. Use
**`tda4j`** (all lowercase) for the package name, the executable/artifact name, the repo name, and ordinary
prose; use **`TDA4j`** (acronym preserved) for Scala identifiers that must start with a capital letter —
class/object/trait names such as `TDA4j` (the MATLAB facade), `TDA4jConf`/`TDA4jCLI` (the CLI). `Tda4j` is
never correct.

This applies to any identifier that's itself an acronym, not just the project name: `io.CSV` (comma-separated
values) and `cli.TDA4jCLI` (command-line interface) are both fully capitalized for the same reason. `io.Gudhi`/
`Dipha`/`Ripser`/`Perseus`, by contrast, are ordinary proper nouns (external projects this codebase talks to
file formats for) and stay titlecased in their own conventional spelling.

## Where the bug histories and design derivations live

This guide summarizes and cross-references, but doesn't duplicate, the detailed worklogs kept at the repo
root: `../../../../.claude/WORKLOG-naive-homology.md`, `../../../../.claude/WORKLOG-cohomology.md`, `../../../../.claude/WORKLOG-alpha-complex.md`, and `WORKLOG.md`
(the alpha-complex DQP numerical-robustness derivations). When this guide says "confirmed by a specific
counterexample" or "see the full derivation," that's where the full derivation actually lives — read those
worklogs directly if you're about to touch code in the area they cover.
