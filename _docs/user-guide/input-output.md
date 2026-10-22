---
layout: main
title: Loading and saving data
---

These objects read and write the file formats of the wider TDA ecosystem. Every format has a raw reader, returning
arrays, and where it makes sense a reader that returns something to compute with (`Persistence(...)` takes it directly).
All of them work on the JVM and under Node.js.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val points = CSV.readPointCloud("points.csv")
val diagram = Persistence(points)
val distances = Ripser.readLowerDistanceExplicitMetricSpace("distances.txt")
val fromDistances = Persistence(distances)
val image = Persistence(Perseus.readCubicalToplex("image.txt"))
CSV.writePersistenceDiagram("diagram.csv", diagram.bars)
```

## Point clouds

One point per line. Raw readers return `Array[Array[Double]]`; the `...MetricSpace` readers return an `EuclideanMetricSpace`.

| format | read | write |
|---|---|---|
| CSV (fields split on commas or any whitespace) | `CSV.readPointCloud`, `CSV.readEuclideanMetricSpace` | `CSV.writePointCloud` |
| Ripser `point-cloud` | `Ripser.readPointCloud`, `Ripser.readEuclideanMetricSpace` | `Ripser.writePointCloud` |
| GUDHI OFF / nOFF (`#` comments allowed; faces are ignored) | `Gudhi.readOff`, `Gudhi.readEuclideanMetricSpace` | `Gudhi.writeOff` |

## Distance matrices

Raw readers return the full symmetric `Array[Array[Double]]`; the `...ExplicitMetricSpace` readers return an
`ExplicitMetricSpace`. Writers take the full matrix.

