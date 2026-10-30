---
layout: main
title: Zigzag persistence
---

A filtration only grows. Zigzag persistence follows homology through a sequence of complexes in which each is
included in the next or the next in it, in any order: a graph that gains and loses edges over time, a point cloud that
moves, a sample that is added to and thinned out. A class of the homology of one complex is followed to the next
through the inclusion, forward or backward, for as long as it lasts. The result is a barcode like any other: a bar
`[birth, death)` is a class that is in the homology at the positions `birth` to `death - 1` of the sequence, and not
at the neighbouring positions. The positions number the complexes from 0, and a class that is still there at the last
one has death `∞`.

## Adding and removing cells

The most direct input is a list of steps from a starting complex, each a cell that enters or a cell that leaves. A cell
enters after its facets and leaves before the cells it is a facet of, so that every complex on the way is a complex.

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*
import ZigzagStep.{Add, Remove}

val steps = Seq(
  Add(Simplex(0)), Add(Simplex(1)), Add(Simplex(2)),
  Add(Simplex(0, 1)), Add(Simplex(1, 2)),
  Add(Simplex(0, 2)),        // the three edges form a loop
  Remove(Simplex(0, 2))      // and it is gone again
)
val diagram = ZigzagPersistence(steps)
diagram.bars.map(bar => (bar.dim, bar.birth, bar.death))
// degree 0: [1, ∞), [2, 4), [3, 5); degree 1: [6, 7)
```

Position 0 is the starting complex, here empty, and position `k` is the complex after `k` steps. The vertex `1` enters
at position 2 as a new component and is joined to the first by the edge at position 4, so its bar is `[2, 4)`: the
older component carries on. The loop is born when the third edge enters at position 6 and is gone at position 7.

`ZigzagPersistence(steps, initial, maxDimension, characteristic)` takes the complex to start from (`initial`, default
none), the top degree of the bars (`maxDimension`, default 2) and the prime of the coefficient field (default 17), or
`coefficients = Coefficients.Rationals` or `Coefficients.Reals`. The result is an ordinary `PersistenceDiagram`, and the
bars have representatives: a cycle in the complex at the bar's birth.

```scala 3
import diagram.given
val loop = diagram.bars.filter(_.dim == 1).map(_.representative)
```

## A list of complexes

When the complexes are given rather than the steps between them, `ofComplexes` takes their cells. Consecutive
complexes must be included in each other, or be joined through their union or their intersection, which then takes a
position of its own: `K₀ ⊂ K₀ ∪ K₁ ⊃ K₁`. This is the usual way to follow a changing point cloud, with a complex at
each time (a Vietoris-Rips complex at a fixed scale, say) on vertices that keep their number from one time to the next.

```scala 3
val first  = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(0, 1), Simplex(1, 2))
val second = Seq(Simplex(0), Simplex(1), Simplex(2), Simplex(1, 2), Simplex(0, 2))
val changing = ZigzagPersistence.ofComplexes(Seq(first, second), link = ZigzagPersistence.Link.Union)
// the union of the two paths is the full triangle's boundary: one loop, at position 1 only
```

Without the `link`, two complexes that are not included in each other are refused with a message that says so. The
complexes must be closed under taking faces; a cell with a facet that is missing is refused by name.

## What it is, and what it costs

The barcode is computed from left to right, one inclusion at a time, on the homology of the complexes in the sense of
Carlsson and de Silva: it keeps a basis of the homology at the current position, each class with its birth, and an
inclusion forward ends the classes that become zero, while an inclusion backward ends the classes that are not the
image of anything and begins the classes in the kernel. Each inclusion recomputes the homology in the two degrees it
changes, by sparse elimination, so the cost is that of reducing the boundary matrices of the larger complex of each
arrow. This is meant for sequences of up to a few thousand cells in the dimensions that change; for a filtration that
only grows, `Persistence` is the faster tool, and a zigzag in which every arrow is forward gives the same bars.

Cells of dimension above `maxDimension + 1` are left out. There is no command-line form, since the input is a
sequence of complexes rather than a file of points.

## From the other interfaces

| interface | call |
|---|---|
| [MATLAB and Java](matlab.md) | `TDA4j.zigzagFromSteps(initial, simplices, additions, options)` and `zigzagFromComplexes(complexes, options)`: a `ZigzagResult` with `toArray()` (rows `(dimension, birth, death)`), `cycleVertices(i)`, `cycleCoefficients(i)`, `size()` and `lastPosition()`; options `maxDimension`, `prime` and, for complexes, `link` (`none`, `union`, `intersection`) |
| [JavaScript](javascript.md) | `zigzagFromSteps(initial, simplices, additions, options)` and `zigzagFromComplexes(complexes, options)`: `{ bars, representatives, lastPosition }` |
| [Python](python.md) | `tda4j.zigzag_from_steps(initial, simplices, additions, **options)` and `zigzag_from_complexes(complexes, **options)`: `bars`, `representatives` and `last_position` |
