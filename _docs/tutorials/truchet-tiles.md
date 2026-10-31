---
layout: main
title: Curves in a Truchet pattern
---

A Truchet tile is a square with two quarter-circle arcs, each joining the midpoints of two neighbouring sides. Turn the tile
a quarter turn and the arcs bend around the other pair of corners. Toss a coin for every tile of a grid and the arcs meet across
the sides, smoothly, in curves: some close up into loops, the others run off the edge of the picture. This page counts the curves
with persistent homology and finds out which of them the homology sees.

That depends on what the homology is relative to. The curves alone have a cycle only where a curve closes up, so they show the
loops. Divide out the boundary of the picture and a curve that runs from edge to edge becomes a cycle too, because its ends
are gone. The long bars of the relative homology are then **all** the curves, closed or not. Persistence does not tell the two
kinds apart; the representatives do.

**The pattern.** One coin toss per tile decides which pair of opposite corners its arcs bend around. `picture` draws each arc
as a slash.

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

def tossTiles(tiles: Int, seed: Long): Array[Array[Boolean]] =
  val random = new scala.util.Random(seed)
  Array.fill(tiles, tiles)(random.nextBoolean())

def picture(flips: Array[Array[Boolean]]): String =
  flips.flatMap { row =>
    Seq(row.map(f => if f then "/ " else " \\").mkString, row.map(f => if f then " /" else "\\ ").mkString)
  }.mkString("\n")

val flips = tossTiles(6, seed = 3L)
println(picture(flips))
```

```
/ /  \/  \/ 
 / /\  /\  /
/ /  \/ /  \
 / /\  / /\ 
/  \ \ \/  \
 /\ \ \  /\ 
/ /  \/ / / 
 / /\  / / /
/  \/  \/  \
 /\  /\  /\ 
 \/ /  \ \/ 
\  / /\ \  /
```

**An image of it.** Each tile is 16 pixels wide. The value of a pixel is its distance to the nearest arc of its tile, less 1, so
that the curves, two pixels wide, are the pixels of value 0, and the sublevel set at `t` is the curves grown by `t`. (Black and
white with nothing in between would do for counting; the end of the page says what it loses.)

```scala sc:nocompile
def distanceImage(flips: Array[Array[Boolean]], side: Int): Array[Array[Double]] =
  def distanceToArc(row: Int, column: Int): Double =
    val (x, y) = (column % side + 0.5, row % side + 0.5)
    val corners = if flips(row / side)(column / side) then Seq((0, 0), (side, side)) else Seq((side, 0), (0, side))
    corners.map((cx, cy) => math.abs(math.hypot(x - cx, y - cy) - side / 2.0)).min
  Array.tabulate(flips.length * side, flips.length * side)((row, column) =>
    math.max(0.0, distanceToArc(row, column) - 1.0)
  )

val side = 16
val pixels = distanceImage(flips, side)
val grid = CubicalImage.fromGrayscale2D(pixels)
```

## The curves alone

```scala sc:nocompile
val alone = Persistence(Image(pixels), maxDimension = 1)
alone.dim(1).bars.map(_.persistence).sorted.reverse.take(7)
// 6.51, 6.51, 6.29, 6.29, 6.29, 4.90, 4.90
val long = 0.35 * side
alone.dim(1).longerThan(long).size                       // 5
```

Five bars of degree 1 stand out, with a gap to the rest. A loop lasts about 6.5 pixels, 0.4 of a tile, until the flood from
its curve has filled the face inside it. Those five are the closed curves.

## Relative to the boundary

`Relative.ofBoundary` is the pair of the picture and its edge: the cells on the boundary are divided out. `Persistence` takes it
like any stream.

```scala sc:nocompile
val relative = Persistence(Relative.ofBoundary(grid), maxDimension = 2)
val curves = relative.dim(1).longerThan(long).size   // 17
relative.dim(0).size                                     // 5
relative.dim(2).triples                                  // List((2, 6.508, Infinity))
```

Seventeen bars now stand out in degree 1, with the same gap below them. In degree 0 there are five bars, the pieces of the pair
that never touch the boundary, which are the loops. In degree 2 there is the picture itself, born when its last pixel enters.

## Counting the curves another way

Follow each arc across the midpoints of the sides of its tile, and see where it ends:

```scala sc:nocompile
def trace(flips: Array[Array[Boolean]]): (Int, Int) =
  val n = flips.length
  def top(i: Int, j: Int) = i * n + j                       // the horizontal sides, then the vertical ones
  def left(i: Int, j: Int) = (n + 1) * n + i * (n + 1) + j
  val parent = Array.tabulate(2 * n * (n + 1))(identity)
  def find(a: Int): Int =
    var x = a
    while parent(x) != x do
      parent(x) = parent(parent(x))
      x = parent(x)
    x
  for i <- 0 until n; j <- 0 until n do
    val (a, b, c, d) =
      if flips(i)(j) then (top(i, j), left(i, j), top(i + 1, j), left(i, j + 1))
      else (top(i, j), left(i, j + 1), top(i + 1, j), left(i, j))
    parent(find(a)) = find(b)
    parent(find(c)) = find(d)
  val ends = (0 until n).flatMap(k => Seq(top(0, k), top(n, k), left(k, 0), left(k, n)))
  val running = ends.map(find).toSet.size
  (parent.indices.map(find).toSet.size - running, running)

