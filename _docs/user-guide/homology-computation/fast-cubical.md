---
layout: main
---

#### A faster engine for cubical images

```scala 3
import org.appliedtopology.tda4j.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

val (rows, cols) = (4, 4)
val topValue: IndexedSeq[Int] => Double = c => (c(0) * 3 + c(1) * 5) % 7 // value of each top-dimensional cell
val stream = CubicalGridStream(IndexedSeq(rows, cols), topValue)
val bars = FastCubicalHomologyEngine[Double]().persistentHomology(stream) // H0 and H1, that's everything at 2D
```

`FastCubicalHomologyEngine` computes the exact same barcode (with real representatives) as
`CubicalHomologyEngine` above, via a different algorithm entirely — a dual-graph union-find (Alexander
duality) rather than general `Chain` reduction, valid at any ambient dimension `>= 2` (it throws
`IllegalArgumentException` only for a degenerate 1-axis grid). At a 2D grid specifically, the two union-finds
(`H_0` and `H_1`) cover everything; at 3D and beyond, the "middle" dimensions (no duality shortcut applies to
them) are handed to `CellularPersistenceInChunksEngine` on a view that hides the real top-dimensional cells,
so the top dimension still skips general `Chain` reduction entirely — still a real win, though a shrinking one
as the ambient dimension grows, since the fraction of dimensions the two union-finds can cover for free shrinks
with it. See the [Developer's Guide](../../developers-guide/persistence-engines.md)'s engine 6 section for the
full picture. `matlab.TDA4j`'s `engine="fast-cubical"` option (and the CLI's `--engine fast-cubical`) use this
automatically for any `computeFromCubicalImage`/`computeFromImage` call at ambient dimension `>= 2`.
