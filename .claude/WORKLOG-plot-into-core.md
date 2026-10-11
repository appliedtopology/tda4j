# WORKLOG: plots folded into the core (2026-10-11)

The project lead asked: since the plot sub-project has no outside dependency anyway, and being a separate project kept the
docs build from compiling the plotting snippets, should it stop being a sub-project? Fix that unless there is a
compelling argument against.

## Arguments weighed

For keeping it separate, none held:
- **Dependency isolation.** This was the original reason, in `DESIGN-plotting.md`: the core should not take on plotting
  dependencies. But plot has none of its own. It uses SVG strings, the JDK's `com.sun.net.httpserver` and
  `java.awt.Desktop`, all standard JDK modules, loaded only when `view()` is called.
- **A separate artifact.** `tda4j-plot` was set up to publish from 0.5.1, but 0.5.1 was never released (the last tag is
  v0.5.0), so no user depends on that artifact.
- **Enforcing "public core API only".** The build never enforced it. The plot sources share the `org.appliedtopology.tda4j`
  prefix, so `private[tda4j]` was visible to them in the separate project as well; it was a convention then and stays
  one now.
- **The fat jar.** It grows by the plot classes, which have no transitive dependencies. MATLAB loads them only if called.

What the separate project cost:
- **Docs.** The docs build compiles fences against the core's classpath, so `_docs/user-guide/plotting.md` had to be
  `scala sc:nocompile`, and plot's scaladoc was not on the site.
- **Build settings.** sbt 2 applies every bare setting in the build's `.sbt` files to every project, so `plot` had to
  replace the core's dependencies, compiler flags, docs-site options, main class and tutorial generator. There was also
  a `LocalRootProject` workaround, because two lazy vals naming each other deadlock sbt's build loader.
- **MiMa.** It needed a separate baseline (`firstPlotRelease`).

The one condition for splitting it out again: a plot feature that needs a library (PNG export, a native window) goes in a
new module, so the core never takes on the dependency.

## Change

- **Sources moved.** `git mv` `plot/src/{main,test}/scala/org/appliedtopology/tda4j/plot/*` into the core's trees. The
  package `org.appliedtopology.tda4j.plot` is unchanged; it is a leaf like `matlab` and `cli`.
- **Labs.** `tdalab-exports.py` matches only `package org.appliedtopology.tda4j` (and the `sset` pair), so the labs do
  not re-export plot. `TDAlabExportsSpec` and `UserImportsSpec` pass.
- **Build.** `build.sbt` loses the `plot` project, `aggregate`, `assembly / aggregate`, `firstPlotRelease` and the
  two-project comment. `sonatype.sbt`'s and `RELEASE.md`'s two-artifact notes are fixed.
- **Docs.** `_docs/user-guide/plotting.md` no longer describes a separate dependency. Its three fences are compiled, each
  self-contained (imports and data). The developer's guide (`plotting.md`, `architecture.md`), `CLAUDE.md`,
  `rules/plotting.md` and a status note in `DESIGN-plotting.md` are updated.
- **Compiler flags.** The core's flags are plot's plus `-language:adhocExtensions`, so the code compiled unchanged.

## Verified

- `sbt clean Test/compile` passes. `testOnly org.appliedtopology.tda4j.plot.* *TDAlabExportsSpec *UserImportsSpec` passes
  28 tests: the plot specs, the export check and the user-import check.
- Further results are in the addendum below.
