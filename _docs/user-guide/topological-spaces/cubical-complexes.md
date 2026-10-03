---
layout: main
---

### Cubical complexes and images

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val pixelValues = IndexedSeq(0.0, 1.0, 0.0, 1.0, 2.0, 1.0, 0.0, 1.0, 0.0) // a 3x3 image, row-major
val diagram = Persistence(Image(pixelValues, shape = IndexedSeq(3, 3)))    // sublevel filtration, F_17

// or step by step, with the engine's cursor:
given Double is Field = Field.DoubleApproximated(1e-9)
val stream = CubicalImage.fromFlatArray(shape = IndexedSeq(3, 3), flatValues = pixelValues, sublevel = true)
CubicalHomologyEngine.persistentHomology(stream).diagramAt(Double.PositiveInfinity)
```

`CubicalImage` also has `fromGrayscale2D`, `fromVoxelGrid3D`, `fromBufferedImage`, and `fromFile` for loading
real images/volumes. `sublevel = false` computes superlevel-set persistence instead (ascending vs.
descending intensity) via the standard "negate the values" trick — reported filtration values under
`sublevel = false` are in negated-intensity units, not raw pixel values.
