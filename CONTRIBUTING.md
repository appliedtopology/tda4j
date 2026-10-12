# Contributing to tda4j

This file is how to work on tda4j: setting up, the commands, how a change goes from an issue to a merged pull request,
and the principles a change is reviewed against. The rest of the map:

| | for |
|---|---|
| [README.md](README.md) | what tda4j is and how to depend on it |
| [the docs site](https://tda4j.appliedtopology.org/) | the user guide, the tutorials and the [developer's guide](https://tda4j.appliedtopology.org/developers-guide/index.html): the architecture, the engines, the invariants that look fine and are not |
| [CHANGELOG.md](CHANGELOG.md) | what changed in each release, and what is changing now |
| [RELEASE.md](RELEASE.md) | how to cut a release |
| [the issues](https://github.com/appliedtopology/tda4j/issues) | open questions, known limitations, unverified claims and planned work |
| [.claude/CLAUDE.md](.claude/CLAUDE.md) | the rules for AI-assisted sessions; also the densest list of the codebase's invariants |
| [CONTRIBUTORS.md](CONTRIBUTORS.md) | who works on tda4j, and the AI statement |

## Setting up

You need Git, Java 21 (any distribution, for example [Temurin](https://adoptium.net/)) and
[sbt](https://www.scala-sbt.org/) 2 (`.claude/scripts/install-sbt.sh` installs it). The build reads the Scala version
(3.9.0) and the compiler flags from `build.sbt`; `.jvmopts` gives sbt 2 GB of heap, which the full test suite needs.

Once per clone, turn on the format check before each commit:

```
git config core.hooksPath .githooks
```

It needs a `scalafmt` command: `cs install scalafmt` ([coursier](https://get-coursier.io/)), or
`.claude/scripts/install-scalafmt.sh` for the native binary (Linux x86-64 and macOS). Without one, the hook says so
and lets the commit through. It checks only the staged `.scala` and `.sbt` files, in well under a second, skips merge
commits, and never starts sbt. `git commit --no-verify` skips it once. Git never copies hooks from a repository, so this step is needed
in every clone; CI's lint job checks every pull request either way. Editors that use Metals format with the same
`.scalafmt.conf`; format on save is the easy way to never see the hook complain.

## The commands

| | |
|---|---|
| `sbt testFull` | every spec. Plain `sbt test` is incremental in sbt 2 and can run none |
| `sbt "testOnly *SimplexSpec"` | one spec (a glob) |
| `sbt scalafmtAll scalafmtSbt` | format everything |
| `sbt "scalafmtSbtCheck ; scalafmtCheck ; Test / scalafmtCheck"` | CI's lint job, exactly |
| `TDA4J_SCALA_VERSION=3.8.4 sbt doc` | the docs site, with every Scala fence compiled (run `sbt shutdown` first; see Documentation) |
| `sbt mimaReportBinaryIssues` | binary compatibility against the earlier releases of the series |
| `sbt assembly` | the fat jar the CLI and MATLAB use |
| `sbt "set Global / cacheStores := Nil ; clean ; coverage ; testFull ; coverageReport"` | statement and branch coverage, a map of what no spec exercises (the report is in `target/out/jvm/scala-3.9.0/tda4j/scoverage-report`; the weekly `coverage.yml` run sends it to [Codecov](https://app.codecov.io/gh/appliedtopology/tda4j)); then `sbt shutdown` and `sbt clean`. The disk cache must be off: sbt 2 would restore instrumented classes without the directory they record into |
| `sbt -DrunBenchmarks=true "testOnly *BenchmarkSpec"` | the benchmark specs, which CI skips |

**Never run two sbt processes against one checkout at once.** One can read the other's class files mid-write, and the
`NoClassDefFoundError` that follows looks like a real regression. For a side-by-side comparison, use a second
checkout (`git worktree add`).

## From issue to merged pull request

**Everything open is an issue.** A question that needs a decision, a known limitation, a claim nobody has checked, a
piece of planned work: each gets an issue, so it can be found, discussed and closed. Not a `TODO` comment, not a list
at the end of a worklog. When a pull request leaves something open, file the issue and link it from the pull request.
The labels:

| label | for |
|---|---|
| `bug` | a wrong answer, a crash, a broken build |
| `enhancement` | a new capability, or a limitation to lift |
| `performance` | time or memory; a fix needs a measurement (below) |
| `unverified` | something claimed or written that nobody has checked end to end |
| `question` | a decision the project lead needs to make |
| `documentation` | the user guide, the tutorials, the developer's guide, the scaladoc |
| `maintenance` | the build, CI, dependencies, tooling |

**Branch from `scala`**, the default branch (fork the repository if you cannot push to it), and open the pull request
against `scala`. The template asks what changed and why, which checks you ran, and what is not verified; delete what
does not apply. "Closes #N" in the description closes the issue when the pull request merges.

**A change a user can see adds a line to [CHANGELOG.md](CHANGELOG.md)** under Unreleased, in the section it belongs
to; a change that breaks existing code goes under Breaking, with what to write instead.

**CI must be green**: `test` (the test suite, then the fat jar built and run on a sample), `docs-build`, `mima` and
`lint`. Reproduce a failure locally before fixing it. A change to the formatter's output on files the pull request
does not otherwise touch goes in a commit of its own, "Format: ..., formatter output only".

**Binary compatibility** is checked within a release series (0.5.x). Before 1.0 a deliberate break is allowed, but it
is named: a commented `mimaBinaryIssueFilters` entry in `build.sbt` and a line under Breaking in the changelog. An
accidental break is restored, not filtered.

**A new user-visible capability** (a complex, an engine, an option) is finished when four places know about it: the
MATLAB facade (`matlab.TDA4j`), the command line (`cli.TDA4jConf`, a one-to-one mirror of the MATLAB options), the
developer's guide and the user guide.

The project lead reviews and merges. Dependabot opens weekly pull requests for the GitHub Actions and the sbt
dependencies; they go through the same CI.

## Design principles

TDA4j is meant to be a platform for experiments and research as much as a toolbox. A change is reviewed against these:

- **Representatives and coefficients.** Every persistent homology computation is generic over the coefficient `Field`
  and returns a representative chain with every bar. An optimization that drops representatives is probably not worth
  it. The default field has 17 elements, never 2: over F₂ signs and odd torsion disappear.
- **Easy, not simple.** A newcomer's first five lines are the measure of an API: one import, defaults for everything,
  error messages that say what to do. The machinery stays reachable, never mandatory. The developer's guide's
  [public API](https://tda4j.appliedtopology.org/developers-guide/public-api.html) page has the details.
- **Open by default.** Classes are public, concrete classes are `open`, and the machinery behind the convenient entry
  points (the streams behind `VietorisRips`, the engines behind `Persistence`, the pairings, union-finds and geometric
  predicates) can be called, combined and subclassed. Which class to reach for first is said in the documentation,
  not enforced by access modifiers. A restriction needs a reason, written as a comment where it is declared:
  - mutable state whose consistency the class relies on, or a constructor that trusts its arguments (offer a read-only
    view or a checked factory instead);
  - a library-wide guarantee (nothing public returns bars without representatives);
  - a generic helper name (`Level`, `LongIntMap`) that would sit in every user's `import org.appliedtopology.tda4j.*`
    and shadow their own definitions (nest it in a companion object before making it public);
  - the internals of the MATLAB and command-line facades, whose contract is their string options.

  `sealed` is for closed mathematical classifications (the four kinds of barcode endpoint); `final` is for value types
  (case classes) and for the chain storages the reductions match on. "It keeps the API small" or "users should not
  need this" are not reasons.
- **One flat package.** Everything a persistent-homology user needs is in `org.appliedtopology.tda4j`; the source
  directories organize files, not packages. The core has no top-level givens: a default instance lives in the companion
  of the data type it serves. The developer's guide's [architecture](https://tda4j.appliedtopology.org/developers-guide/architecture.html)
  page explains why.

## Tests

Tests are [specs2](https://etorreborre.github.io/specs2/) specifications, with ScalaCheck for properties. Write the
spec first, and make it discriminating: one that the plausible wrong answer fails. Betti numbers alone prove little.
Compare over F₂ and F₃, check ∂∂ = 0, check that a representative is a cycle present at its birth, compare a new
construction with a hand-built one cell for cell and value for value. Shared generators live in
`src/test/.../streams/Generators.scala`. The specs in `src/test/scala/tda4juser/` sit outside the library's package on
purpose: they check what a user's own code sees.

## Documentation

The docs site is built by scaladoc from `_docs/` (`sidebar.yml` is the navigation), with Scala 3.8.4 until Scala 3.9.1
fixes scaladoc's JavaScript; sbt only sees `TDA4J_SCALA_VERSION` when it starts a new server, so run `sbt shutdown`
before the build, and `sbt shutdown` and `sbt clean` after it.

- **Every Scala fence is compiled**, on its own, so each needs its own imports and data. A fence that only restates a
  declaration is marked `scala sc:nocompile`.
- **The tutorial pages are tests.** A page's "The whole script" fence is compiled into the test suite, and its spec
  checks every number the prose quotes. Run the code first and write the prose around what it printed.
- **Pages say what the code does and what to call.** History ("used to", "was fixed") belongs in commit messages and
  worklogs, not in the docs or the scaladoc.
- **Mermaid diagrams** render on the site; `sbt doc` does not check them.

<details>
<summary>Making a Scastie link that loads tda4j</summary>

The site's Playground link and its Scastie configuration are set in `build.sbt` (`-quick-links`,
`-scastie-configuration`). A link that opens a snippet with the library loaded is
`https://scastie.scala-lang.org/?inputs=` followed by URL-encoded JSON like this (replace `"code"`, and keep the
version current):

```json
{
  "_isWorksheetMode": true,
  "code": "import scala.language.experimental.modularity\nimport org.appliedtopology.tda4j.*\n∆(1,2,3)\n",
  "target": { "scalaVersion": "3.9.0", "tpe": "Scala3" },
  "libraries": [],
  "librariesFromList": [],
  "sbtConfigExtra": "scalacOptions ++= Seq(\"-feature\", \"-source:future\", \"-language:experimental.modularity\")\nlibraryDependencies += \"org.appliedtopology\" %% \"tda4j\" % \"0.5.0\"\n",
  "sbtPluginsConfigExtra": "",
  "isShowingInUserProfile": true
}
```

</details>

## Performance

A claim that something is faster or leaner needs an isolated measurement: one engine per JVM, warm-ups, then the
median of several runs, with the variants interleaved. The developer's guide's
[measuring performance](https://tda4j.appliedtopology.org/developers-guide/performance.html) page has the method and
the tools. An effect smaller than the spread between runs is not a result.

## Working with Claude Code

Much of tda4j is written with Claude Code, reviewed by the core team (see the AI statement in
[CONTRIBUTORS.md](CONTRIBUTORS.md)). Its sessions follow `.claude/CLAUDE.md`, the subsystem rules in `.claude/rules/`,
and the steward skill (`.claude/skills/steward/SKILL.md`) for getting a pull request to green. Investigations are
recorded in `.claude/WORKLOG-*.md`, which are never rewritten afterwards; the resulting rule goes in `CLAUDE.md` or
a rule file, and what stays open goes in an issue. Cloud sessions turn on the pre-commit format check themselves
(`.claude/settings.json`).
