---
layout: main
title: Calling from MATLAB or Java
---

`org.appliedtopology.tda4j.matlab.TDA4j` is the library's interface for MATLAB (through its built-in Java support) and
for plain Java. Every method takes and returns only `int`, `double`, `String`, arrays of those, and result objects, and
options are a flat list of name-value strings, so a new option never changes a method's signature.

```matlab
javaaddpath('/path/to/tda4j-VERSION-assembly.jar');   % see Installation
import org.appliedtopology.tda4j.matlab.*;

points = [0.0 0.0; 1.0 0.0; 0.5 0.8];
result = TDA4j.computeFromPoints(points, {'maxDimension', '1'});
bars = result.toArray();                 % one row per bar: [dimension birth death], death Inf if essential
simplices = result.cycleVertices(0);     % the representative of bar 0: one row per simplex, 0-based vertices
coefficients = result.cycleCoefficients(0);
```

```java
double[][] points = { {0.0, 0.0}, {1.0, 0.0}, {0.5, 0.8} };
PersistenceResult result = TDA4j.computeFromPoints(points, new String[] {"maxDimension", "1"});
double[][] bars = result.toArray();
```

From Python, the [`tda4j` package](python.md) makes these calls with NumPy arrays: `computeFromPoints(points, {'maxDimension', '1'})`
is `tda4j.persistence(points, max_dimension=1)`, and the options below are keyword arguments in snake_case.

Bars and simplices are numbered from 0, as everywhere in Java. The [tutorials](../tutorials/index.md) show most tasks
in a MATLAB tab next to the Scala code.

### Conventions

* **Options** are a flat array of name-value strings: `{'maxDimension', '1', 'complex', 'alpha'}` in MATLAB,
  `new String[] {"maxDimension", "1"}` in Java. Names are not case-sensitive; every value is a string, so write a number
  with `sprintf('%.17g', x)` in MATLAB (`num2str` rounds). An odd-length array, an unknown name, an unknown value or an
  option that does not apply to the entry point throws an `IllegalArgumentException` whose message names it and lists
  the accepted ones.
* **Numbering** is from 0 everywhere: bars, cells, landmarks, point indices and cocycle indices.
* **Matrices** are `double[][]` (a MATLAB matrix converts by itself); one row per point.
* Each `compute...` method has an overload without options.

### Entry points

