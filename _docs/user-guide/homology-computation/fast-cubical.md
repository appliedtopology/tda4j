---
layout: main
title: The fast cubical engine
---

#### A faster engine for cubical images

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

given Double is Field = Field.DoubleApproximated(1e-9)

val topValue: IndexedSeq[Int] => Double = c => (c(0) * 3 + c(1) * 5) % 7 // the value of each pixel
val stream = CubicalGridStream(IndexedSeq(4, 4), topValue)
val bars = FastCubicalHomologyEngine[Double]().persistentHomology(stream)
```

`FastCubicalHomologyEngine` gives the same bars, with representatives, as the general engines, by union-find instead of
matrix reduction: on the pixels for degree 0, and on the dual graph of the top-dimensional cells for the top degree
(Alexander duality). On a 2-D image those two cover everything. In 3-D and above, the degrees in between are computed
by the cohomology engine on the complex without its top cells. It needs an image of at least two dimensions.

It is the default for images: `Persistence(Image(pixels))` and, from MATLAB and the command line, `computeFromImage`/
`computeFromCubicalImage` or a cubical `--input-format` use it unless you choose another engine (`engine=fast-cubical`
names it explicitly, with `computeFromImage`/`computeFromCubicalImage` or a cubical
`--input-format`. The [images tutorial](../../tutorials/images.md) uses it.
