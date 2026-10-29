### Breaking

- `significant()` is renamed `longerThanFraction()` on `PersistenceDiagram` and on lists of bars, and
  `PersistenceFilter.significant` is `PersistenceFilter.longerThanFraction`. The old name suggested a statistical test;
  the method keeps the bars longer than a fraction (default 1%) of the data's scale, nothing more. Rename the call. The
  facades' `minPersistence` option is unchanged.

### Added

- `Vectorization.bettiCurve`, `Vectorization.silhouette` (Chazal et al. 2014) and `Vectorization.persistenceEntropy`
  (Rucco et al. 2016), and the matching methods on the MATLAB and JavaScript results.
- `BarcodeDistance.slicedWassersteinDistance` (Carrière, Cuturi and Oudot 2017), and `BarcodeDistance.wassersteinMatching`
  and `bottleneckMatching`, which return the matching (pairs of bars and the bars sent to the diagonal), not only the
  distance.
- `BootstrapBand`: a resampling confidence band for the bars of one degree (Fasy et al. 2014);
  `diagram.longerThan(band.threshold)` keeps the bars beyond it.
- `ToMATo`: persistence-based clustering (Chazal, Guibas, Oudot and Skraba 2013) on any graph with a density, or on a
  point cloud through its nearest-neighbour graph, with the valley depths as a diagram to choose the merging depth from.
- The user guide page "Telling features from noise, and clustering".
