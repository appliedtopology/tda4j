# Diagram statistics, matchings, bootstrap band and ToMATo (2026-10-29)

Context: a read-only gap survey against GUDHI, giotto-tda, persim and JavaPlex (notes in the project files, `gap-survey/`)
found the diagram-statistics layer thin. Mikael asked for "a fistful" and for a better name for the 1% filter, which
suggested significance it does not have.

What landed
- `PersistenceDiagram.significant` / `PersistenceFilter.significant` / the list extension are now `longerThanFraction`.
  0.7.0 starts a fresh MiMa series, so no filter entry; a Breaking line in `changelog.d/diagram-statistics.md`.
- `Vectorization.bettiCurve` (alive means `birth <= t < death`), `silhouette(power)`, `persistenceEntropy(normalized)`.
- `BarcodeDistance.slicedWassersteinDistance` (mean over equispaced angles in `[-pi/2, pi/2)` of the L1 distance of sorted
  projections of the diagrams completed by each other's diagonal projections), `wassersteinMatching` (Hungarian
  assignment decoded) and `bottleneckMatching` (Hopcroft-Karp on the augmented graph at the bottleneck threshold).
- `BootstrapBand` (resampling, `threshold = 2 * width`), `ToMATo` (own array union-find, ties by index).
- MATLAB (`PersistenceResult`) and JS (`Exports`, `npm/index.d.ts`) methods for the vectorizations. Not exposed: the
  matchings, `BootstrapBand`, `ToMATo` (library-only for now).

Oracles
- Sliced Wasserstein of one bar against nothing tends to `persistence * sqrt(2) / pi` (derived by hand: the difference of
  the projections is `(p/2)|sin t - cos t|`, whose mean over a period is `(p/2) sqrt(2) 2/pi`).
- ToMATo diagram equals the degree-0 barcode of the lower-star filtration of `-f` computed by the engines, on random
  graphs with ties. The strict `<` in the merge test is pinned by a valley exactly `tau` deep (a `<=` mutant survived the
  first spec set and was caught after adding that example).
- Betti curve integrates to the total persistence of a computed circle's finite bars.

Not verified
- The bootstrap band is a heuristic with a statistical reading. Fasy et al. prove coverage for specific estimators; the
  specs only check reproducibility, the quantile index, and that a noisy circle's loop exceeds the threshold while a
  uniform square's bars do not for the seeds used. A subsample band is not rescaled to the full sample.
- Comparator definitions (GUDHI's sliced Wasserstein normalisation, persim's silhouette) were not checked against their
  code; ours are defined in the scaladoc.
