# A self-contained REPL jar for talks (2026-10-15, branch `claude/release-prep-bugs-rkjpln`)

Ask: the project lead gives a tutorial (2026-10-16 UTC date in the deck's path); participants should not have to install
Scala, Scastie cannot show the plots (the viewer is a local server) and the Scaladex/Maven problem breaks its dependency
lookup. Result: `demo/`, a jar that opens a Scala 3 REPL with the library and the imports already there.

## Shape, and why

- **A separate sbt build** (`demo/`, own `project/`), not a subproject of the root and not a dependency of the library:
  the root auto-aggregates subprojects, so `assembly`, `publishSigned`, release and coverage would all have picked up the
  compiler. The mini-build depends on a *version* of the library: `sbt publishLocal` of the checkout for development,
  `TDA4J_VERSION=0.6.1 sbt assembly` from Maven Central once released.
- **Built from this branch, not 0.6.0.** The cask/Undertow viewer (`be3f71a7`) is after the `v0.6.0` tag, and 0.6.0's
  `view()` is the JDK-server one that crashed for the project lead. The deck's `:dep ...:0.6.0` line therefore loads the
  crashing viewer; a jar from this branch, or a 0.6.1, does not.
- **`DemoRepl`** starts `dotty.tools.repl.ReplDriver(Array("-usejavacp", "-Xrepl-print-height:20") ++ args, Console.out,
  None, predef)`. The fourth constructor parameter, `extraPredef`, is what runs the imports; found by `javap`, not
  documented. The REPL compiles against `java.class.path`, which in a fat jar is the jar itself.

## Findings

- **Assembly merge**: one conflict, `scala/collection/internal/pprint/CollectionName` (pprint 0.9.0, which cask brings,
  against the REPL's vendored copy): `MergeStrategy.first`. `META-INF/services` is concatenated; jline's native
  libraries (Linux, Mac, Windows) and the XNIO/jline provider files are present in the jar.
- **Java floor**: class-file major 61 = Java 17 (the Scala compiler's classes and `tda4j`'s own); run-tested on 21 only.
  74 MB.
- **Piped stdin quirk** (testing only): this REPL (the stock `dotty.tools.repl.Main` in the same jar too) evaluates the
  first piped line and then ends. Drive scripted runs with `:load FILE` (compiles the file as one unit: no `:quit` inside).
- **Rendering**: `Rendering.replStringOf` receives the runtime value only and calls a vendored `pprint` reflectively in
  the user's class loader. A case class with an overridden `toString` is printed with it (`ProductToStringProbe`); other
  case classes are printed field by field, which is what flooded the screen after `Plot...view()`. The `-Vrepl-max-print-*`
  flags are deprecated no-ops in 3.9.0; `-Xrepl-print-height` works (50-point array: 50 lines -> 20).
- **Fix in the library** (`2e1ce1e0`): `Figure`, `Scene`, `Board` have a one-line `toString`.
- **Logging**: the first `view()` printed eight `INFO:` lines (XNIO, JBoss Threads, Undertow, through
  `java.util.logging`). Levels set from `main` were lost until the field holding the loggers was made public: JUL keeps
  loggers weakly and the compiler drops a private field nobody reads. Set from REPL code the same call worked, which is
  how it was found.
- **Viewer, against the finished jar** (curl): `/`, `/list`, `/plot/1..3` answer 200 with the titles, a SVG page and the
  event stream; `/plot/99` 404; `:quit` exits with the server up. The source binds `127.0.0.1` only; the socket itself
  was not observed (`ss` is not installed here).
- **`.show`**: `cats.syntax.show.*` is in the predef; `chain.show` gives `Fp(1) ⊠ ∆(0,1) + ...` where the REPL's own echo
  gives `1⊠TreeSet(0, 1) + ...`. The deck's own lines (`longbars.bars(1).representative`, `Persistence(points)`,
  `significant(0.10)`, `diagram.longest(2)`, the three `view()` calls) ran without errors.

## Show-first echo: possible, not built

The REPL cannot prefer `Show` by itself: it never sees the static type, and a `Simplex` is a bare `TreeSet` at runtime.
Hooks that exist: `ReplDriver.rendering` is a `protected var`, `resetToInitial` is protected, `Rendering.renderVal(d:
Denotation)` is public and receives the static type. A subclass living in package `dotty.tools.repl` (it needs
`private[repl]` members) could replace the renderer and, per echoed value, compile a second snippet that summons
`Show[T]` and prints it. Costs: a second compile per echo, dependence on 3.9's REPL internals, fallback when there is no
instance. Not attempted; `println(x.show)` does it explicitly today.

## Not verified / decisions for the project lead

- macOS, Windows (terminal, browser opening, firewall prompt), a Java 17 runtime, Windows consoles that are not UTF-8
  (`?` for `∞ ⊠ ∆` here under a POSIX locale; `-Dstdout.encoding=UTF-8` prints them).
- **cask in the core contradicts `rules/plotting.md`'s module rule** ("a feature needing a library goes in a new module")
  and 0.6.0's changelog ("no dependencies of its own"). Keep it in the core, or split the viewer into a module? The
  rules file now states the deviation; the decision is the project lead's. `Viewer` is now `extends cask.MainRoutes`, which
  also puts cask types in a public object.
- **MiMa** (`sbt mimaReportBinaryIssues`) exits 0 with the viewer rewrite.
- Publishing the jar (a GitHub release asset or the docs site) is not done; the deck is the project lead's.
