# WORKLOG: prompt audit of the Claude Code configuration, and the rules/ split (2026-10-03)

Audit (`/claude-api prompt-audit`, target model claude-sonnet-5-5): scope was `.claude/CLAUDE.md` (the only project instruction
file) and the user-level skills under `~/.claude/skills`. Nothing dated in the prompting sense; the findings were stale facts.
Applied (commit "CLAUDE.md: fix stale CI/benchmark/formatter facts"):
- CI job `docs` -> `docs-build`; MiMa baseline wording (per-series, `mimaBaselineVersions`); `TDA4J_SCALA_VERSION` is also set in
  `test.yml`; "all seven" benchmark specs -> all of them (eight, `EdgeCollapseBenchmarkSpec` was missing);
  the `scalafmtSbt` paragraph (cited `project/SnipDirective.scala`, gone; contradicted the later "CI lint also runs scalafmtSbtCheck"
  line and `lint.yml`); three "now" phrasings that read as a diff against an earlier version.
- User level (not in the repo, affects every project): `~/.claude/skills/session-start-hook/SKILL.md` -- removed the async echo from the
  hook-file template (it contradicted "no async in the first iteration" and the wrap-up text) and three `IMPORTANT:` markers.
Flagged, NOT changed (owner decisions): the condensing-history sentence at the top and its "note the new condensing date/commit" rule;
measurements inside CLAUDE.md although its own header says measurements belong in worklogs (the "~90 s" tutorial-spec figure is
probably low now); `skill-creator/references/schemas.md` pins an example model id (synced skill, managed externally).

Restructure: CLAUDE.md was 57 KB loaded every session, about 40% of it subsystem detail. That detail moved, verbatim, into
path-scoped rule files `.claude/rules/*.md` (frontmatter `paths:` globs; they load when a matching file is read):
`streams`, `engines` (+ benchmark specs), `cubical`, `simplicial-sets`, `filtered-complexes` (Cech/witness/Dowker/DTM/Sheehy/edge
collapse), `alpha`, `facade` (persistence threshold, I/O, CLI, MATLAB), `docs-and-tutorials`. CLAUDE.md keeps the project-wide
rules and an index table of the rule files (a backstop if a rule does not load). 57 KB -> 19 KB; rule files 2.6-7.6 KB each.
Checked: every original non-empty line is in CLAUDE.md or a rule file except the five deliberately changed ones (promoted headings,
one cross-reference); loading verified in-session by reading a `streams/` file, `CechStream.scala`, `sidebar.yml` and a `matlab/` file
-- each pulled in its rule. Not verified: the exact glob semantics for files nobody read this session (alpha, cubical, engines).
Maintenance rule updated in CLAUDE.md "Session practices": single-subsystem rules go to the matching rule file; size budgets
~25k chars for CLAUDE.md and ~12k per rule file.