val (closed, running) = trace(flips)                     // (5, 12)
```

Five loops and twelve curves that leave the picture: 17 in all, as many as the long bars. There are always twice as many
curves leaving as there are tiles on a side, since each of the four sides has that many midpoints and every curve has two ends.

## Which are the loops?

The bars do not say. The five loops and the twelve other curves are all born at 0 and die between 6.29 and 6.51. The
representatives do: the cycle of a curve that leaves the picture has to reach the boundary, since that is what makes it a cycle
of the pair, so look for an edge of the cycle that ends there.

```scala sc:nocompile
import relative.given
val cube = summon[Cube is OrderedCell]
val reachingBoundary = relative.dim(1).longerThan(long).bars.count { bar =>
  bar.representative.cells.exists(edge =>
    cube.boundary(edge.cell)[relative.Coefficient].exists((end, _) => grid.onBoundary(end))
  )
}                                                        // 12
```

Twelve of the seventeen reach it; the other five are the loops.

## A bigger pattern

`Relative` runs the general cohomology engine. The fast cubical engine takes only a plain image, but a plain image can stand in
for the pair: put a frame of pixels below every value around the picture. The frame adds one component and one loop,
both born below everything; leave those two bars out and the rest are the bars of the pair.

```scala sc:nocompile
def framed(pixels: Array[Array[Double]]): PersistenceDiagram[Cube] =
  val size = pixels.length
  val withFrame = Array.tabulate(size + 2, size + 2)((r, c) =>
    if r == 0 || c == 0 || r == size + 1 || c == size + 1 then -1.0 else pixels(r - 1)(c - 1)
  )
  Persistence(Image(withFrame), maxDimension = 1)

