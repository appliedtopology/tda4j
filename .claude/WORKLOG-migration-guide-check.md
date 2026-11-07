# The migration guide checked against JavaPlex, Ripser and GUDHI (#159)

2026-11-07, thread "Triage issues before 1.0.0 and paper". A point-in-time snapshot. `_docs/user-guide/coming-from-other-libraries.md`
existed (written from memory, 2026-10-21); this is the pass that checked its rows against the libraries.

## What was checked, against what

- **JavaPlex 4**: the source in a clone of `appliedtopology/javaplex`: `api/Plex4.java` (every `Plex4.` name the page uses
  exists; `getDefaultSimplicialAlgorithm` is `BooleanClassicalHomology`, so Z/2; `DEFAULT_NUM_DIVISIONS = 20`),
  `autogen/homology/BooleanClassicalHomology.java` (the algorithm's `maxDimension` bounds the reported degrees from above:
  intervals of degree `k < maxDimension` are kept, simplices up to `maxDimension + 1` are read, so `k + 1` for `H_k` is right
  for both the stream and the algorithm, and the shipped `rips_example.m` passes the same `max_dimension` to both; an interval
  born and killed at one filtration index is not stored), the shipped MATLAB tutorials, and a search for an alpha complex
  (none) and for integer coefficients (none: default Z/2, modular, rational).
- **ripser.cpp**: `ripser.cpp` from `Ripser/ripser` master (fetched as raw text, not built): `--format` values (including
  `dipha`), `--modulus` only under `USE_COEFFICIENTS`, the default threshold at the enclosing radius, pairs with death not
  above birth skipped.
- **GUDHI 3.13.0** (pip, run): `RipsComplex(max_edge_length=inf)`, `create_simplex_tree(max_dimension=1)`, `persistence()` with
  `homology_coeff_field=11`, `min_persistence=0` (strict: a duplicate point's zero-length H0 bar is gone, 8 bars against 9
  with `-1`), `persistence_dim_max=False`; `AlphaComplex.create_simplex_tree(output_squared_values=True)`;
  `flag_persistence_generators()` returns vertices of birth and death edges; `read_points_from_off_file`, the persistence
  readers, `collapse_edges`, `Landscape`, `PersistenceImage` exist.
- **tda4j's side**: each name in the rows is in `src/main/scala` (a grep, not a compile: the page's cells are not fences);
  the CLI's `--input-format`, `--output-format`, `--prime`, `--max-dimension`, `--max-filtration-value`,
  `--min-persistence` are in `TDA4jConf`; the DIPHA distance reader and Ripser's `dipha` format share the magic number
  8067171840.

## What changed on the page

Wrong: the alpha row said JavaPlex filters by "a radius" (it has no alpha complex); the closing sentence said JavaPlex has
persistent homology over Z and exact rationals that tda4j lacks (JavaPlex has no integer coefficients, and tda4j has exact
rationals since #270; what is left is Hom complexes of filtered complexes, `Plex4.createHomStream`). Vague, now stated:
zero-length bars for JavaPlex and GUDHI ("check your version"), GUDHI's default threshold ("you give it": it is
infinity). Missing: `--modulus` needs the `USE_COEFFICIENTS` build, JavaPlex's default 20 divisions, Ripser's `dipha`
format, GUDHI's `output_squared_values`, what `flag_persistence_generators` returns. The page now names the versions it
describes. The Euler curve section of `vectorization.md` says that a persistence diagram does not give the curve (#248's
last point, which had no home in the docs).

## Not checked

- **ripser.py**: the `ripser(X, maxdim, thresh, coeff)` and `distance_matrix=True` rows are as before: the package could
  not be installed here, and the checked source of the GitHub repository was not reachable. The only confirmed part is its
  README's `ripser(data)['dgms']`.
- **ripser.cpp was not built or run**, so the CLI row is the source's flags against tda4j's, not an output comparison.
- **GUDHI before 3.13**: `output_squared_values` and `flag_persistence_generators` are described as of 3.13.
- JavaPlex was read, not run (no MATLAB here).
