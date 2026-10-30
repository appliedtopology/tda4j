---
layout: main
title: Using it from Python
---

The `tda4j` Python package is the [MATLAB and Java interface](matlab.md) with NumPy in and out: the same library, running in
a JVM that the package starts for you. Python 3.10 or newer, and Java 21 or newer on the machine.

```
pip install tda4j
```

```python
import numpy as np
import tda4j

square = np.array([[0, 0], [1, 0], [1, 1], [0, 1]])
result = tda4j.persistence(square, max_dimension=1)

result.bars                # rows (dimension, birth, death); death is inf for a class that never dies
result.diagram(1)          # [[1.0, 1.414...]]: the loop, as (birth, death)
result.representative(4)   # the loop of the last bar: its edges and their coefficients
result.bar_cells(4)        # (2, 3) and (0, 1, 2): the edge that opens that loop and the triangle that closes it
result.plot_barcode()      # a figure; in a notebook it shows itself, `.save("barcode.svg")` writes it
```

The wheel carries the library's jar, so there is nothing else to install; from a source checkout, build the jar with
`sbt tda4j/assembly` and set `TDA4J_JAR` to it (the `python/README.md` in the repository has the steps).

### Conventions

* **Options** are keyword arguments in snake_case, and they are the options of the MATLAB and Java interface: `max_dimension=1`
  is `maxDimension`, `max_filtration_value`, `prime=3` (the default is 17), `complex="alpha"`, `engine`,
  `field="Q"` (exact rationals; `"R"` for the reals), `representative_type="cocycles"`, `include_zero_length=True`,
  `min_persistence`. A value can be a number, a string or a boolean; `None` leaves the option unset. A name that is not an
  option raises `ValueError` and lists the accepted ones. The defaults are the interface's: degrees 0 to 2, a bar shorter
  than 1% of the scale of the data is hidden (`result.all_bars` has every bar, `result.hidden_count` says how many were left out).
* **Arrays** can be anything NumPy reads as numbers: arrays of any layout and type, lists, tuples. A point cloud has one row
  per point; one-dimensional data is a column (`x.reshape(-1, 1)`).
* **Numbering** is from 0 everywhere: bars, points, cells, cocycle indices. A bar is its row in `result.bars`.
* **Errors** from the library are Python errors: `ValueError` for a bad argument, `IndexError`, `NotImplementedError`, and
  `tda4j.Tda4jError` for the rest.
* **The JVM** starts when the first function needs it. To give it a bigger heap, which is the limit on how large a
  computation can be, start it yourself before that: `tda4j.start_jvm(["-Xmx16g"])`. `JAVA_HOME` chooses the Java.

### Functions

| function | input |
|---|---|
| `persistence(points, **options)` | a point cloud, one row per point; any `complex` |
| `persistence_from_distances(distances, **options)` | a square distance matrix |
| `persistence_from_image(image, **options)` | an image or voxel grid of any dimension, as a cubical complex; `nan` is missing data |
| `persistence_from_relation(relation, **options)` | the Dowker complex of a relation, a matrix that need not be square |
| `persistence_from_landmarks(points, landmarks, **options)` | the witness complex on the landmarks; `persistence_from_distances_and_landmarks` for a distance matrix |
| `relative_persistence(points, subcomplex, **options)` | the Vietoris-Rips complex [relative to](relative-persistence.md) the subcomplex on the points `subcomplex`; `relative_persistence_from_distances` for a distance matrix |
| `inclusion_map(points, subsample, **options)`, `simplicial_map(source_points, target_points, vertex_map, **options)` | the [map on persistent homology](barcode-algebra.md#maps-between-spaces) a subsample or a simplicial map induces: a `MapResult` with, in each degree, `source_bars`, `target_bars`, `entries`, `image`, `kernel`, `cokernel`; `inclusion_map_from_distances`, `simplicial_map_from_distances` for distance matrices |
| `select_landmarks(points, num_landmarks)`, `covering_radius(points, landmarks)` | landmarks by farthest-point sampling, and the covering radius of any landmarks; `..._from_distances` for distance matrices |
| `euler_curve(points, **options)` | the [Euler characteristic curve](vectorization.md#the-euler-characteristic-curve) of the Vietoris-Rips complex: call it, `curve(t)`, or `curve.sample(t_min, t_max, n)` |
| `bootstrap_band(points, **options)` | the [resampling band](noise-and-clusters.md) of the bars of one degree |
| `tomato_clusters(points, k, tau)`, `tomato_diagram(points, k)` | [ToMATo](noise-and-clusters.md) clustering: the cluster of every point, and the valleys between peaks |
| `takens_embedding(series, dimension, delay=1, stride=1)`, `delay_by_autocorrelation(series, max_lag)` | the sliding-window embedding of a time series (or of a series of vectors) as a point cloud, and a first guess for its `delay` |
| `h1_bars(points)`, `circular_coordinates(points, r)`, `toroidal_coordinates(points, r, cocycle_indices)` | [circular and toroidal coordinates](circular-coordinates.md) |
| `load_result(path)` | a result saved by `result.save(path)`, by the other interfaces, or by `--save-result` |

### What a result has

* `bars`, `all_bars`, `diagram(dimension)`, `hidden_count`, `persistence_threshold`, and `len(result)`.
* `representative(i)`: a `Representative` with `cells` (tuples of vertex numbers) and `coefficients`. For an image a cell is a
  cube in doubled coordinates (see [MATLAB and Java](matlab.md#representatives)).
* `bar_cells(i)`: the cell that created the bar and the cell that destroyed it (`None` for a bar that never dies); see
  [the quickstart](quickstart.md) for which engines record them.
* `bottleneck_distance`, `wasserstein_distance`, `sliced_wasserstein_distance`, `bottleneck_matching`,
  `wasserstein_matching`, each against another result and a dimension.
* `landscape`, `betti_curve`, `silhouette`, `persistence_entropy`, `persistence_image` (see [vectorization](vectorization.md)).
* `boundary()` and `cells()`: the filtered complex as a boundary matrix in coordinate form, and its cells.
* `plot_barcode()`, `plot_diagram()`, `plot_landscape(dim)`, `plot_persistence_image(dim)`, `plot_input()`, `plot_complex(at)`,
  `plot_representative(i)` and the `_3d` variants return a `Plot`: `svg()` and `html()` give its text, `save(path)` writes an
  `.svg` or `.html` file, `view()` opens it in the library's live viewer, and a notebook shows it. `tda4j.side(a, b)`
  puts plots in one row, and `tda4j.set_plot_defaults("palette=print")` changes the look of the next ones.
* `save(path)`, to be read back by `load_result`.

### Coming from ripser.py or GUDHI

`tda4j.persistence(X, max_dimension=1, prime=2).diagram(1)` is `ripser(X, maxdim=1)['dgms'][1]`; the defaults that differ
(the coefficients, and the top degree of GUDHI) are in [Coming from JavaPlex, Ripser or GUDHI](coming-from-other-libraries.md).

### What is not in the package

The package is the interface of [MATLAB and Java](matlab.md), so what that interface does not have, Python does not: the typed
Scala API, with its streams, engines and cursors, and the simplicial sets and group nerves of the `sset` add-on. A
board of plots with a free layout (`TDA4j.layout`) is not wrapped yet; `tda4j.side` puts plots in a row.
