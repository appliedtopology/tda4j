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
