### Changed

- DTM-Rips, sparse Rips and the edge-collapsed Vietoris-Rips complex build their layers from a weighted edge list with
  the layered in-order generator (`LayeredInorderSimplexStream.FlagGenerator`), so the cells above the edges are
  produced in filtration order and are never sorted. Same cells, values and order as before (`FlatLayersSpec` compares
  each with the complex built from cells); building the layers, median of three trials, one JVM each, takes 2.2 to 2.5
  times less for DTM-Rips (100 to 160 points) and sparse Rips (300 to 600 points), and an edge-collapsed complex of 2,500
  to 4,000 points takes 5 to 7 times less because the pairs the collapse removed are no longer scanned (#190).

### Added

- `LayeredInorderSimplexStream.FlagGenerator.ofEdges(n, a, b, weight, threshold, deadline)`: the layers of the flag
  complex of any weighted graph in filtration order, with an optional per-vertex deadline after which a vertex joins no
  simplex (Sheehy's sparse Rips complex). `Generator` is the case of the complete graph on a metric.
