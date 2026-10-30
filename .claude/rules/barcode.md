---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/barcode/**"
  - "src/test/scala/org/appliedtopology/tda4j/barcode/**"
---

# Barcode distances and vectorizations

**`BarcodeDistance`/`Vectorization`/`BootstrapBand`/`ToMATo`**: distances (matchings are `None` on an essential-count
mismatch), sliced Wasserstein, landscapes, images, Betti curves (`birth <= t < death`), silhouettes, entropy on
`PersistenceBar[Double,_]`. Ground norm matches Hera/GUDHI, images `persim`. Essential bars: matched by birth for distance
(mismatch → `+Infinity`); kept by landscapes and Betti curves, dropped by the rest. `longerThanFraction` is a display rule,
not a test; `BootstrapBand.threshold` (2 x width, Fasy 2014) is the estimate. The facades are in `facade.md`. `WORKLOG-diagram-statistics.md`.
