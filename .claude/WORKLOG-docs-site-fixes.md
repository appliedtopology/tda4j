# Docs site fixes: landing page dark mode, ScalaDoc navigation, root title document

Session date: 2026-09-27. Triggered by three things the project lead reported after building the landing
page tonight (commit `dcc8dab`): (1) something keeps re-creating a git tag literally named `scala`, messing
with IntelliJ's git integration; (2) the new landing page looks unreadable in dark mode; (3) once ScalaDoc
opens, clicking a class in the left API nav does nothing. Priority was (2) and (3); (1) was "nice to have."

## 0. Branch state

`claude/compassionate-shannon-k78asq`'s own PR (#27) had already merged into `scala`, and `scala` had since
moved on (`dcc8dab`, the landing page commit). Per the merged-PR protocol, the branch was restarted from
`origin/scala` (`git checkout -B claude/compassionate-shannon-k78asq origin/scala`, force-with-lease push)
rather than stacked on the stale merged history.

## 1. The `scala` git tag

Found a LOCAL-ONLY tag named `scala` in this session's container clone (`git tag -l` shows it; `git
ls-remote --tags origin` does NOT — it is not on GitHub). It was created at the exact same second as every
legitimate `v*` release tag (`.git/refs/tags/*` all share one mtime), consistent with this being an
artifact of however this cloud session's container provisions/clones the repo, not anything in the
repository's own tracked files (grepped for `git tag` across every `.sbt`/`.scala`/`.yml`/`.md` file —
`release.sbt`'s `tagRelease` only ever creates `v<version>` tags; nothing creates a tag matching the branch
name). No hook, script, or setup file in this checkout does it either.

This does NOT explain the project lead's report of IntelliJ recreating it on their own, separate local
machine/checkout — different environment entirely, and I have no visibility into it. Named local vs. remote
ref collision (a branch and a tag sharing the exact name `scala`) is a known source of `git checkout
scala`-style ambiguity in some tools, so if it keeps happening locally, check for a local alias, IDE action,
or script that runs something like `git tag "$(git branch --show-current)"` believing it's creating/marking
a branch. Not resolved this session — insufficient visibility into the reporter's own machine.

## 2. `sbt laikaSite` was already broken on `scala`'s tip

Before touching anything: `sbt laikaSite` on `scala`'s HEAD (`9714462`) fails outright --
`InvalidDocuments: unresolved internal reference: ../landing-page.md` on every single page plus the
generated downloads page. Root cause: `dcc8dab` renamed the root docs page from `README.md` to
`landing-page.md`, but every *subdirectory* (`tutorials/`, `user-guide/`, `developers-guide/`) still uses
`README.md`, matching Laika's own hardcoded default for `laika.titleDocuments.inputName` ("README"). Root's
title document was never findable after the rename, breaking every `@:breadcrumb`-generated link back home
(and the theme's own `Root / "landing-page.md"` references in `homeLink`/`titleLinks`/`linkPanel`). This is
a real build failure, not a style nit — it should have failed `docs.yml` on push, so either that run failed
too (worth checking Actions) or nobody had run a truly clean `sbt laikaSite` since the rename.

## 3. A real, separate Laika 1.3.2 StackOverflowError bug

Fixing #2 by renaming root back to `README.md` (matching every subdirectory) surfaced a SECOND, unrelated,
much nastier bug: an intermittent (later found: 100%-reproducible once isolated) infinite recursion in
`laika.api.config.ObjectConfig.get`'s own fallback resolution, `java.lang.StackOverflowError`.

Initial (WRONG) hypothesis: this was about `laika.titleDocuments.inputName` overrides. Spent a long time
chasing that — tried a root-only override, a uniformly-renamed whole tree (all directories on
`landing-page.md`), removing `@:breadcrumb` entirely, and none of it reliably fixed anything. Crucially,
**even the plain, unmodified `README.md`-everywhere state (zero custom title-document config) intermittently
stack-overflowed** — sometimes 0/6 consecutive clean (`rm -rf target/docs/site`) builds succeeded, sometimes
1/6, never reliably 6/6. Confirmed genuinely infinite (not just deep) by bumping the JVM thread stack to
512m via `.jvmopts` and still overflowing.

**Actual root cause, found by bisection**: `SiteTheme.theme`'s `.landingPage(...)` call's `linkPanel`
argument used `TextLink.internal(Root / "user-guide" / "README.md", ...)`-style links — i.e. the landing
page linking, via Laika's own internal-reference resolution, to *another directory's title document*.
Setting `linkPanel = None` made the build 6/6 stable; restoring those three `TextLink.internal` targets
reproduced the crash 6/6. Neither root's own filename nor any `titleDocuments.inputName` config was ever the
actual trigger — the earlier flaky results were noise from a small sample size on a bug that, it turns out,
only fires on this one specific code path (cross-directory `TextLink.internal` from inside `.landingPage`'s
own `linkPanel`, which apparently exercises a different/buggier resolution path in Helium's landing-page
renderer than an ordinary page's own body links do).

**Fix**: swapped those three links to `TextLink.external("user-guide/", "User Guide")` (and similarly for
`developers-guide/`, `tutorials/`) — a root-relative path string that bypasses Laika's internal-reference
machinery entirely rather than fighting whatever the actual bug in it is. Verified the links still resolve
correctly (each directory's title document renders to that directory's `index.html`, so a trailing-slash
relative link works exactly like it would on GitHub Pages or any static host). Confirmed 6/6 stable across
several repeated clean-build runs after the fix, including combined with every other change in this session.

Never got to (and didn't need, once the real trigger was found) a laika-core-source-level explanation of
*why* that one link shape triggers infinite recursion. If this resurfaces (e.g. after a Laika version bump),
start from `linkPanel`'s `TextLink.internal` targets, not from title-document naming.

## 4. `landing-page.md` as a second, non-rendering file

Mid-session the project lead asked to keep `landing-page.md` as an actual file (not just rename it back to
`README.md`), containing the same content, as a second, independent file alongside `README.md` — two files,
not one driving the other.

Tried this first inside `src/docs/` (alongside `README.md`, both same content). That reintroduced a
different, milder bug: the landing page's body content — which Helium's `.landingPage(...)` normally
suppresses entirely, replacing it with the hero/teasers layout (confirmed: with only `README.md` present,
`grep`ing the rendered `index.html` for the README's own prose finds zero matches) — started rendering
**twice** underneath the teasers the moment a second markdown file existed at the root of the Laika source
tree. `sbt-laika` exposes no per-file exclude filter (checked `LaikaPlugin`'s own keys via `javap` on the
vendored jar — nothing like `Laika / excludeFilter`), so rather than fight a second Laika quirk, moved
`landing-page.md` to the **repository root** (outside `Laika / sourceDirectories`, which is scoped to
`src/docs/` only) — it's a plain, inert repo file there, not part of the generated site at all, with zero
risk of interacting with Laika/Helium again. Confirmed 0 occurrences of the duplicated body text after the
move, across multiple clean rebuilds.

## 5. Dark mode: landing page header text/icons unreadable

Confirmed visually (real headless-Chromium screenshots, both color schemes) before and after. Root cause:
Helium marks the landing page's `#header` element `class="light-inverted dark-default"`. `.light-inverted`
(defined unconditionally, no media query) sets `--component-color`/`--component-area-bg`/etc. to
`--primary-medium`/`--primary-color` — correct in light mode, where the header has a dark gradient background
against an otherwise light page and needs "inverted" (light) component colors for contrast. `.dark-default`
is presumably meant to cancel that back to plain/uninverted colors when the page itself goes dark (this
project's header keeps a dark gradient in *both* color schemes — a customization, not Helium's stock
behavior, where the header is normally already the same darkness as the rest of a dark-mode page) — but
Helium's own bundled `laika-helium.css` (1.3.2) defines **no CSS rule for `.dark-default` at all**. Result:
in dark mode, `.light-inverted`'s rule remains in full, unopposed effect, and since `--primary-medium` is
now a *dark* color (part of the dark palette), title text, subtitle, the "Documentation"/"GitHub"/"ScalaDoc"
links, and the header's GitHub icon (`.svg-shape { fill: var(--component-color) }`) all render in a dark
color on the header's own dark background — effectively invisible, matching the report exactly.

**Fix**: added a small `@media (prefers-color-scheme: dark) { #header.dark-default { ... } }` override in
`SiteTheme.theme`'s existing `.site.inlineCSS(...)` block, restoring the plain (non-inverted) dark-mode
values for the five affected custom properties, scoped specifically to the header. Confirmed by screenshot:
full contrast restored, matching the light-mode version's legibility.

## 6. ScalaDoc: clicking a class in the left nav does nothing

Confirmed with a real headless-Chromium click (Playwright): the click registers, but
`document.querySelectorAll("a")`'s own click handler in Scala 3.9.0's bundled `scaladoc` static assets
(`ux.js`) calls `e.preventDefault()` immediately, then `$.get(href, function (data) { ...AJAX page swap via
history.pushState... })` — and **no page anywhere loads jQuery** (grepped every `<script>` tag in the
generated `api/index.html`; confirmed via `page.on('pageerror', ...)`: `ReferenceError: $ is not defined` at
`ux.js:180`, thrown from the anchor's own click handler). Because `preventDefault()` already ran, the
click's normal navigation is cancelled *and* the replacement AJAX navigation never happens — the click does
nothing, exactly as reported. This is a real upstream Scala 3.9.0 scaladoc bug (its bundled JS assumes
jQuery is available and nothing supplies it), not a Laika/`laikaIncludeAPI` config issue, and would affect
any Scala 3.9.0 project's scaladoc opened normally, not just this one.

Only one call site in the whole file (`grep -n '\$\.\|\$('` on `ux.js` — a single hit). `$.get(url, cb)`'s
success-path semantics are exactly `fetch(url).then(r => r.text()).then(cb)` here (the callback only ever
receives raw HTML text, fed straight to `DOMParser`).

**Fix**: since scaladoc's static assets are a build artifact (regenerated every `sbt doc`/`packageDoc`, not
something to hand-edit in the repo), added a `laikaSite` task augmentation in `build.sbt` (`laikaSite := {
val result = laikaSite.value; ...patch target/docs/site/api/scripts/ux.js...; result }` — the standard,
supported sbt idiom for wrapping a plugin-provided task, the same mechanism `+=`/`++=` desugar to, not a
self-referential cycle) that does a literal string replace of the one `$.get(...)` call site immediately
after Laika copies the API docs into the site. Verified end-to-end with Playwright: clicking a sidebar link
now actually navigates (`window.location` changes, page content swaps in) with zero page errors.

## Verification

- `sbt laikaSite` run 6+ times consecutively from a clean `target/docs/site`, always green, after all fixes
  landed together.
- `sbt scalafmtAll` run; diff reviewed, no unexpected reformatting.
- Real headless-Chromium (Playwright, the environment's pre-installed Chromium) screenshots for light/dark
  landing page, and a real click-through test against the ScalaDoc nav, both before and after each fix.
- Did NOT run the full `sbt test` suite this session (no `src/main`/`src/test` Scala changes — everything
  touched was `project/Theme.scala`, `build.sbt`, and `src/docs/**` markdown/config) or `sbt
  mimaReportBinaryIssues` (nothing library-API-shaped changed). If a future session touches library code in
  the same PR, both should still run before merge per the usual convention.
- Did NOT get a real GitHub Pages deploy to confirm against (no push to `scala` from this session by
  default) — everything above is from a local `sbt laikaSite` build served over `python3 -m http.server` and
  inspected with a real headless browser, not merely by reading the generated HTML/CSS.

## Correction (same session, after the project lead pushed back)

**Section 4 above was wrong.** The project lead pointed out that Laika's own user guide documents
`landing-page.<suffix>` (i.e. `landing-page.md`) as the intended, built-in mechanism for adding content
below `.landingPage(...)`'s templated hero/teasers — not something to route around. Confirmed directly
against Laika's docs (`03-theme-settings.html`): *"Additionally or alternatively you can also add a regular
markup document called `landing-page.<suffix>` to one of your input directories and its content will be
inserted at the bottom of this page."*

Re-tested with `landing-page.md` restored to `src/docs/` (its documented location) and looked at the actual
rendered `<main>` block line-by-line instead of just grepping a match count. The earlier "duplication" was
real, but the diagnosis was wrong: with `landing-page.md` present, Helium renders **both** the title
document's (`README.md`'s) own body **and** `landing-page.md`'s body, back to back, in that order — matching
"additionally *or* alternatively" literally (either source works alone; both together both render). With
`landing-page.md` absent, the title document's own body is suppressed entirely (confirmed earlier: 0
occurrences) — so the suppression is conditional on `landing-page.md`'s absence, not unconditional as
originally assumed. Since I'd made `README.md` and `landing-page.md` byte-identical, both bodies rendering
looked like one page duplicated, and I misattributed it to a same-directory conflict rather than to genuinely
duplicate content across the two documents Laika was correctly, separately rendering.

**Fix**: `landing-page.md` stays in `src/docs/`, holding the full descriptive prose. `README.md` was
shrunk to a single `# @:tda4j` heading trial, then to **fully empty** (0 bytes) — confirmed safe: the site's
`<title>` tag, the landing page's own title/subtitle (from `SiteTheme.theme`'s explicit `title`/`subtitle`
args), and every other page's breadcrumb "Home" link (driven by `homeLink`'s own config, not by `README.md`'s
content) are all unaffected by an empty title document. Verified the rendered `<main>` now contains the
heading and all four paragraphs exactly once, sourced from `landing-page.md` alone.

**Corrected rule**: root's title document (`README.md`) still must exist, named `README.md`, matching every
subdirectory and Laika's own default (unrelated `StackOverflowError` bug from Section 3, still real, still
avoided by keeping this naming) — but it should be kept minimal-to-empty when `.landingPage(...)` is in use
and `landing-page.md` supplies the real content, specifically to avoid double-rendering identical prose.
`landing-page.md` belongs in `src/docs/` (its documented location), not exiled to the repo root as this
worklog originally (wrongly) concluded.
