---
layout: main
title: Tutorials for TDA4j
---

# Tutorials for TDA4j

TODO: We want to port Henry Adams' excellent JavaPlex tutorials.

Placeholder page; nothing ported yet. When per-language (Java/Scala) tabbed examples are needed here we want some solution with tabsets.

Here's a test for us.

```scala sc:compile
import language.implicitConversions
import language.adhocExtensions
import language.experimental.modularity

import org.appliedtopology.tda4j.TDAContext
import org.appliedtopology.tda4j.algebra.*
import org.appliedtopology.tda4j.cells.∆

given Double is Field = Field.DoubleApproximated(1e-9)
val context = TDAContext[Int,Double,Double]()
given TDAContext[Int,Double,Double] = context
import context.{*,given}

val chain = 1.0 ⊠ ∆(1, 2) - ∆(2, 3)
println(chain)
```