| method | computes |
|---|---|
| `computeFromPoints(points, options)` | a point cloud, one row per point, with Euclidean distances: any `complex` |
| `computeFromDistanceMatrix(distances, options)` | a square, symmetric distance matrix with a zero diagonal (the lower triangle is read): `vr`, `witness`, `dtm-rips`, `sparse-rips`, not `alpha`, `cech` or `dtm-alpha`, which need coordinates |
| `computeFromImage(pixels, options)` | a 2-D image as a matrix, `pixels(row, column)` |
| `computeFromCubicalImage(shape, values, options)` | an image or voxel grid of any dimension: `shape` is the size along each axis, `values` are in row-major order (last axis fastest) |
| `computeFromRelation(relation, options)` | the Dowker complex of a relation: `relation(x, w)` is the strength of the tie between row `x` and column `w` (smaller is stronger, `Infinity` for never, non-negative); it need not be square or symmetric. Options: `engine` (`naive`, the default, or `cohomology`), `maxDimension`, `maxFiltrationValue` (default `Infinity`), `dual`, `representativesOn` (`rows` or `columns`: the side the representatives are on, by default the side built; with landmark-to-point distances `columns` gives cycles or cocycles on the points, see [Dowker complexes](topological-spaces/dowker-complexes.md)), `field`, `prime`, `epsilon`, and the bar options |
| `selectLandmarksFromPoints(points, options)`, `selectLandmarksFromDistanceMatrix(distances, options)` | step 1 of the two-step witness complex: options `numLandmarks` (required), `landmarkSelector`, `landmarkSeed`, and no others. Returns a `LandmarkSelectionResult` |
| `computeFromPointsAndLandmarks(points, landmarks, options)`, `computeFromDistanceMatrixAndLandmarks(distances, landmarks, options)` | step 2: the witness complex for given landmarks (0-based, non-empty, distinct). Options: `witnessVariant`, `nu`, `engine`, `representativeType`, `maxDimension`, `maxFiltrationValue`, `field`, `prime`, `epsilon`, the bar options, and `complex` only as `witness`. See [witness complexes](topological-spaces/witness-complexes.md) |
| `coveringRadiusFromPoints(points, landmarks)`, `coveringRadiusFromDistanceMatrix(distances, landmarks)` | the covering radius `R` of a landmark set: the largest distance from a point to its nearest landmark |
| `eulerCurveFromPoints(points, options)`, `eulerCurveFromDistanceMatrix(distances, options)` | the [Euler characteristic curve](vectorization.md#the-euler-characteristic-curve) of the Vietoris-Rips complex, counted without building it. Options: `maxSimplexDimension` (default `2`), `maxFiltrationValue`, `threads`. Returns an `EulerCurveResult` with `scales()`, `values()`, `at(t)` and `sample(tMin, tMax, resolution)` |
| `computeRelativeFromPoints(points, subcomplex, options)`, `computeRelativeFromDistanceMatrix(distances, subcomplex, options)` | [relative persistent homology](relative-persistence.md): the Vietoris-Rips complex modulo the subcomplex on the points `subcomplex` (0-based). Options: `engine` (`cohomology`, the default, `naive` or `chunks`), `maxDimension`, `maxFiltrationValue`, `field`, `prime`, `epsilon`, `representativeType`, the bar options and `threads`. The representatives and the cells of the bars are cells outside the subcomplex |
| `computeInclusionFromPoints(points, subsample, options)`, `computeInclusionFromDistanceMatrix(distances, subsample, options)`, `computeMapFromPoints(sourcePoints, targetPoints, vertexMap, options)`, `computeMapFromDistanceMatrices(…)` | the [map on persistent homology](barcode-algebra.md#maps-between-spaces) induced by the inclusion of the Vietoris-Rips complex of a subsample (0-based point numbers) into the whole, or by a map `vertexMap` of points that does not increase a distance: a `PersistenceMapResult` with `maxDimension()`, `characteristic()` and, per degree `k`, `sourceBars(k)`, `targetBars(k)`, `entries(k)` (rows of source bar, target bar, coefficient) and the barcodes `image(k)`, `kernel(k)`, `cokernel(k)`. Options: `engine` (`ripser`, the default, or `cohomology`), `maxDimension`, `maxFiltrationValue` (shared; by default the target's enclosing radius), `prime`, `threads` |
| `persistentLaplacianFromPoints(points, dimension, from, to)`, `persistentLaplacianFromDistanceMatrix(distances, dimension, from, to)` | the [persistent Laplacian](persistent-laplacian.md) of the Vietoris-Rips complex: a `PersistentLaplacianResult` with `matrix()`, `eigenvalues()` (smallest first), `bettiNumber()`, `spectralGap()` (`NaN` if every eigenvalue is zero), `hasSpectralGap()` and `cells()` |
| `morseReductionFromPoints(points, options)`, `morseReductionFromDistanceMatrix(distances, options)` | the [discrete Morse reduction](discrete-morse.md) of the Vietoris-Rips complex: a `MorseReductionResult` with `cellCounts()` and `criticalCounts()` by dimension, `pairCount()`, `criticalCells()` and `bars()` (rows `(dimension, birth, death)`, computed on the critical cells). Options `maxDimension` (2), `maxFiltrationValue`, `strategy` (`reduction` or `coreduction`), `prime` (17) and `threads` |
| `zigzagFromSteps(initial, simplices, additions, options)`, `zigzagFromComplexes(complexes, options)` | [zigzag persistence](zigzag-persistence.md) of a sequence of simplicial complexes (simplices as arrays of vertex numbers), given as steps that add or remove one simplex or as a list of complexes: a `ZigzagResult` with `toArray()` (rows `(dimension, birth, death)`, death `Inf` for a class that lasts to the end), `cycleVertices(i)`, `cycleCoefficients(i)` of the representative at each bar's birth, `size()` and `lastPosition()`. Options `maxDimension` (2), `prime` (17) and, for complexes, `link` (`none`, `union` or `intersection`) |
| `productBars(first, second, filtration)`, `homBars(first, second)`, `homDimension(first, second)`, `dualBars(bars)` | the [algebra of barcodes](barcode-products.md): the bars of the homology of a product of two filtered complexes from the bars of the factors (`filtration` is `"max"` or `"sum"`), the internal Hom, the dimension of the space of maps, and the dual. Rows `(dimension, birth, death)` in and out, `result.toArrayUnfiltered()` for the barcode of a result |
| `bootstrapBand(points, options)` | the [resampling band](noise-and-clusters.md) of the bars of one degree, a `BootstrapBandResult` with `width()`, `threshold()` (bars that persist longer survive the resampling), `alpha()` and `distances()`; options `dimension`, `rounds`, `alpha`, `seed`, `subsampleSize`, `prime`, `maxFiltrationValue`, `threads` |
| `tomatoClusters(points, k, tau)`, `tomatoDiagram(points, k)` | [ToMATo](noise-and-clusters.md) clustering: the cluster of every point (0 for the highest peak), and the `(birth, death)` of the valleys between peaks, whose persistence is their depth |
| `takensEmbedding(series, dimension, delay)`, `takensEmbedding(series, dimension, delay, stride)` | the sliding-window (Takens) embedding of a time series: the points are the windows `(x_t, x_{t+delay}, ...)`, for any stride-th start `t`. `takensEmbeddingOfVectors(rows, dimension, delay)` does the same for a series of vectors, one row per time step |
| `delayByAutocorrelation(series, maxLag)` | the first lag in `1..maxLag` at which the autocorrelation is zero or negative: a first guess for `delay` |
| `h1Bars(points)`, `h1Bars(points, numLandmarks)` | the loops of the Vietoris-Rips complex: one row `(birth, death)` per persistent H¹ class, most persistent first; with `numLandmarks`, the loops of the balls around that many maxmin landmarks, for sparse coordinates |
| `circularCoordinates(points, r)`, `circularCoordinates(points, r, cocycleIndex, prime)`, `circularCoordinates(points, r, cocycleIndex, prime, numLandmarks)` | circular coordinates of loop `cocycleIndex` of `h1Bars` at scale `r` (defaults `0` and `47`, an odd prime); throws `NoIntegerCocycleException` if the class has no integer lift at that prime; `numLandmarks > 0` gives Perea's sparse coordinates over that many landmarks, for many points |
| `toroidalCoordinates(points, r, cocycleIndices)`, `toroidalCoordinates(points, r, cocycleIndices, prime, reduce)`, `toroidalCoordinates(points, r, cocycleIndices, prime, reduce, numLandmarks)` | toroidal coordinates for several loops alive together at `r`; `reduce` (default `true`) applies lattice reduction; `numLandmarks` as for `circularCoordinates` |
| `loadResult(path)` | a result saved by `result.save(path)` or `tda4j --save-result` |
| `setPlotDefaults(settings)`, `clearPlotDefaults()` | session defaults for every plot, see [plotting](plotting.md#defaults-for-every-plot-a-given-look) |

In an image, a pixel of value `NaN` is masked (missing data): it is not in the complex, whatever `sublevel` is. `Inf` and
`-Inf` are ordinary values: with `sublevel` `true`, `Inf` enters after every finite value and `-Inf` before them; with
`sublevel` `false`, the reverse. With `nanMask` `false`, masks are off and a `NaN` pixel is an error naming the pixel,
for data where a `NaN` can only be a bug.

### Options

| option | values | default |
|---|---|---|
| `complex` | `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sparse-rips` | `vr` |
| `engine` | `ripser`, `parallel-ripser`, `chunks`, `naive`, `cohomology`, `fast-cubical`, `fast-alpha` | `ripser` for `vr` and the lazy witness complex (`parallel-ripser` instead for `vr` when there are several threads and it applies, see [Threads](#threads)), `fast-cubical` for images of dimension 2 and up, `cohomology` otherwise |
| `threads` | integer, at least 1 | all the processors; `1` is the sequential computation |
| `representativeType` | `cycles`, `cocycles` | `cycles` (every engine gives both except `fast-cubical` and `fast-alpha`, which give cycles; `ripser` and `cohomology` compute cocycles natively and cycles a little more slowly, the others the other way round) |
| `maxDimension` | integer | `2`: the top homological degree (for an image: its dimension) |
| `maxFiltrationValue` | number | the minimum enclosing radius (`Infinity` for the general witness complex, which is not a flag complex; `Infinity` for any complex gives the whole complex, and `engine=fast-alpha` takes the whole triangulation and no radius) |
| `field` | `Z` (a prime field), `R` (floating point), `Q` (exact rational numbers: the bars are computed exactly and the coefficients of the representatives reported as the nearest `double`) | `Z` |
| `prime` | prime | `17` |
| `epsilon` | number | `1e-9`: the tolerance of `field=R` |
| `minPersistence` | number | unset: an absolute threshold for the reported bars |
| `minPersistenceFraction` | number | `0.01`: the threshold as a fraction of the input's scale |
| `includeZeroLength` | `true`, `false` | `false` |
| `sublevel` | `true`, `false` | `true` (images) |
| `nanMask` | `true` (a `NaN` pixel is masked), `false` (a `NaN` pixel is an error) | `true` (images) |
| `alphaBackend` | `default`, `bowyer-watson`, `helix`, `DQP` | `default`: `bowyer-watson` for points in at most 4 dimensions, `helix` above; with `maxFiltrationValue`, `DQP` instead when it is expected to be faster (same result in general position) |
| `requireValidTriangulation` | `true`, `false` | `false` (alpha with `helix`: also check for cavities, and fail rather than return a triangulation that could not be repaired) |
| `dtmK` | integer | required for `dtm-rips` and `dtm-alpha` |
| `dtmQ`, `dtmP` | number | `2.0`, `1.0` (`dtmP` is `1.0` or `2.0`, for `dtm-rips`) |
| `sparseEpsilon` | number in `(0, 1)` | required for `sparse-rips` |
| `numLandmarks` | integer | required for `witness` |
| `witnessVariant` | `lazy`, `general` | `lazy` |
| `landmarkSelector` | `maxmin`, `random` | `maxmin` |
| `landmarkSeed` | integer | `0` |
| `nu` | `0`, `1`, `2` | `2` (lazy witness) |
| `edgeCollapse` | `true`, `false` | `false` (`vr` only; the same result from a smaller complex) |

Which engine goes with which complex: `ripser` needs `vr` or the lazy witness complex; `parallel-ripser` needs `vr`; `chunks` works with every
complex except `alpha`, `dtm-alpha`, the general witness complex and relations; `naive` and `cohomology` work with all;
`fast-cubical` is for images of dimension 2 and up, `fast-alpha` for `alpha` with the `bowyer-watson` or `helix` backend. A mismatch, an
unknown option or an unknown value throws an `IllegalArgumentException` that names it.

### Threads

Computations share the work between the processors where they can (the Vietoris-Rips, Cech and alpha complexes and
the block-parallel Ripser engine). `threads` caps the number of threads for one call, and `1` runs it on one; the
bars are the same either way. For `vr` over a prime field, with `includeZeroLength`
`false` and no `edgeCollapse`, an unset `engine` is the block-parallel Ripser engine (`parallel-ripser`) when there is
more than one thread, and `ripser` otherwise. Both give the bars of `ripser`. Their representatives are valid but need
not be the same chains where simplices tie in diameter, so a script that must reproduce a run exactly, on any machine,
sets `engine` to `ripser`. `engine=parallel-ripser` by name refuses what it cannot do (the reals, zero-length bars,
`edgeCollapse`, a prime above 46,000) with an error naming it. `parallel-ripser` builds a bar's
representative when you first read it, so a script that only reads `toArray()` never pays for them.

### Which bars are reported

`toArray()`, `size()`, `birth(i)`, `death(i)`, `cycleVertices(i)`, ... report a bar if it is essential or if its
persistence is greater than 1% of the input's scale: the minimum enclosing radius of a point cloud or distance matrix
(the scale up to which anything is born), or the range of values of an image or relation.

```matlab
TDA4j.computeFromPoints(points, {'minPersistenceFraction', '0.05'});   % 5% of the scale
TDA4j.computeFromPoints(points, {'minPersistence', '0.1'});            % an absolute threshold
everything = TDA4j.computeFromPoints(points, {'minPersistence', '0'}); % every bar

result.hiddenCount()             % how many bars the threshold hid
result.persistenceThreshold()    % the persistence a bar had to exceed
result.toArrayUnfiltered()       % all bars, the same layout as toArray()
```

Zero-length bars (`birth == death`) are not computed into the result at all unless `includeZeroLength` is `true`.
Distances and vectorizations (`bottleneckDistance`, `wassersteinDistance`, `slicedWassersteinDistance`, `landscape`,
`persistenceImage`, `bettiCurve`, `silhouette`, `persistenceEntropy`, see [vectorization](vectorization.md)) always use
every bar of the result, so they do not depend on its threshold.

### Representatives

`cycleVertices(i)` and `cycleCoefficients(i)` give the representative of bar `i`: the simplices (each as its sorted
vertex numbers) and their coefficients: a cycle, or a cocycle with `representativeType=cocycles`.
For the witness complex the vertices are point numbers, not landmark numbers. For an image, a cell is given by its
coordinates in the doubled grid (a pixel `(i, j)` is `(2i + 1, 2j + 1)`). The [boundary matrix](boundary-matrix.md) of
the complex is also available.

### The cells that open and close a bar

`birthCell(i)` is the cell whose entry created the class of bar `i`, and `deathCell(i)` the cell whose entry killed it,
each in the format of one row of `cycleVertices(i)`. The birth cell has the bar's dimension and enters at its birth; the
death cell is one dimension up and enters at its death. `deathCell(i)` is empty for an essential bar, and both are empty
for an engine that records no cells (`fast-cubical`, `fast-alpha`: use `engine=cohomology` for an image). Among cells that
enter at the same value, which one is the birth cell depends on the engine's order of ties; the bars do not. `save`
writes the cells of every bar to the result file (`birthCells` and `deathCells`).

## Result objects

### `PersistenceResult`

Bars are indexed from 0 in the order of `toArray()`: the reported bars only, as described above.

| method | returns |
|---|---|
| `size()` | the number of reported bars |
| `toArray()` | one row per reported bar, `[dimension birth death]`; `death` is `Inf` for an essential bar |
| `dimension(i)`, `birth(i)`, `death(i)` | the parts of bar `i` |
| `hiddenCount()` | how many computed bars the threshold hid; the result has `size() + hiddenCount()` in all |
| `persistenceThreshold()` | the persistence a bar had to exceed to be reported (`0`: no threshold) |
| `toArrayUnfiltered()` | every computed bar, laid out like `toArray()` |
| `cycleVertices(i)`, `cycleCoefficients(i)` | the representative of bar `i`: its simplices as rows of sorted vertex numbers, and the coefficients (an integer representative over `Z/p`) |
| `birthCell(i)`, `deathCell(i)` | the cells that open and close bar `i`, as one row of `cycleVertices(i)`; empty for an essential bar's death, and where the engine records no cells |
| `mostPersistentBar(dimension)` | the index of the reported bar of that dimension with the largest persistence (an essential bar first) |
| `bottleneckDistance(other, dimension)`, `(other, dimension, groundNorm)` | the bottleneck distance between the bars of that dimension of two results; `groundNorm` is `Inf` (the default) or a finite `p >= 1`. `Inf` if the numbers of essential bars differ |
| `wassersteinDistance(other, dimension)`, `(…, order)`, `(…, order, groundNorm)` | the Wasserstein distance; `order` defaults to `1` |
| `wassersteinMatching(other, dimension, order)`, `bottleneckMatching(other, dimension)` | an optimal matching of the bars of that dimension: one row `[i j]` per matched pair (bars numbered from 0 as in `toArrayUnfiltered()` restricted to that dimension), `[i -1]` for a bar of this result sent to the diagonal and `[-1 j]` for one of `other`; no rows if the numbers of essential bars differ |
| `slicedWassersteinDistance(other, dimension, directions)` | the sliced Wasserstein distance, averaged over `directions` directions; essential bars are left out |
| `landscape(dimension, numLevels, tMin, tMax, resolution)` | the first `numLevels` persistence landscapes sampled at `resolution` points of `[tMin, tMax]`; row `k` is level `k` |
| `bettiCurve(dimension, tMin, tMax, resolution)` | the number of bars of that dimension alive at each of `resolution` points of `[tMin, tMax]` |
| `silhouette(dimension, power, tMin, tMax, resolution)` | the persistence silhouette, weighted by persistence to the power `power`; essential bars are left out |
| `persistenceEntropy(dimension, normalized)` | the entropy of the bar lengths, in `[0, 1]` when `normalized` is `true` |
| `persistenceImage(dimension, sigma, birthMin, birthMax, persistenceMin, persistenceMax, birthResolution, persistenceResolution)`, and the same with a final `weightCap` | the persistence image; the weight rises to 1 at the largest persistence, or at `weightCap` |
| `numCells()`, `boundaryRows()`, `boundaryCols()`, `boundaryValues()`, `columnDimension(j)`, `columnVertices(j)`, `columnFiltrationValue(j)` | the boundary matrix of the complex, see [boundary-matrix export](boundary-matrix.md) |
| `save(path)` | writes the result as JSON and returns its absolute path |
| `plot...` | figures, see below |

Distances and vectorizations use every bar of the result, reported or not. See [vectorization](vectorization.md).

**Plots.** Each method returns a figure (or, in 3-D, a scene) that you `view()` in the browser or `save(path)` as `.svg` or
`.html`, and restyle with the methods of [plotting](plotting.md#from-matlab-and-the-command-line).

| method | draws |
|---|---|
| `plotBarcode()`, `plotBarcode(longest)`, `plotBarcode(order)` | the reported bars; the `longest` most persistent of each dimension; ordered by `'birth'` (default), `'persistence'` or `'death'` |
| `plotDiagram()` | the persistence diagram |
| `plotLandscape(dimension)`, `plotPersistenceImage(dimension)` | the vectorization of that dimension |
| `plotInput()` | the points (first two coordinates) or the 2-D image |
| `plotComplex(at)` | the complex the result was computed from, at scale `at`, over the points; it stops at the result's `maxFiltrationValue` and says so |
| `plotRepresentative(i)`, `plotRepresentative(i, at)` | bar `i`'s cycle over the input, or its cocycle restricted to the complex at the bar's birth; with `at`, over the complex at that scale (`Inf` draws a cocycle whole) |
| `plotInput3D()`, `plotComplex3D(at)`, `plotRepresentative3D(i)` | the same in 3-D, from the first three coordinates |

A plot method needs the input the result was computed from; a result from `loadResult` has it.

**Saved results.** `result.save('result.json')` writes the whole result, which `TDA4j.loadResult('result.json')` reads
back (in MATLAB, Java, Scala or `tda4j --plot ...`). The file is JSON, readable from other languages too; see
[saved results](plotting.md#saved-results) for its layout. It does not contain the boundary matrix.

### `LandmarkSelectionResult`

`landmarks()` is the landmarks as 0-based row numbers; `coveringRadius()` is `R`, the largest distance from a point to its
nearest landmark.

### `CircularCoordinatesResult`

| method | returns |
|---|---|
| `theta()` | one angle in `[0, 1)` per point, `NaN` for a point outside the loop's connected piece |
| `hasCoordinate(i)` | whether point `i` has an angle |
| `birth()`, `death()` | the loop's bar |
| `r()`, `prime()` | the scale and the prime it was computed with |
| `plot()` | the points on a cyclic color map by angle |

### `ToroidalCoordinatesResult`

| method | returns |
|---|---|
| `dimension()` | the number of coordinates |
| `theta(c)` | the angles of coordinate `c`, one per point |
| `hasCoordinate(i)` | whether point `i` has coordinates |
| `cocycleIndices()` | the loops used, indices into `h1Bars` |
| `basisChange()` | the integer change of basis chosen by lattice reduction |
| `originalGram()`, `reducedGram()` | the Gram matrices before and after reduction |
| `r()`, `prime()` | the scale and the prime |
| `plot()`, `plot(c1, c2)` | coordinates `0` and `1`, or `c1` against `c2`, as a torus |

The MATLAB tabs in the tutorials are checked as the same Java calls made from Scala, not in MATLAB itself.
