# WORKLOG: Paradox → Laika site migration

Started 2026-09-23, continuing a partial conversion the project lead began earlier tonight (renamed
`src/main/paradox` → `src/main/docs`, added `LaikaPlugin` + a `theme` val to `build.sbt`, added `laika-sbt` to
`project/plugins.sbt`). Working tree was dirty at session start (not clean, despite an earlier snapshot claiming
otherwise) with those changes uncommitted. Not committing tonight per standing instruction (project lead commits
their own work).

Requirements from the project lead: Laika replaces Paradox entirely; linked+built scaladoc; GitHub repo link;
GitHub Pages page as the site root; light/dark theme, off-white-bg/dark-fg (light) and dark-bg/off-white-fg (dark)
instead of Laika's default blues; converted directive syntax; breadcrumbs somewhere in nav. Told to ask now if
needed (going to bed) but prioritize progress — proceeding on judgment calls below rather than blocking, per
advisor's steer (auto mode + user says prioritize progress).

## Judgment calls made without asking (would need too long to get an answer)

1. **`src/main/docs` → `src/docs`** (sibling of `src/main`/`src/test`, not nested under `main`). This is laika-sbt's
   own default source directory (`sourceDirectory.value / "docs"` resolves to `src/docs`), so it's also less config
   to carry. The project lead floated this themselves ("maybe it's worth it").
2. **Full removal of the Paradox toolchain** (plugins.sbt: `sbt-site-paradox`, `sbt-paradox-material-theme`;
   build.sbt: `SiteScaladocPlugin`/`ParadoxSitePlugin`/`ParadoxMaterialThemePlugin` and all `paradox*` settings).
   "Shifting our website engine to Laika" reads as full replacement, not parallel run.
3. Color palette specifics (exact hex values for "off-white/dark-fg" and "dark bg/off-white fg") — picked values,
   noted below; easy to retune, not load-bearing on anything else.

## Findings log

**Ground truth at session start**: `build.sbt` did not compile (`laikaConfig := object { ... }` is not valid Scala
syntax — an anonymous `object` literal doesn't exist). Whatever the project lead saw as a "preview" earlier
tonight was not this custom theme; it was either an older successful build or Laika's stock Helium defaults.
`target/docs/site/api` (scaladoc only, no site pages) confirms a prior `laikaSite` run got as far as `Compile /
doc` and then failed on the markup. `git status` was genuinely dirty (renames + build.sbt/plugins.sbt edits already
in progress) despite an earlier snapshot claiming clean — trusted the live `git status`, not the snapshot.

**`.html.darkMode` → `.site.darkMode`**: Helium's namespaces are `.all`/`.site`/`.epub`/`.pdf` — there is no `.html`.
Confirmed by decompiling the actual 1.3.2 jars (`javap` against `~/Library/Caches/Coursier/.../org/typelevel/`),
not by trusting doc-summary text — WebFetch's own AI-summarized answers turned out unreliable enough on Helium's
exact API (see below) that jar introspection plus GitHub's raw source for the exact tag (`v1.3.2`) was the more
trustworthy source throughout this session.

**Markdown.GitHubFlavor is required, not optional**: Laika's base Markdown parser (what laika-sbt uses by default)
does not include GFM fenced code blocks. Without `laikaExtensions += Markdown.GitHubFlavor`, a ` ```scala ` fence
is NOT recognized as code — its contents get parsed as ordinary prose, so any `[...]` inside an example (a type
param like `[CoefficientT: Field]`, a Java array type `double[][]`, a bracketed comment `[dimension, birth,
death]`) is treated as a dangling Markdown reference link and fails the build with `unresolved link id reference`
or `too many anonymous references`. This hit essentially every code-heavy page. Fix is one line; took real
digging via a controlled `laikaSite` failure to isolate (confirmed by checking the exact source location error
pointed at was inside a real ` ```scala ` fence, not stray text).

**`laika.config.SyntaxHighlighting` is also opt-in**, separately from GitHubFlavor — without it every code block
renders as plain unstyled text (no `<span class="keyword">` etc.). Added alongside GitHubFlavor.

