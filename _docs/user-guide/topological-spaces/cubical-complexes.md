### Cubical complexes and images

```scala 3
val stream = CubicalImage.fromFlatArray(shape = IndexedSeq(3, 3), flatValues = pixelValues, sublevel = true)
val homology = CubicalHomologyContext[Double, Double]().persistentHomology(stream)
homology.diagramAt(Double.PositiveInfinity)
```

`CubicalImage` also has `fromGrayscale2D`, `fromVoxelGrid3D`, `fromBufferedImage`, and `fromFile` for loading
real images/volumes. `sublevel = false` computes superlevel-set persistence instead (ascending vs.
descending intensity) via the standard "negate the values" trick — reported filtration values under
`sublevel = false` are in negated-intensity units, not raw pixel values.
