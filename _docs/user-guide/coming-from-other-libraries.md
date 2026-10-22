---
layout: main
title: Coming from JavaPlex, Ripser or GUDHI
---

tda4j continues the Stanford libraries (JavaPlex and before it JPlex and PLEX) and reads and writes the formats of Ripser
and GUDHI. This page maps the calls you know to tda4j's, and lists the differences that change numbers.

## Differences that change results

| | JavaPlex | Ripser | GUDHI | tda4j |
|---|---|---|---|---|
| Default coefficients | `Z/2` (`getDefaultSimplicialAlgorithm`) | `Z/2` | `Z/11` | `Z/17`; pass `characteristic = 2` to compare with the others |
| Top degree | `maxDimension` is the top simplex dimension: `k + 1` for `H_k` | `--dim k` is `H_k` | `max_dimension` is the top simplex dimension: `k + 1` for `H_k` | `maxDimension = k` is `H_k` |
| Filtration values | quantized to `numDivisions` steps | exact | exact | exact |
| Default threshold | you give it | the enclosing radius | you give it | the enclosing radius |
| Zero-length bars | check your version | not reported | check your version | not reported (`includeZeroLength = true` to see them) |
| Short bars | all reported | all reported | all reported (`min_persistence = 0`) | all reported by `Persistence(...)`; the MATLAB, Java and command-line interfaces hide bars up to 1% of the data's scale (`minPersistence = 0` reports all) |
| Alpha complex filtration | a radius | | the **squared** radius | a radius: square values to compare with GUDHI |
| Representatives | generators of the bars | no | not from `persistence()` | a cycle or cocycle for every bar |

Every number in tda4j's output is a filtration value of the complex you chose: a diameter for Vietoris-Rips, a radius for
Čech and alpha. Compare shapes between complexes, not endpoints.

## From JavaPlex

JavaPlex's `Plex4` is called from MATLAB as `api.Plex4` after `import edu.stanford.math.plex4.*;`. The facade in tda4j
keeps the idea of a Java entry point that MATLAB calls ([MATLAB and Java](matlab.md)), and replaces the stream, algorithm
and interval objects by one call.

| JavaPlex | tda4j |
|---|---|
| `Plex4.createVietorisRipsStream(points, k + 1, maxDistance, numDivisions)`, `Plex4.getModularSimplicialAlgorithm(k + 1, p)`, `persistence.computeIntervals(stream)` | `Persistence(points, maxDimension = k, maxFiltrationValue = maxDistance, characteristic = p)`; MATLAB `TDA4j.computeFromPoints(points, {'maxDimension', 'k', 'prime', 'p'})` |
| `intervals.getIntervalsAtDimension(d)` | `diagram.dim(d).bars`, or `.triples`; MATLAB `result.toArray()` rows `[dimension birth death]` |
| `Plex4.createMaxMinSelector(points, n)` | `LandmarkSelector.maxmin(metricSpace, n).landmarks`; `.coveringRadius` is `R` |
| `Plex4.createRandomSelector(points, n)` | `LandmarkSelector.random(metricSpace, n, seed)` |
| `Plex4.createExplicitSelector(points, landmarks)` | pass the landmark indices to `Witness(...)` |
| `Plex4.createLazyWitnessStream(selector, k + 1, maxDistance, numDivisions)` | `Witness(metricSpace, landmarks, maxDimension = k, maxFiltrationValue = maxDistance)`; `Witness.Variant.Lazy` is the default |
| `Plex4.createWitnessStream(...)` | `Witness(..., variant = Witness.Variant.General)` |
| `Plex4.createExplicitSimplexStream()`, `addVertex`, `addElement`, `finalizeStream()` | `ExplicitStreamBuilder.fromFacets(facets)` or `fromFilteredFacets(Seq((value, facet), ...))` |
| `Plex4.getRationalSimplicialAlgorithm(...)` | `characteristic = 0`: floating point, not exact rationals |
| `Plex4.createBarcodePlot(intervals, caption, end)` | `Plot.barcode(diagram).save("barcode.svg")`; MATLAB `result.plotBarcode().save('barcode.svg')` |

