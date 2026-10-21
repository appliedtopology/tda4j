---
layout: main
title: Glossary
---

**Bar, barcode, persistence diagram.** A topological feature born at one filtration value and dead at another is a
bar `[birth, death)`. The barcode is the multiset of bars; the persistence diagram plots them as points. A bar that
never dies is *essential*; its `death` is `Infinity`. Its length, `death - birth`, is its *persistence*.

**Betti number.** The number of independent features of a given degree: components (degree 0), loops (1), voids (2).

**Boundary, coboundary.** The boundary of a cell is the signed sum of its faces; the coboundary lists the cells that
have it as a face. A boundary matrix holds the boundaries as columns; persistence is computed by reducing it.

**Cell, simplex, cube.** The building blocks of a complex. A *simplex* is a set of vertices; a *cube* is a cell of a
grid, as in an image.

**Chain, cycle, cocycle.** A chain is a combination of cells with coefficients. A cycle is a chain with zero boundary,
a cocycle a cochain with zero coboundary.

**Complex, filtration.** A complex is a set of cells closed under taking faces. A filtration gives each cell a value
at which it enters, so that faces enter no later than the cells they bound.

**Coefficients, field.** The numbers chains are built from. tda4j works over a prime field (`characteristic = p`) or
the reals (`0`). The default is the field with 17 elements.

**Engine.** An algorithm that computes persistence, for example Ripser's. They are interchangeable for the same
input, with different speeds and abilities; see [choosing an engine](homology-computation/choosing-engine.md).

**Representative.** A cycle (or cocycle) that witnesses a bar: it is born when the bar is born and is a nonzero class
until the bar dies.

**Stream.** A filtered complex given as its cells in filtration order.

**Vietoris-Rips, Čech, alpha, witness, Dowker, cubical.** Ways to build a filtered complex from data; see
[complexes](topological-spaces/index.md).