**Helium ships no breadcrumb by default** — decompiled `default.template.html` out of the laika-io 1.3.2 jar and
confirmed it never includes a breadcrumb partial. `@:breadcrumb` is a real standard directive (confirmed via
`BreadcrumbDirectives` class in laika-core), but you have to place it yourself via a template override. Copied
Helium's own `default.template.html` to `src/docs/default.template.html` (global override) and added `@:breadcrumb`
right above `${cursor.currentDocument.content}`. Verified in rendered HTML: real link trail (e.g. "TDA4j >
Developer's Guide for TDA4j > A Scala 3.7+ primer for this codebase").

**`@@@ index` was defining reading order, not just contents** — the developers-guide bullet list is a deliberately
curated order (primer → architecture → persistence-engines → gotchas → degeneracies → alpha-complex →
class-diagrams), NOT alphabetical. Stripping the Paradox wrapper down to a plain bullet list would have kept the
*visible* list correct but silently made Laika's own auto-generated left-nav sidebar fall back to alphabetical
directory order, disagreeing with the page's own prose. Fixed with `laika.navigationOrder` in `directory.conf` at
both `src/docs/` (tutorials, user-guide, developers-guide — also non-alphabetical) and `src/docs/developers-guide/`
(the full curated order). Advisor caught this gap before it shipped.

**Scala 3's own `scaladoc` output shape doesn't match what `@:api`/`ApiLinks` expects.** Scaladoc for a *package*
(not a class) renders as a flat file `org/appliedtopology/tda4j.html`, not a directory with an `index.html`/summary
page inside (`ApiLinks`'s `packageSummary` config assumes the latter, a Javadoc/Scala-2-scaladoc convention).
Rather than fight that mismatch for a single link, used a plain relative link to the real, verified file path
(`api/org/appliedtopology/tda4j.html`) instead of the `@:api` directive. Documenting this as a deliberate choice,
not an oversight — `@:api` may still be worth it if per-class deep links are ever needed.

**Wrote a custom Laika directive, `@:snip`, to replace `@@snip`** (`project/SnipDirective.scala`, a `DirectiveRegistry`
registered via `laikaExtensions`) — reads a real source file at build time and extracts the region between two
`// #tag-name` marker lines (same convention the codebase's `@@snip` usages already had in
`SimplexOrderedCell.scala`/`APISpec.scala`). This preserves the property the user-guide's own prose promises
("compiled and exercised directly by the test suite") rather than freezing a copy-pasted, driftable snippet.
Two build-level gotchas fixed along the way: `project/*.scala` compiles under sbt's own Scala 2.12, so no
`.minOption` (2.13+/Scala-3-only); and `cats`'s `.mapN` on a tuple of two `AttributePart[String]` needs both
`.widen`ed to the common `DirectivePart[String]` supertype first, or cats' implicit search resolves `F` as the
narrower `AttributePart` type constructor (for which Laika only defines the Functor/Semigroupal instance on the
`DirectivePart` companion, not on `AttributePart`) and fails.

**Mermaid diagrams need client-side JS, not a "transformer"** (asked about mid-session): Laika has no built-in
mermaid support; a ` ```mermaid ` fence becomes a plain `<pre class="mermaid">raw text</pre>` (no `<code>` wrapper,
since no highlighter recognizes "mermaid" as a language — it just passes through untouched). Wired up
`.site.externalJS` (mermaid.js from jsdelivr) + `.site.internalJS` (a small init script at
`src/docs/assets/mermaid-init.js`) that re-parents each `pre.mermaid`'s text into a `<div class="mermaid">` (so
code-block CSS doesn't clash with the rendered diagram) and calls `mermaid.run()`. Verified structurally (script
tags land in `<head>`, target elements exist with the right class) but **not yet verified in an actual browser** —
no headless browser available in this session; the project lead should sanity-check this visually.

**`sbt scalafmtAll` reformatted an unrelated file** (`LandmarkSelectionResult.scala`, a pre-existing docstring
line-wrap drift, not something this session touched otherwise) as a side effect of formatting the new
`project/SnipDirective.scala`. Manually reverted that one file's content back to its original (still
non-scalafmt-compliant) state rather than ship an unrelated cosmetic diff — confirmed via a standalone
`scalafmtCheck` afterward that this file's non-compliance is pre-existing, not something introduced this session,
so left it alone rather than "fixing" scope I wasn't asked to touch.

## What's done and verified (via actual `sbt laikaSite` runs + inspecting `target/docs/site` output)

- `src/main/docs` → `src/docs` (matches laika-sbt's own default source dir, sibling of `src/main`/`src/test`).
- Paradox toolchain fully removed: `plugins.sbt` (`sbt-site-paradox`, `sbt-paradox-material-theme`), `build.sbt`
  (`SiteScaladocPlugin`/`ParadoxSitePlugin`/`ParadoxMaterialThemePlugin`, all `paradox*` settings).
- `build.sbt` compiles; `sbt laikaSite` builds cleanly (11 markup docs, scaladoc copied to `/api`).
- All Paradox directive syntax converted: `@ref:[t](p)` → `[t](p)`; `@scaladoc[t](fqcn)` → real relative link;
  `@@@ index ... @@@` → plain bullet list + `directory.conf` navigationOrder; `@@@ note ... @@@` → `@:callout(info)
  ... @:@`; `@@snip [...](...) { #tag }` → custom `@:snip(path, tag)` directive.
- GitHub repo icon link in top nav (`IconLink.external(...HeliumIcon.github)`).
- Site base URL set to the GitHub Pages URL; `gitHubPagesSiteDir` now points at Laika's real output
  (`target/docs/site`, was wrongly `target/site` before — would have deployed nothing).
- Color palette: off-white/dark-charcoal (light), dark-charcoal/off-white (dark), teal/red kept only as accent
  colors — confirmed exact hex values present in the generated CSS.
- Breadcrumbs render with real links (template override).
- Left-nav order matches the curated reading order, not alphabetical (`directory.conf`).
- Footer copyright line restored (`MIT License © Mikael Vejdemo-Johansson, Daniel Hope`), via `.site.footer(...)`.
- `.github/workflows/docs.yml`: `sbt makeSite` → `sbt laikaSite`; dropped the now-unused `ts-graphviz/setup-graphviz`
  step (was for the already-removed `sbt-paradox-diagrams`; nothing else in the build uses Graphviz — confirmed by
  grep).
- `CLAUDE.md` stale references fixed: `sbt makeSite`/`src/main/paradox` command line, the docs.yml one-liner, and
  a previously-mangled edit that had collapsed surfaces (3) and (4) of the "finalizing a capability" checklist into
  an identical, wrong path (both now correctly point at `src/docs/developers-guide/` and
  `src/docs/user-guide/README.md` respectively).
- `sbt scalafmtCheck scalafmtSbtCheck` clean (after `scalafmtSbt` on the two touched `.sbt` files).
- Scaladoc generation itself succeeds (4 pre-existing, unrelated scaladoc `@link` cross-reference warnings in
  `Barcode.scala`/`Gudhi.scala`/`FiniteMetricSpace.scala` — not introduced this session, not touched).

## Known gaps / not done (honest list, not swept under anything)

- **No manual light/dark toggle** — only `@media (prefers-color-scheme: dark)`. Helium 1.3.2's icon set
  (`HeliumIcon`) has no sun/moon icon and no toggle-button config was found; the user's ask said "preferably
  switchable," so this is a real, deliberate scope cut, not an oversight. Building a manual toggle (small JS +
  `localStorage` + a `data-theme` override, same shape as the mermaid script) is a reasonable follow-up if wanted.
- **Mermaid rendering unverified in an actual browser** — Helium's native support is confirmed present and wired
  correctly (right script, right target elements), but nobody has looked at the actual rendered diagrams on screen.
- **EPUB output not enabled** (`laikaIncludeEPUB` was never set, in the original Paradox setup either) — not
  attempted, out of scope. PDF (`laikaIncludePDF`) IS enabled and builds successfully (see corrections §9).
- **`tutorials/README.md`'s language-tab placeholder** was cleaned up to plain prose rather than wired to Laika's
  real equivalent (`@:select`/`@:choice` + a `laika.selections` config block) — there was no real content to
  switch between (both branches said "Some things go here"), so wiring the full mechanism up now would have been
  speculative. Left a pointer in the page's own text for whoever ports the actual JavaPlex tutorials.
- **Full `sbt test` was not re-run** — no `src/main/scala`/`src/test/scala` files were touched (only docs, build
  config, and CI config), and `Compile / doc` succeeding during `laikaSite` already proves the main sources compile.
- **`LandmarkSelectionResult.scala`'s pre-existing scalafmt non-compliance** (line-wrap width drift, unrelated to
  this session) was left as found — not this session's scope to fix.

## Corrections (found by a second advisor pass, after I'd first called the session "done")

1. **The prompt-injection flag was wrong.** Earlier I flagged what I believed was a prompt-injection attempt inside
   two `WebFetch`/`WebSearch` tool results (a `<system-reminder>`-formatted block carrying a `Claude-Session` URL
   and a `SendUserFile` mention). These tags are inserted by the harness adjacent to any tool result and "bear no
   direct relation to the specific tool result... in which they appear" (this repo's own recent commits already
   carry `Claude-Session:` trailers from prior sessions) — ordinary harness behavior, not something embedded in the
   fetched pages. Retracted.

