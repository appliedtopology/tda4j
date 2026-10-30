### Added

- `TakensEmbedding`: sliding-window embedding of a time series (or of a vector series) into a point cloud, with
  `delayByAutocorrelation` for a first guess of the delay.
- `GraphMetric.geodesic` and `GraphMetric.nearestNeighbours`: the shortest-path distances of a weighted graph, and of
  the nearest-neighbour graph of a point cloud (Isomap's), as a metric space.
- `WeightedGraph` and `FlagComplex`: the flag complex of a sparse weighted graph, grown from neighbour lists without
  an `n x n` matrix. `Persistence(graph)` computes it; `Ripser.readSparseGraph` reads a `sparse` file into one.
- `LowerStar`: the sublevel (and superlevel) filtration of a function on the vertices of a complex or a graph.
- `DirectedFlagStream` and `DirectedSimplex`: the directed flag complex of a weighted directed graph, whose simplices
  are ordered cliques.
- The user guide page "Time series, graphs and functions".
- `TDA4j.takensEmbedding`, `takensEmbeddingOfVectors` and `delayByAutocorrelation` in the MATLAB/Java facade, and the
  same three in JavaScript.
- The command line has `--bootstrap-band` (with `--band-dimension`, `--band-rounds`, `--band-alpha`, `--band-seed`,
  `--band-subsample-size`), `--tomato` (with `--tomato-k`, `--tomato-tau`) and `--takens-dimension` (with
  `--takens-delay` or `--takens-max-lag`), as the facade has them.