A JavaPlex discretization step is gone: there is no `numDivisions`, and the values in a barcode are the real filtration values.
The witness complex corresponds to JavaPlex's tutorial one; see [witness complexes](topological-spaces/witness-complexes.md)
for the `2R` threshold. What JavaPlex has and tda4j does not: Hom complexes and persistent homology over `Z` or the
rationals as exact arithmetic.

## From Ripser and ripser.py

tda4j implements Ripser's algorithm (clearing, cohomology, apparent pairs) as its engine for Vietoris-Rips complexes, so
`Persistence(points)` computes what Ripser computes.

| Ripser | tda4j |
|---|---|
| `ripser --format point-cloud --dim k --threshold t --modulus p file` | `java -jar tda4j-VERSION-assembly.jar --input-format ripser-points --max-dimension k --max-filtration-value t --prime p --min-persistence 0 file` |
| `--format point-cloud`, `distance`, `lower-distance`, `upper-distance`, `sparse`, `binary` | `--input-format ripser-points`, `ripser-distance`, `ripser-lower`, `ripser-upper`, `ripser-sparse`, `ripser-binary` |
| `ripser(X, maxdim=k, thresh=t, coeff=p)` (ripser.py) | `Persistence(X, maxDimension = k, maxFiltrationValue = t, characteristic = p)` |
| `ripser(D, distance_matrix=True)` | `Persistence(ExplicitMetricSpace(rows))` |
| `result['dgms'][d]` | `diagram.dim(d).triples` (degree, birth, death), death `Infinity` for an essential bar |

Beyond Ripser, tda4j returns a representative for every bar, so the bars from a Ripser-style run come with the cycles
(or, with `representatives = Representatives.Cocycles`, the cocycles) that witness them. Write a diagram in a format
other tools read with `--output-format gudhi`, `dipha` or `csv`.

## From GUDHI

| GUDHI (Python) | tda4j |
|---|---|
| `RipsComplex(points=X, max_edge_length=r).create_simplex_tree(max_dimension=k + 1)`, then `st.persistence(homology_coeff_field=p)` | `Persistence(X, maxDimension = k, maxFiltrationValue = r, characteristic = p)` |
| `st.persistence_intervals_in_dimension(d)` | `diagram.dim(d).triples` |
| `AlphaComplex(points=X)`, `create_simplex_tree()` | `Persistence(X, complex = AlphaShapes)`; radii, where GUDHI has squared radii |
| `RipsComplex(distance_matrix=D)` | `Persistence(ExplicitMetricSpace(rows))` |
| `CubicalComplex(top_dimensional_cells=img)` | `Persistence(Image(pixels))`; `Image(pixels).superlevel` for superlevel sets |
| `DTMRipsComplex`, `WeightedRipsComplex` | `DtmRips.fromNeighbours(metricSpace, k, maxDimension)`, see [Vietoris-Rips](topological-spaces/vietoris-rips.md#distance-to-measure-weighting) |
| `EuclideanWitnessComplex`, `EuclideanStrongWitnessComplex` | `Witness(...)`, see [witness complexes](topological-spaces/witness-complexes.md) |
| sparse Rips, `RipsComplex(sparse=ε)` | `SparseRips(metricSpace, epsilon, maxDimension)` |
| edge collapse | `EdgeCollapse.collapse(metricSpace)` |
| `gudhi.bottleneck_distance(a, b)`, `gudhi.wasserstein.wasserstein_distance` | `BarcodeDistance.bottleneckDistance(a.bars, b.bars)`, `BarcodeDistance.wassersteinDistance(a.bars, b.bars, order)`, see [vectorization](vectorization.md) |
| `gudhi.representations` landscapes and images | `Vectorization.landscape`, `Vectorization.persistenceImage` |
| `read_points_from_off_file`, persistence files | `Gudhi.readOff`, `Gudhi.readPersistenceDiagram` and `Gudhi.writePersistenceDiagram` ([files](input-output.md)) |

Names and defaults change between versions of these libraries; check the ones you use against your version.
