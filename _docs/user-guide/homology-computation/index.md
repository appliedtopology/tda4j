---
layout: main
title: Homology computation
---

An engine reads a filtered complex, cell by cell in filtration order, and pairs the cells that create classes with the
cells that kill them: the pairs are the bars. Every engine works over any coefficient field and records a representative
for every bar.

`Persistence(...)` runs an engine for you; `engine = Persistence.Engine.Naive` (or `Cohomology`, `Ripser`) picks another
than the default. Using an engine directly gives you more: a cursor you can advance in steps or in time slices and read
at any scale (`advanceTo`, `advanceFor`, `diagramAt`, `barcodeAt`), for computations too long to run in one go. See the
[quickstart](../quickstart.md#long-computations-the-cursor) for an example, and
[which engine](choosing-engine.md) for what each one supports. All engines leave zero-length bars out unless asked
(`includeZeroLength = true`).
