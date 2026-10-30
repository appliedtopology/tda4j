### Changed

- The Vietoris-Rips stream (`VietorisRips(...)`, the default construction) builds its dimensions with the layered in-order
  generator instead of the coface loop and a sort of each dimension, for up to 2^15 points. Same cells, values and order
  (`FlatLayersSpec` compares the layers bit for bit); building the layers takes 2.3 to 4 times less on uniform clouds (100
  to 250 points, median of 9 trials), and `Engine.Cohomology` on such a stream takes 1.5 to 2 times less end to end (150
  points on S^2: 9.8 s to 6.4 s; 250 points in R^3: 23 s to 12 s). On a lattice whose distances take a handful of values
  the layers take about as long as before. The stream keeps 4 bytes more per simplex of its top dimension (#251).
- `LayeredInorderSimplexStream.sortIndices` merges only the overlap of neighbouring blocks, so a nearly sorted sequence is
  sorted faster; it is an `inline` method now (same signature, same stable result).
