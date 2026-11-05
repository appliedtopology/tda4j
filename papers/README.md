# Papers

`journal/` holds the first draft of the journal article that announces TDA4j. `common/` and `tools/` are shared and
are meant to be reused by the conference paper, which is written separately (its narrative is the authors' to craft).

## Build

```
make journal      # papers/journal/main.pdf (latexmk, bibtex; plain `article` class, no venue template yet)
make todo         # everything still pending
make results RESULTS="path/to/results-dir [more dirs]"   # regenerate common/results.tex from the harness output
make clean
```

## Three markers, three colours

Defined in `common/macros.tex`. The draft is built with them visible (`\drafttrue`); `\draftfalse` makes every marker print
`??` and warn, so a submission build cannot hide an unresolved one.

| macro | colour | meaning |
|---|---|---|
| `\TODO{..}`, `\TODOtime{..}`, `\TODOmem{..}`, `\TODOnum{..}` | red | something to supply; the last three are the placeholders for timings, memory and other measured numbers |
| `\OUTCOME{..}` | blue | a sentence written from the design, as a template for the result; rewrite it from the table, do not edit it |
| `\VERIFY{..}`, `\PROV{..}` | orange | a claim written from project notes and not re-checked; a provenance question (who first did this) |

No timing, memory or benchmark ratio in the draft is a real number: every one is a placeholder or a table cell that
shows a red placeholder (`??` in a final build) until the harness has produced the value.

## How the tables fill in

A table cell is a key `case/p/tool/field`, with field one of `warm`, `cold`, `mem`, `status`, `bars`, `agree`, `dist`
(`\resT{case}{p}{tool}`, `\resC`, `\resM`, `\resA` read warm time, cold time, memory, agreement; `\res[kind]{key}` reads any key). `common/results.tex` is generated from the harness's `summary.csv` files by
`tools/bench-to-tex.py` and defines the keys it knows. Values that the harness does not write to `summary.csv` (thread sweeps,
alpha sweeps, the ablations) are entered by hand in `common/results-manual.tex`, one `\defres{key}{value}` per line (see the header of that file). A table
row is `\benchrow{p}{tool}{label}`; the columns are the cases of `\benchcases`. The table of cases and rows in Appendix B is
the harness's own list (`bench/bench.py`, `CASES` and `TOOLS`): regenerate it from the final commit.

Before the evaluation can be filled in, see Appendix D of the draft ("Notes to the authors"): a final run on a frozen
commit (`bench/final_run.sh`), the unrun rows, and the harness gaps.

## What is reusable for the conference paper

`common/macros.tex` (markers, lookup, table rows), `common/refs.bib` (about 70 entries; entries with a `verify` field are
not yet checked against the source, the arXiv ones carry `eprint`), `tools/`, and the Appendix A catalogue with its
provenance tags. The evaluation tables take their numbers from the same keys, so a condensed paper that reuses a table
reads the same results.

## Assumptions to confirm

- Plain `article` class: the venue is not decided (the plan in `.claude/PLAN-paper.md` recommends the Journal of Applied and
  Computational Topology; ACM TOMS and JOSS are alternatives).
- The author list is the one in `CITATION.cff`.
- Appendix D is wrapped in `\ifdraft` and disappears with `\draftfalse`.
