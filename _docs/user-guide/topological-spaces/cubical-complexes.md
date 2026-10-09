---
layout: main
title: Cubical complexes and images
---

### Cubical complexes and images

An image is a function on a grid, and its sublevel sets (the pixels at or below a threshold, as the threshold rises)
form a filtered cubical complex: each pixel is a square (a cube, for voxels), together with its edges and corners.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val pixels = Array(
  Array(0.0, 1.0, 0.0),
  Array(1.0, 2.0, 1.0),
  Array(0.0, 1.0, 0.0)
)
val dark = Persistence(Image(pixels))                  // sublevel sets: the darkest pixels first
val bright = Persistence(Image(pixels).superlevel)     // superlevel sets: the brightest first
```

`Image(values, shape)` takes the values in row-major order and the size along each axis, for any number of
dimensions; `Image(rows)` takes a 2-D array. In the superlevel filtration the values are negated, so the filtration
values you read back are negative intensities (a class born at brightness 1.1 is born at -1.1).

To build the complex itself, or read an image from a file:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val values = IndexedSeq(0.0, 1.0, 0.0, 1.0, 2.0, 1.0, 0.0, 1.0, 0.0)
val stream = CubicalImage.fromFlatArray(shape = IndexedSeq(3, 3), flatValues = values, sublevel = true)
Persistence(stream)
```

`CubicalImage` also has `fromGrayscale2D` and `fromVoxelGrid3D` (arrays), `fromBufferedImage` and `fromFile` (image
files); [files](../input-output.md) reads Perseus and DIPHA grids. A pixel with value `Infinity` (`-Infinity` in a
superlevel image) never enters (a mask, for missing data); a `NaN` value is refused, with a message naming the pixel.

`Persistence` computes the diagram of an image of two or more dimensions with the
[fast cubical engine](../homology-computation/fast-cubical.md), which is much faster than matrix reduction and gives
cycles as representatives. The [images tutorial](../../tutorials/images.md) works through an example. From MATLAB, `TDA4j.computeFromImage(pixels,
options)` or `computeFromCubicalImage(shape, values, options)`, with the option `sublevel`.
