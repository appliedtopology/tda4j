# Docs review, 2026-10-20

Scope: README, `_docs/`, sidebar, repo meta files; no code. Audience: human readers.

## Step 1: gaps found and filed

- #156 no installation page (requirements, build tools, jar download); no GitHub release exists yet, so the README's
  "download from the releases" points nowhere until the first release.
- #157 AI-reader text and development history in `_docs/developers-guide/*`.
- #158 no troubleshooting/FAQ page, no glossary.
- #159 no migration guide for JavaPlex/Ripser/GUDHI users.
- #160 no CITATION.cff, SECURITY.md, CODE_OF_CONDUCT.md; stray scripts in the repo root (not moved: referenced from
  `.claude/` files).
- #161 README lacks Java/MATLAB first examples.

Not checked (budget): scaladoc coverage of public members, tutorial correctness (the docs build runs the fences).

## Fix order

1. #156 + #161 installation page and README. 2. #157 cleanup. 3. #158, #160. 4. #159.

## Step 2: first fixes (same branch, one draft PR)

- #156/#161: `user-guide/installation.md`, README rewrite with Java/MATLAB/CLI/JS, jar placeholders `VERSION` in the
  tutorials and guides (they named `0.5.0-SNAPSHOT` and `sbt assembly`).
- #157 partly: developer's-guide index, primer, engines 3 and 5, gotchas process section, stream-naming pointer.
  Grep for `.claude|LLM|used to|no longer|WORKLOG` over `_docs` is now clean apart from legitimate uses (the
  `tdalab-exports.py` and JFR script paths in contributor-facing pages; "no longer a key" in an algorithm description).
- #158: troubleshooting and glossary pages (claims taken from `.claude/CLAUDE.md` rules, not re-run).
- #160: CITATION.cff, SECURITY.md. No CODE_OF_CONDUCT.md: it needs a contact the maintainers must choose.
- #162 (content pass) opened: dev guide partly done; user guide, tutorials, plotting (45 KB) still to read end to end.
- Unverified: the installation page's `publishLocal` and Maven Central coordinates (no release exists yet); the docs
  build (`sbt doc`) was not run in this session.

## Step 3: read-through (#162)

Read in full: user-guide `topological-spaces/index`, `vietoris-rips`, `homology-computation/*`, the head of `plotting`,
a tutorial opening; developer's guide passages flagged by a hedge-word grep. The user guide and tutorials were already
concise and in human register: no edits needed in what was read. Developer's guide: filler ("genuinely", "actually")
and one history sentence removed. Not read end to end (budget): remaining user-guide pages, 11 other tutorials, the
bulk of `plotting.md`, `architecture.md`, `persistence-engines.md`, `class-diagrams.md`. Those are the next step if
wanted; the pattern sweeps found nothing there.

## Step 4: full read-through of everything outside the developer's guide (#162)

Read in full (prose; code fences skimmed): user guide (quickstart, cli, matlab, javascript, input-output,
boundary-matrix, circular-coordinates, vectorization, all complexes pages, engines pages, simplicial sets, plotting),
all 12 tutorials' prose. Result, honestly: the register is already human and concise; edits were small and specific.
Fixed: unclosed parenthesis and broken sentence in fast-cubical.md; wrong link target (witness section pointed to the
sparse Rips page); Scala.js section of javascript.md (placeholder version, sbt 1/Mill clutter); a paper-bug note in
sparse-vietoris-rips.md reduced to the rule the code follows; CLI/plotting jar names; test-class names
(`MatlabTabsSpec`, `FindALoopSpec`, `TutorialData`) removed from tutorial prose, one sentence in the tutorials index
says MATLAB tabs are checked via Java; the 25-line data dump in all-ways-to-call.md replaced by a link; plotting.md
gets a pointer separating the common sections from the reference. Link check over `_docs` (relative `.md` links and
anchors): no broken ones remain. Not changed on purpose: the long reference sections of plotting.md (structure fine,
length is the content); developer's guide beyond the earlier steps (human-edited per Mikael).
Not done: docs build (CI `docs-build` runs it on the PR).

## Step 5: Mikael's follow-up (2026-10-21)

Asked for: a careful pass over the developer's guide using jmatuszewski's edits as the guide; complete MATLAB, CLI and
plotting references that need no ScalaDoc; migration guides.

- Jordan Matuszewski's one commit (82743b6, 2026-10-02, "First pass at developer guide documentation", -722/+259 lines
  over 8 pages) is the editorial standard. What he removed: provenance narratives ("no paper access", "confirmed
  empirically", counterexample stories), pointers to `.claude/` DESIGN/WORKLOG files, per-class cross-validation
  asides, repeated explanations; he broke long paragraphs into short lists and kept the invariants, signatures and
  warnings. Since then Claude added about 12 KB to architecture.md and similar to the engines page; the additions that
  matched his standard (platform seams, givens rule, chain storages) were kept and tightened, the rest cut.
- References, written from the source rather than from the old pages: `cli.md` from `TDA4jConf` (every flag, default,
  value; exit status), `matlab.md` from `TDA4j.scala` and the result classes (every entry point, option key set,
  every result method), `plotting.md` MATLAB/Java section from `Figure`/`Scene` (every string/number method).
- Migration guide `user-guide/coming-from-other-libraries.md`. The JavaPlex column was checked against `Plex4.java` in
  appliedtopology/javaplex; the Ripser and GUDHI columns are from memory of their public interfaces and say so.
  Unverified: the claim that GUDHI and JavaPlex omit zero-length bars (left as "check your version").
- Unverified in general: the docs build (CI runs it); the CLI reference's exit-status line for argument parse errors
  (Scallop's default exit code).
