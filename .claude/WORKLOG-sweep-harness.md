# Sweep harness: one radius for every tool (#242) and mergeable sweep directories (#243)

2026-11-06, thread "Triage issues before 1.0.0 and paper". A point-in-time snapshot; the rules that came out of it are in
`bench/README.md` (alpha sweep, "Merging sweeps", "The final run").

## #242: the alpha sweep's radius

`sweep_alpha.py` called `bench.command(..., threshold=None, ...)` for every tool, so `tda4j` stopped at the enclosing
radius (its default, #216), GUDHI built the whole triangulation and the named backends did what their default is. Now
`tool_radius`: a tool that takes a radius gets `fraction x enclosing radius of the draw` (fraction 1 with no `--radii`, so
the default is the `--radii 1` cell), a `no_radius` tool none. The row's `radius` column stays empty for the default, so
older `sweep.csv` files read as before; `env.json` now carries `alpha_radius_rule: enclosing`.

Issue text asked to check that the comparison Helix against GUDHI is unaffected. GUDHI alone, Python, `AlphaComplex` with
`max_alpha_square=inf` against the enclosing radius squared, 10 draws (interior n=40 d=6,8,10 and n=60 d=8, surface n=40
d=8, two seeds each): the barcode in degrees 0 to 2 identical (dimension, birth, death) on all 10; the simplex tree 21% to
40% smaller (d=6: 44,959 to 34,061; d=10: 6,397,995 to 3,839,891 and 6,377,203 to 4,377,379); time 2.28 s vs 2.36 s at
d=8, 42.9 s vs 41.8 s and 46.2 s vs 41.1 s at d=10: at most 11% faster with the cut, the Delaunay triangulation is the
cost. So GUDHI's old rows are close to the new ones, not equal; the merge refuses to mix them (below).

Trap found on the way: `enclosing_radius` built an n x n x d array. It was only called with `--radii`, but now every cell
calls it, and the `plane` path has draws of 3,000,000 points (the array would be 144 TB). The new one is exact in the
memory of a few vectors: the farthest point from p is a vertex of the convex hull, so the largest distance to the extreme
points in 68+ directions is a lower bound; points are scanned in order of that bound until it passes the best value. 3,000,000
points in the plane: 1.75 s; equal to the dense formula to 1e-12 on eight draws up to d=25 and n=6,000; the test also uses
points on a sphere, where the bound is loose (a search that stops at the first scan fails there; mutation-checked).

Not measured: the time of the `tda4j-dqp` row at the enclosing radius against no radius. By argument it should not
change: the enclosing radius is at least half the diameter (|ab| <= |ap| + |pb| <= 2 F(p)), so every pair of balls meets
and DQP's neighbour pass prunes nothing, as with no cutoff; but the candidates' exact test now has a radius. Needs a JVM run.

## #243: merging sweeps

`sweep_merge.py` (`run.sh sweep-merge OUT DIR[:tools]...`), one module for the three kinds, told apart by the columns of
`sweep.csv`. Differences from the issue text, deliberate:

- The unit replaced is a tool's *series*, not a `(case/cell, tool, threads)` row. A series stops at the first timeout or
  out-of-memory, so row-level replacement lets rows of the old commit at sizes or thread counts the rerun never reached
  stand beside the new ones. Series keys: threads `(case, p, tool)`; cubical `(dim, image, engine)`; alpha the `d`-series
  `(n, shape, radius, tool)`, the `n`-series `(d, shape, radius, tool)`, an `--extra` cell its own `(shape, n, d, radius,
  tool)`; all seeds together. Spec: merging threads without a tools filter must not keep the old 4-thread row.
- Output order is the first source's, so the tables keep their columns and rows where the earlier sweep had them.
- The sweeps' own summaries rebuild `sweep.md` (`sweep_threads.summarize`, `sweep_alpha.summarize`, and a new
  `sweep_cubical.summarize` that reads `sweep.csv`, with `--summarize`); all three print `Merged from:` when `env.json`
  has `merged_from` (`bench.merged_text`).
- `sweep_alpha` and `sweep_cubical` now write `env.json` (`bench.write_env`), so a part carries its host, date and commit.
- Merged `env.json` of the threads sweep gets the union of the parts' baselines (the TDA4j rerun runs with
  `--baselines ''`; the summary still compares with `ripser.cpp`'s time and bars from the earlier part).
- `alpha_radius_rule` guard: sources whose rule differs (an older sweep has none) do not merge without `--allow-mixed`.
- `--tools` of the threads and alpha sweeps and `--engines` of the cubical sweep take `bench.tool_selected` patterns;
  `plan` of the threads sweep reports a pattern that matches no tool.
- `final_run.sh` `SWEEPS_BASE`: TDA4j's rows alone into `$FINAL/sweeps/<sweep>-tda4j`, merged with the earlier sweep's
  others into `$FINAL/sweeps/<sweep>`; the anchors check of `merge` is shared (`require_anchors`); the alpha rule is checked
  before the rerun, not after it, so a refusal costs no compute.

## Checked and not checked

Checked: `python3 bench/test_bench.py` 217 tests (25 new; 12 skipped as before), mutation checks of eight changes to the
new code, a real GUDHI-only alpha sweep twice and its merge (`--count` logs and bars follow their rows), the dry run of
`final_run.sh sweeps` with and without `SWEEPS_BASE`.

Not checked: a merge of real sweeps with TDA4j's JVM tools in them; the live `SWEEPS_BASE` path of `final_run.sh`
(shell, dry run only); that the parts of a merge were run with the same `--cases`/`--dims` (the merge does not compare
them: `*_SWEEP_ARGS` must be the earlier sweep's).
