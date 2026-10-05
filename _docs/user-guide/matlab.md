---
layout: main
title: Calling from MATLAB or Java
---

`org.appliedtopology.tda4j.matlab.TDA4j` is the library's interface for MATLAB (through its built-in Java support) and
for plain Java. Every method takes and returns only `int`, `double`, `String`, arrays of those, and result objects, and
options are a flat list of name-value strings, so a new option never changes a method's signature.

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar');   % after sbt assembly
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

Bars and simplices are numbered from 0, as everywhere in Java. The [tutorials](../tutorials/index.md) show most tasks
in a MATLAB tab next to the Scala code.

### Entry points

| method | input |
|---|---|
| `computeFromPoints(points, options)` | a point cloud, one row per point |
| `computeFromDistanceMatrix(distances, options)` | a full distance matrix, of which the lower triangle is read (no `alpha`, `cech` or `dtm-alpha`: they need coordinates) |
| `computeFromImage(pixels, options)` | a 2-D image |
| `computeFromCubicalImage(shape, values, options)` | an image or voxel grid of any dimension, values in row-major order |
| `computeFromRelation(relation, options)` | a relation, for the Dowker complex (options `engine`, `maxDimension`, `maxFiltrationValue`, `dual`, `field`, `prime`, `epsilon`, and the bar options) |
| `selectLandmarksFromPoints`, `computeFromPointsAndLandmarks`, `coveringRadiusFromPoints` (and `...FromDistanceMatrix`) | the witness complex in two steps, see [witness complexes](topological-spaces/witness-complexes.md) |
| `h1Bars`, `circularCoordinates`, `toroidalCoordinates` | see [circular coordinates](circular-coordinates.md) |

Each `compute...` method also has an overload without options.

### Options

| option | values | default |
|---|---|---|
| `complex` | `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sparse-rips` | `vr` |
| `engine` | `ripser`, `chunks`, `naive`, `cohomology`, `fast-cubical`, `fast-alpha` | `ripser` for `vr` and the lazy witness complex, `fast-cubical` for images of dimension 2 and up, `cohomology` otherwise |
| `representativeType` | `cycles`, `cocycles` | `cycles` (every engine gives both except `fast-cubical` and `fast-alpha`, which give cycles; `ripser` and `cohomology` compute cocycles natively and cycles a little more slowly, the others the other way round) |
| `maxDimension` | integer | `2`: the top homological degree (for an image: its dimension) |
| `maxFiltrationValue` | number | the minimum enclosing radius (`Infinity` for the general witness complex and for `alpha`, which then builds the whole complex) |
| `field` | `Z` (a prime field), `R` (floating point) | `Z` |
| `prime` | prime | `17` |
| `epsilon` | number | `1e-9`: the tolerance of `field=R` |
| `minPersistence` | number | unset: an absolute threshold for the reported bars |
| `minPersistenceFraction` | number | `0.01`: the threshold as a fraction of the input's scale |
| `includeZeroLength` | `true`, `false` | `false` |
| `sublevel` | `true`, `false` | `true` (images) |
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

Which engine goes with which complex: `ripser` needs `vr` or the lazy witness complex; `chunks` works with every
complex except `alpha`, `dtm-alpha`, the general witness complex and relations; `naive` and `cohomology` work with all;
`fast-cubical` is for images of dimension 2 and up, `fast-alpha` for `alpha` with the `bowyer-watson` or `helix` backend. A mismatch, an
unknown option or an unknown value throws an `IllegalArgumentException` that names it.

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
Distances, landscapes and persistence images (`bottleneckDistance`, `wassersteinDistance`, `landscape`,
`persistenceImage`, see [vectorization](vectorization.md)) always use every bar of the result, so they do not depend on
its threshold.

### Representatives

`cycleVertices(i)` and `cycleCoefficients(i)` give the representative of bar `i`: the simplices (each as its sorted
vertex numbers) and their coefficients: a cycle, or a cocycle with `representativeType=cocycles`.
For the witness complex the vertices are point numbers, not landmark numbers. For an image, a cell is given by its
coordinates in the doubled grid (a pixel `(i, j)` is `(2i + 1, 2j + 1)`). The [boundary matrix](boundary-matrix.md) of
the complex is also available.

MATLAB itself is not part of the test suite: the tutorial MATLAB tabs are checked by making the same Java calls from
Scala.
