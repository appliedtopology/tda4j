---
layout: main
title: Tutorials for TDA4j
---

# Tutorials for TDA4j

TODO: We want to port Henry Adams' excellent JavaPlex tutorials.

Placeholder page; nothing ported yet. When per-language (Java/Scala) tabbed examples are needed here we want some solution with tabsets.

Here's a test for us.

```scala sc:compile
import language.experimental.modularity

import org.appliedtopology.tda4j.TDAlab
val tdalab = TDAlab(17)
import tdalab.{*,given}


val chain = Fp(1) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```