| format | read | write |
|---|---|---|
| CSV, full `n x n` | `CSV.readFullDistanceMatrix`, `CSV.readExplicitMetricSpace` | `CSV.writeFullDistanceMatrix` |
| CSV, lower triangle, one row per line | `CSV.readLowerTriangularDistanceMatrix`, `CSV.readLowerTriangularExplicitMetricSpace` | `CSV.writeLowerTriangularDistanceMatrix` |
| Ripser `lower-distance` (all values as one stream) | `Ripser.readLowerDistanceMatrix`, `Ripser.readLowerDistanceExplicitMetricSpace` | `Ripser.writeLowerDistanceMatrix` |
| Ripser `upper-distance` | `Ripser.readUpperDistanceMatrix`, `Ripser.readUpperDistanceExplicitMetricSpace` | `Ripser.writeUpperDistanceMatrix` |
| Ripser `distance` (only each row's part below the diagonal is read) | `Ripser.readDistanceMatrix`, `Ripser.readDistanceExplicitMetricSpace` | none: write `CSV.writeFullDistanceMatrix` |
| Ripser `sparse` (`i j d` triples; a pair not listed has no edge, read as `Infinity`) | `Ripser.readSparseDistanceMatrix`, `Ripser.readSparseDistanceExplicitMetricSpace` | `Ripser.writeSparseDistanceMatrix` |
| Ripser `binary` (32-bit floats) | `Ripser.readBinaryLowerDistanceMatrix`, `Ripser.readBinaryExplicitMetricSpace` | `Ripser.writeBinaryLowerDistanceMatrix` |
| DIPHA distance matrix | `Dipha.readDistanceMatrix`, `Dipha.readExplicitMetricSpace` | `Dipha.writeDistanceMatrix` |

A matrix with infinite entries is the flag complex of its finite edges; without a `maxFiltrationValue` the filtration
runs to the largest finite distance.

## Images and grids

The raw readers return `(shape, flatValues)`, which `CubicalImage.fromFlatArray(shape, flatValues)` turns into a grid;
the writers take the same pair. The `readCubicalGridStream` / `readCubicalToplex` readers build the grid directly and
take `sublevel` (default `true`) and `nanMask` (default `true`; `false` makes a `NaN` an error instead of a masked
cell).

| format | read | write |
|---|---|---|
| DIPHA image data | `Dipha.readImageData`, `Dipha.readCubicalGridStream` | `Dipha.writeImageData` |
| Perseus cubical toplex | `Perseus.readCubicalImageData`, `Perseus.readCubicalToplex` | `Perseus.writeCubicalToplex` |

## Complexes and boundary matrices

| format | read | write |
|---|---|---|
| Perseus `simtop` (simplices of one dimension: `d`, coordinates per vertex `n`, then each simplex's vertices and birth time) | `Perseus.readUniformToplex` | `Perseus.formatUniformToplex` |
| Perseus `nmfsimtop` (each simplex starts with its own dimension) | `Perseus.readNonUniformToplex` | `Perseus.formatNonUniformToplex` |
| PHAT boundary matrix, ASCII and binary | `Phat.readAscii`, `Phat.readBinary` | `Phat.writeAscii`, `Phat.writeBinary` |

The Perseus readers return the listed simplices with their birth times; `Perseus.toplexStream(simplices)` is the filtered
complex they generate (a face is born with the earliest simplex containing it, a birth time of `-1` is skipped), which
`Persistence(...)` takes like any other stream. `Phat.fromStream(stream)` is the boundary matrix of a stream over Z/2,
columns in filtration order, for PHAT or your own reduction.

## Persistence diagrams

Readers return `Seq[PersistenceBar[Double, Nothing]]`; writers take `diagram.bars` (or any `Seq` of bars).

| format | read | write |
|---|---|---|
| CSV: `dim,birth,death` per line, `inf` for an infinite endpoint | `CSV.readPersistenceDiagram` | `CSV.writePersistenceDiagram` |
| GUDHI `.pers`: 2, 3 or 4 columns, `#` comments | `Gudhi.readPersistenceDiagram` | `Gudhi.writePersistenceDiagram(path, bars, field = None)`; with `field = Some(p)` it writes the 4-column form |
| DIPHA diagram | `Dipha.readPersistenceDiagram` | `Dipha.writePersistenceDiagram` |
| Perseus intervals: one file per dimension | `Perseus.readPersistenceIntervals(path, dim)` | `Perseus.writePersistenceIntervals(pathPrefix, bars)`, which writes `<pathPrefix>_<dim>.txt` |

Perseus's intervals are integer filtration steps, not filtration values: the writer rounds, and an essential class is
written with death `-1`. Convert your values to step indices first if you need Perseus's own meaning. The 4-column
`.pers` form carries the field's characteristic, which the reader discards.

## Text and bytes in memory

A browser page has no file system. Every reader and writer above has a counterpart that works on the text or the bytes
themselves: `parse...(text)` and `format...(data)` for the text formats (`CSV.parsePointCloud`,
`Ripser.parseLowerDistanceMatrix`, `Gudhi.parseOff`, `Perseus.parseCubicalImageData`, `CSV.formatPersistenceDiagram`, ...),
and `parse...(bytes)` and `format...(data): Array[Byte]` for the binary ones (`Dipha.parseDistanceMatrix`,
`Ripser.parseBinaryLowerDistanceMatrix`, ...). The path versions are these on a file's contents. A parser that can fail
on a line takes an optional second argument naming the text in its error messages.

## Details

Two details if you compare with another tool: Ripser's binary distance matrices are 32-bit floats, and DIPHA's and
Perseus's grids list the first axis fastest (the readers and writers convert for you). A Perseus value of `-1` is a
missing cell, read as `NaN`: masked, not in the complex, sublevel or superlevel. Writing a grid writes `NaN` as `-1` and
refuses an infinite value (the format has none).

A whole result of the [MATLAB interface](matlab.md) or the [command line](cli.md), with its representatives and the
points or image it was computed from, is saved as one JSON file by `result.save(path)` (`--save-result`) and read back
by `TDA4j.loadResult(path)`: see [saved results](plotting.md#saved-results).
