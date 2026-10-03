---
layout: main
---

### Cubical complexes and images

```scala 3
import org.appliedtopology.tda4j.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

val pixelValues = IndexedSeq(0.0, 1.0, 0.0, 1.0, 2.0, 1.0, 0.0, 1.0, 0.0) // a 3x3 image, row-major
val stream = CubicalImage.fromFlatArray(shape = IndexedSeq(3, 3), flatValues = pixelValues, sublevel = true)
val homology = CubicalHomologyEngine[Double, Double]().persistentHomology(stream)
homology.diagramAt(Double.PositiveInfinity)
```

`CubicalImage` also has `fromGrayscale2D`, `fromVoxelGrid3D`, `fromBufferedImage`, and `fromFile` for loading
real images/volumes. `sublevel = false` computes superlevel-set persistence instead (ascending vs.
descending intensity) via the standard "negate the values" trick — reported filtration values under
`sublevel = false` are in negated-intensity units, not raw pixel values.
