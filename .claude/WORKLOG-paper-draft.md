# Journal article draft (2026-11-05)

Point-in-time note. Mikael asked for a first draft of the journal article announcing TDA4j, with placeholders for every
timing and memory figure; he then dropped the conference paper from the request (the narrative for that audience is his and his
student's to craft). Only the journal draft exists; the shared tooling is written to be reused.

## Where things are

- `papers/journal/` the article (plain `article` class, 40 pages with the appendices); `papers/common/` macros, `refs.bib`,
  generated `results.tex`, hand-entered `results-manual.tex`; `papers/tools/` `bench-to-tex.py`, `todo-inventory.py`;
  `papers/README.md` build and marker conventions.
- Source material: `.claude/PLAN-paper.md` (2026-10-04 plan), the paper catalog (2026-10-24, project memory), `bench/` harness
  (`CASES`, `TOOLS`), `.claude/BUGS-IN-REFERENCES.md`, the rule files, the docs.

## Method

- Every measured number is a marker (`\TODOtime`, `\TODOmem`, `\TODOnum`) or a table cell keyed `case/p/tool/field`, filled
  from the harness's `summary.csv` by `bench-to-tex.py`. Sentences whose direction depends on the benchmark are `\OUTCOME`
  (blue, written from the design, to be rewritten from the table). Unverified claims are `\VERIFY`/`\PROV` (orange).
  `make todo` lists everything pending (79 `\TODOnum`, no loose time or memory placeholders at the time of writing; the
  table cells are listed separately).
- Bibliography: about 70 entries, arXiv ones checked against arXiv (they carry `eprint`); the rest carry a `verify` field.
- No new numbers were measured. Counts quoted in notes (bars, cells) come from the project notes.

## Open questions (also Appendix D of the draft)

Preprint (arXiv:2411.05495) against the edge-block material (is there a newer version?); Helix's design source; the source of
the barcode algebra; proof of the volume-optimality conjecture for the dual union-find representative; prior art for
persistent group homology through Brown's collapse; prior-art checks not run (index width, lazy representatives,
block-parallel reduction, agreement checks at scale, representatives in a browser); venue; authors and AI statement;
defects in references to report upstream (three in the draft, plus an unspecified one in jvptree 0.3.0).

## Prerequisites for the evaluation

A final run on a frozen commit (`bench/final_run.sh`) with the tda4j rows rerun; unrun rows (RedZeD on the server,
Flash Cubical, Dowker packages, OAT at primes, 128^3 cubical); harness gaps: `bench-to-tex.py` reads `summary.csv` only
(sweeps and ablations need a reader or hand entry), representative sizes are not recorded, interquartile ranges are not
tabulated.