2. **Mermaid was already built into Helium — my externalJS/internalJS wiring was redundant and briefly a real
   double-render risk.** Helium ships `laika.helium.internal.generate.MermaidInitializer` and a dedicated `pre.mermaid`
   CSS rule; it auto-detects a mermaid code block and injects its own ES-module `import mermaid from '...esm.min.mjs'`
   + `mermaid.initialize(...)` script, no config needed — confirmed by decompiling the jar and by comparing the
   ` ```mermaid ` block's actual rendering (`<pre class="mermaid">`, no `<code>` wrapper — a deliberate special case)
   against the ` ```matlab ` block's rendering (`<pre class="..."><code class="matlab">`, the real "unrecognized
   language" fallback). My first pass added a second mermaid.js load plus a script that would have re-parsed
   Helium's already-rendered SVG as if it were still raw diagram text. Removed `.site.externalJS`/`.site.internalJS`
   and `src/docs/assets/mermaid-init.js` entirely; a bare ` ```mermaid ` fence is enough, nothing else to configure.
   Corrected the build.sbt comment and the answer I'd given the project lead mid-session about this.
3. **`@:snip` used `BlockDirectives.create`, which fails open** — a missing file or a renamed/typo'd tag rendered
   an error string as a code block on the page while `laikaSite` still exited 0, contradicting the user guide's own
   "compiled and exercised" claim. Switched to `BlockDirectives.eval` (returns `Either[String, Block]`) so the same
   failure now fails `laikaSite` with a clear message. Verified both directions: pointed a call at a bogus tag,
   confirmed the build failed with `snip: tag 'given-example-BOGUS' not found twice in <path>`, then reverted.
4. **The last few "clean" builds in the previous pass were laika-sbt cache hits**, not real re-renders — nothing
   under `src/docs` had changed, so `laikaSite` skipped the render step (no `Rendering N html documents` line in
   the log). A change made only in `build.sbt` (theme colors, extensions, etc.) may not show up in `target/docs/site`
   without `sbt clean laikaSite` first. All checks in this corrections section were re-run against a genuine
   `sbt clean laikaSite` output to be sure.
5. **Ran a real link-resolution sweep** (a small Python script over every `href`/`src` in every rendered `.html`,
   resolving relative paths against the file's own directory) rather than trusting "the build didn't complain": 30,748
   local links checked, 14 broken — all 14 inside `api/` (Scala 3's own `scaladoc` output, not anything Laika or
   this session authored), all the same shape: scaladoc linking to a stdlib member (`scala.math.Ordering$OrderingOps`,
   `scala.collection.Iterator$GroupedIterator`) it doesn't generate a page for. Pre-existing scaladoc limitation,
   out of scope for this migration. Every authored page (README, developers-guide, user-guide, tutorials) has zero
   broken links.
6. **`sbt scalafmtAll` reformatted the same unrelated pre-existing-drift file a second time** (it's a
   repo-wide command, not scoped to touched files) — reverted `LandmarkSelectionResult.scala` again by hand.
   Going forward in a session like this, prefer formatting just the new/touched file(s) directly rather than a
   blanket `scalafmtAll`, to avoid re-discovering the same unrelated drift.

## Third advisor pass — two more real issues, one over-reach reverted

7. **The converted `@@@ index` blocks were rendering as a VISIBLE duplicate bullet list on the page**, sitting
   between the breadcrumb and the `<h1>`, on both the root page and the developers-guide landing page. Paradox's
   `@@@ index ... @@@` was navigation-only and never rendered in the page body; a plain bullet list, unlike the
   wrapped version, IS body content. Since `directory.conf`'s `navigationOrder` (fixed earlier) already drives the
   real left-sidebar nav with the identical curated order, the lists were pure redundant clutter — deleted both
   (`src/docs/README.md`, `src/docs/developers-guide/README.md`). Root README.md's later prose ScalaDoc mention
   (an actual explanatory sentence, not a nav list) was left alone. To keep ScalaDoc reachable from every page (not
   just the root body text), added a ScalaDoc icon (`HeliumIcon.api`, linking to `Root / "api" / "index.html"`) to
   the top nav next to the GitHub icon — confirmed present and resolving on both root and non-root pages.
   - Tried also fixing a genuinely-preexisting-but-unrelated cosmetic issue while in there: Helium's default
     `homeLink` (the top-nav home icon when unconfigured) renders as a dead `href="#"`. Setting it explicitly
     (`IconLink.internal(Root / "index.html", ...)`, then `IconLink.internal(Root, ...)`) broke the build for
     every page below the root with `unresolved internal reference: ../index.html` / `../../` — Laika's per-page
     relative-path translation for a theme-global icon pointing at the virtual root document doesn't work the way
     `Root / "api" / "index.html"` (a path outside Laika's own managed tree, so unvalidated) does. Reverted rather
     than keep debugging a nice-to-have I'd added on my own initiative, not something asked for. The dead `href="#"`
     home icon is Helium's own out-of-the-box behavior, not a regression from this migration — left as-is.
8. **`@:snip` output wasn't syntax-highlighted** — `laika.config.SyntaxHighlighting`'s rewrite pass only sees
   `CodeBlock`s that already exist in the tree when it runs; a `CodeBlock` built inside a directive's own `eval`
   is constructed too late in the pipeline to be caught by that pass. Fixed by having the directive tokenize its
   own extracted text: `ScalaSyntax`/`JavaSyntax` (`laika.parse.code.languages`) both expose a public
   `rootParser: Parser[Seq[CodeSpan]]`; running `.parse(text).toOption` and falling back to `Seq(Text(text))` for
   any other language reproduces exactly what the built-in highlighter does for a fenced block. Verified: `@:snip`
   output now has the same `<span class="keyword">`/`<span class="type-name">` markup as a hand-written fence
   (the outer `<code>` tag still says `class="nohighlight"` rather than `class="scala"` — a cosmetic label
   mismatch with no visible effect, since styling is per-span; not chased further).
9. **`laikaIncludePDF := true` restored** — it was the project lead's own existing setting, not something outside
   this session's scope to drop; the earlier worklog line calling it "wasn't asked for" was itself wrong. Rebuilt
   clean: PDF now generates successfully (`target/docs/site/downloads/TDA4j-0.1.pdf`, ~310KB). Mermaid diagrams
   will render as raw, un-rendered text in the PDF (no JS there) — not fixed, just noted; nobody asked for
   PDF-specific mermaid handling and Laika has no story for it.
10. **Verified there really is no dark/light toggle** rather than re-asserting the earlier (once-wrong) claim
    outright: decompiled `laika/helium/js/theme.js` from the 1.3.2 jar — it handles the mobile nav-icon toggle,
    dropdown/version menus, and the `@:select` tab feature, but has zero dark-mode-related code. OS-preference-only
    stands, now checked rather than assumed.
