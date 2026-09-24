# Developer's Guide for TDA4j

TDA4j is meant to be a viable platform for algorithm development and research into topological data
analysis, not just a library you call into as a black box. This guide gets you from "I understand
persistent homology" to "I can confidently write, review, and extend code in this repository" — which, if
Scala 3.7+'s newest context-abstraction syntax is new to you (as it is for most people — it's genuinely
recent), is most of the actual gap.

## How to use this guide

1. **The Scala 3 syntax.** `Type is TypeClass`, `type Self: Ordering as ordering`, `given [bounds] => Body
   = value`, opaque types, extension methods. None of this is older-Scala-3-style code that "should" already
   look familiar. Start with the [Scala 3 primer](scala3-primer.md).
2. **Where a given piece of functionality lives, and what layer it's in.** The library is built in clear
   layers — algebra, then complex construction (streams), then the persistence engines that consume a
   stream — and each has its own subpackage. See [Architecture](architecture.md).
3. **Which persistence algorithm, and which of its implementations, to build on.** `homology` ships four
   independently-implemented algorithms across five concrete classes; they are not layers on one shared
   core, and their intended roles (production vs. reference oracle) are not something you can infer just
   because all five compile and share a file. See [Persistence engines](persistence-engines.md).
4. **What traps look fine, compile fine, and only produce a wrong answer on specific inputs.** Ordering and
   reduction code in this codebase has a real history of exactly this failure mode. See
   [Hard-won invariants](gotchas.md) before you trust your own "this looks obviously correct" judgment
   on a change to reduction or ordering logic.
5. **Whether a surprising result is a bug or the mathematically correct answer on a degenerate input.** See
   [Degeneracies](degeneracies.md) before "fixing" something that isn't broken.

## Naming convention: `tda4j`/`TDA4j`, never `Tda4j`

"TDA4j" is the acronym TDA ("topological data analysis") plus the conventional `4j` suffix (`log4j`,
`slf4j`) — not an ordinary English word, so don't let a titlecasing habit turn it into `Tda4j`. Use
**`tda4j`** (all lowercase) for the package name, the executable/artifact name, the repo name, and ordinary
prose; use **`TDA4j`** (acronym preserved) for Scala identifiers that must start with a capital letter —
class/object/trait names such as `TDA4j` (the MATLAB/Java facade), `TDA4jConf`/`TDA4jCLI` (the CLI).
`Tda4j` is never correct.

The same rule applies to any identifier that's itself an acronym: `io.CSV` (comma-separated values) and
`cli.TDA4jCLI` (command-line interface) are both fully capitalized for the same reason. `io.Gudhi`/`Dipha`/
`Ripser`/`Perseus` are ordinary proper nouns (the external projects those file formats belong to) and stay
titlecased in their own conventional spelling instead.