val framedBars = framed(pixels).bars.filter(_.birth > -1.0).map(_.toTriple).sorted
framedBars == relative.dim(0).triples.sorted ++ relative.dim(1).triples.sorted   // true
```

The same bars, bar for bar. A pattern of 24 × 24 tiles is 384 × 384 pixels:

```scala sc:nocompile
val bigFlips = tossTiles(24, seed = 5L)
val big = framed(distanceImage(bigFlips, side))
big.dim(1).bars.count(b => b.birth > -1.0 && b.persistence > long)   // 97
trace(bigFlips)                                                      // (49, 48)
```

The 97 long bars are the 49 loops and 48 curves that leave the picture.

## The other bars

Below the gap are 31 bars of the same length, 4.90, and 505 shorter than a pixel. The 31 are not curves. Every corner where
four tiles meet is as far from the curves as any point gets, and each gives a bar; together with the 17 there are
7² − 1 = 48 bars longer than a pixel, and (tiles + 1)² − 1 in any pattern. The rest is the rounding of the distance to whole
pixels.

Drawn in black and white, with a curve pixel 0 and every other pixel 1, none of this is there. The pair has exactly 17 bars
in degree 1, 5 in degree 0 and the picture in degree 2, each of the first two kinds `[0, 1)`:

```scala sc:nocompile
val blackAndWhite = pixels.map(_.map(v => if v == 0.0 then 0.0 else 1.0))
Persistence(Relative.ofBoundary(CubicalImage.fromGrayscale2D(blackAndWhite)), maxDimension = 2).size   // 23
```

The count is right, and nothing is ranked: every curve lives exactly as long as every other. The distance gives each curve a
lifetime of about 0.4 of a tile, nearly the same for all of them because every tile has the same size.

## What to take from this

Relative to the boundary of the picture, the dominant cycles of a Truchet pattern are its curves, the ones that run off the
edge as well as the loops. The pairing cannot rank loops above the others; the representatives, or the homology of the
curves alone beside the relative one, say which is which.

## The whole script

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

def tossTiles(tiles: Int, seed: Long): Array[Array[Boolean]] =
  val random = new scala.util.Random(seed)
  Array.fill(tiles, tiles)(random.nextBoolean())

def picture(flips: Array[Array[Boolean]]): String =
  flips.flatMap { row =>
    Seq(row.map(f => if f then "/ " else " \\").mkString, row.map(f => if f then " /" else "\\ ").mkString)
  }.mkString("\n")

val flips = tossTiles(6, seed = 3L)

def distanceImage(flips: Array[Array[Boolean]], side: Int): Array[Array[Double]] =
  def distanceToArc(row: Int, column: Int): Double =
    val (x, y) = (column % side + 0.5, row % side + 0.5)
    val corners = if flips(row / side)(column / side) then Seq((0, 0), (side, side)) else Seq((side, 0), (0, side))
    corners.map((cx, cy) => math.abs(math.hypot(x - cx, y - cy) - side / 2.0)).min
  Array.tabulate(flips.length * side, flips.length * side)((row, column) =>
    math.max(0.0, distanceToArc(row, column) - 1.0)
  )

val side = 16
val pixels = distanceImage(flips, side)
val grid = CubicalImage.fromGrayscale2D(pixels)

val alone = Persistence(Image(pixels), maxDimension = 1)
val long = 0.35 * side
val loopsAlone = alone.dim(1).longerThan(long).size

val relative = Persistence(Relative.ofBoundary(grid), maxDimension = 2)
val curves = relative.dim(1).longerThan(long).size

def trace(flips: Array[Array[Boolean]]): (Int, Int) =
  val n = flips.length
  def top(i: Int, j: Int) = i * n + j                       // the horizontal sides, then the vertical ones
  def left(i: Int, j: Int) = (n + 1) * n + i * (n + 1) + j
  val parent = Array.tabulate(2 * n * (n + 1))(identity)
  def find(a: Int): Int =
    var x = a
    while parent(x) != x do
      parent(x) = parent(parent(x))
      x = parent(x)
    x
  for i <- 0 until n; j <- 0 until n do
    val (a, b, c, d) =
      if flips(i)(j) then (top(i, j), left(i, j), top(i + 1, j), left(i, j + 1))
      else (top(i, j), left(i, j + 1), top(i + 1, j), left(i, j))
    parent(find(a)) = find(b)
    parent(find(c)) = find(d)
  val ends = (0 until n).flatMap(k => Seq(top(0, k), top(n, k), left(k, 0), left(k, n)))
  val running = ends.map(find).toSet.size
  (parent.indices.map(find).toSet.size - running, running)

val (closed, running) = trace(flips)

import relative.given
val cube = summon[Cube is OrderedCell]
val reachingBoundary = relative.dim(1).longerThan(long).bars.count { bar =>
  bar.representative.cells.exists(edge =>
    cube.boundary(edge.cell)[relative.Coefficient].exists((end, _) => grid.onBoundary(end))
  )
}

def framed(pixels: Array[Array[Double]]): PersistenceDiagram[Cube] =
  val size = pixels.length
  val withFrame = Array.tabulate(size + 2, size + 2)((r, c) =>
    if r == 0 || c == 0 || r == size + 1 || c == size + 1 then -1.0 else pixels(r - 1)(c - 1)
  )
  Persistence(Image(withFrame), maxDimension = 1)

val framedBars = framed(pixels).bars.filter(_.birth > -1.0).map(_.toTriple).sorted
val sameBars = framedBars == relative.dim(0).triples.sorted ++ relative.dim(1).triples.sorted

val bigFlips = tossTiles(24, seed = 5L)
val big = framed(distanceImage(bigFlips, side))
val bigCurves = big.dim(1).bars.count(b => b.birth > -1.0 && b.persistence > long)
val (bigClosed, bigRunning) = trace(bigFlips)

val blackAndWhite = pixels.map(_.map(v => if v == 0.0 then 0.0 else 1.0))
val flat = Persistence(Relative.ofBoundary(CubicalImage.fromGrayscale2D(blackAndWhite)), maxDimension = 2)
```
