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
