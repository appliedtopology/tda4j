---
layout: main
title: Topology of an image
---

A grayscale image is a function on a grid, brightness at each pixel, and persistent homology reads functions as naturally as it
reads point clouds. Instead of growing balls around points, you **flood** the image: let a threshold sweep from one end of the
brightness range to the other, keep the pixels the threshold has passed, and watch connected pieces and holes appear and merge.
This tutorial builds a small synthetic image with known structure, reads its barcodes, and shows how the two directions the
threshold can sweep tell different but related stories.

**The data.** A 28 × 28 image made in a few lines of code, so you can change it: a bright ring (brightness about 1), a dimmer solid
blob (about 0.8), a dark background (about 0), and a little noise (up to 0.1) on every pixel. The `#` marks pixels brighter than
one half:

```
............................
............................
............................
............................
............#...............
.........#######............
.......###########..........
......###.......###.........
......##.........##.........
.....##...........##........
.....##...........##........
.....##...........##........
....###...........###.......
.....##...........##........
.....##...........##........
.....##...........##........
......##.........##.........
......###.......###.........
.......###########..........
.........#######............
............#...............
......................###...
.....................#####..
.....................#####..
.....................#####..
......................###...
............................
............................
```

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2

val random = new java.util.Random(5L)
val size = 28
val pixels = Array.tabulate(size, size) { (row, column) =>
  val distanceToRingCentre = math.hypot(row - 12.0, column - 12.0)
  val distanceToBlobCentre = math.hypot(row - 23.0, column - 23.0)
  val signal =
    if distanceToRingCentre >= 6.0 && distanceToRingCentre <= 8.0 then 1.0
    else if distanceToBlobCentre <= 2.5 then 0.8
    else 0.0
  signal + 0.1 * random.nextDouble()
}
```

A grid of `28 × 28` pixels becomes a **cubical complex**: each pixel is a square, and the edges and corners between squares are
cells of their own, `57 × 57 = 3249` cells in all. A cell enters the complex when the threshold reaches the brightness of the
pixels around it.

## Flooding from the dark: sublevel sets

```scala sc:nocompile
val sublevelStream = CubicalImage.fromGrayscale2D(pixels, sublevel = true)
val engine = CubicalHomologyEngine[CoefficientT, Double]()
val bars = engine.persistentHomology(sublevelStream).diagramAt(Double.PositiveInfinity)
bars.size    // 1625
```

Noise makes 1,625 bars again, nearly all negligible. Because we know the noise is at most 0.1, a sensible cut-off is "persists for
more than 0.3", three times the noise amplitude. (With point clouds the scale of the data suggested a cut-off; here the noise
level of the camera does. The command-line and MATLAB default, 1% of the range, would keep 170 of the 1,625 bars.)

```scala sc:nocompile
def significant(bars: List[(Int, Double, Double)]) =
  bars.filter((_, birth, death) => death.isInfinite || death - birth > 0.3).sortBy(bar => (bar._1, bar._2))

significant(bars)
// (0, 0.00, Infinity)   (0, 0.00, 1.00)   (1, 0.06, 1.10)   (1, 0.06, 0.90)
```

Four features survive, and each one is something you can point at in the picture. Starting from the darkest pixels:

* **`(0, 0.00, Infinity)`**: the dark background, which exists from the start and is never swallowed.
* **`(0, 0.00, 1.00)`**: the dark disc *inside the ring*. It is a separate piece of darkness, walled in by the ring, until the
  flood reaches the ring's brightness (about 1.0). Then the ring's own pixels begin to enter, the disc is joined to the
  background, and its bar ends.
* **`(1, 0.06, 1.10)`**: a hole. The dark background, once it has formed a connected band around the ring, encircles it, a loop
  that stays until the ring itself is flooded (at 1.1, the brightest pixel of the ring).
* **`(1, 0.06, 0.90)`**: the same, around the blob, which is flooded at 0.9.

The death values of the two loops, 1.10 and 0.90, are the brightness of the ring and of the blob: sublevel sets read the
*brightness of obstacles*. Everything else in the 1,625 bars is noise.

## Flooding from the bright: superlevel sets

Run the threshold the other way and you track the *bright* structures instead. There is no separate setting for it: pass
`sublevel = false` and the image is negated, so the engine's "smallest first" becomes "brightest first". The numbers you read
back are negative brightnesses (a bar from `-1.10` is something born at brightness 1.10):

```scala sc:nocompile
val superlevelStream = CubicalImage.fromGrayscale2D(pixels, sublevel = false)
significant(engine.persistentHomology(superlevelStream).diagramAt(Double.PositiveInfinity))
// (0, -1.10, Infinity)   (0, -0.90, -0.06)   (1, -1.00, -0.00)
```

* **`(0, -1.10, Infinity)`**: the ring, a bright piece born at its brightest pixel, never merged into anything.
* **`(0, -0.90, -0.06)`**: the blob, born at brightness 0.90 and absorbed into the ring's piece when the threshold falls to the
  background level, 0.06.
* **`(1, -1.00, -0.00)`**: the ring's loop. It is born at brightness 1.00, when the weakest pixel of the ring closes the circle,
  and dies at 0 when the dark disc in the middle has been flooded as well.

Compare the two views. The bright view says "a ring and a blob, and the ring has a hole"; the dark view says "a background with
two obstacles in it, one of which walls in a pocket of darkness". These are the same facts, seen from either side. Which one is
easier to read depends on whether the objects you care about are brighter or darker than their surroundings.

## A faster engine

For images the generic engine above works on all 3,249 cells with the same machinery as for point clouds. Images are so regular
that there is a much faster approach, exploiting the grid (the `fast-cubical` engine, described in the
[user guide](../user-guide/homology-computation/fast-cubical.md)), and it gives *the same answer*, bar for bar:

```scala sc:nocompile
val fast = FastCubicalHomologyEngine[CoefficientT]()
val fastBars = fast.persistentHomology(sublevelStream).map(_.toTriple)   // (dimension, birth, death) for each bar
// the same 1,625 bars as engine.persistentHomology(sublevelStream).diagramAt(Double.PositiveInfinity)
```

On a 28 × 28 image you will not feel the difference. On a photograph of a few megapixels you will.

## Where to go next

Images come from files more often than from code: the [input/output](../user-guide/input-output.md) page lists the image and grid
formats TDA4j reads (Perseus and DIPHA grids, and the image loaders in `CubicalImage`), and the [cubical complexes](../user-guide/topological-spaces/cubical-complexes.md)
page covers three-dimensional volumes.

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2

val random = new java.util.Random(5L)
val size = 28
val pixels = Array.tabulate(size, size) { (row, column) =>
  val distanceToRingCentre = math.hypot(row - 12.0, column - 12.0)
  val distanceToBlobCentre = math.hypot(row - 23.0, column - 23.0)
  val signal =
    if distanceToRingCentre >= 6.0 && distanceToRingCentre <= 8.0 then 1.0
    else if distanceToBlobCentre <= 2.5 then 0.8
    else 0.0
  signal + 0.1 * random.nextDouble()
}

def significant(bars: List[(Int, Double, Double)]) =
  bars.filter((_, birth, death) => death.isInfinite || death - birth > 0.3).sortBy(bar => (bar._1, bar._2))

val sublevelStream = CubicalImage.fromGrayscale2D(pixels, sublevel = true)
val superlevelStream = CubicalImage.fromGrayscale2D(pixels, sublevel = false)

val engine = CubicalHomologyEngine[CoefficientT, Double]()
val sublevelBars = engine.persistentHomology(sublevelStream).diagramAt(Double.PositiveInfinity)
val superlevelBars = engine.persistentHomology(superlevelStream).diagramAt(Double.PositiveInfinity)

val fast = FastCubicalHomologyEngine[CoefficientT]()
val fastBars = fast.persistentHomology(sublevelStream).map(_.toTriple)

val dark = significant(sublevelBars)
val bright = significant(superlevelBars)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

% The same image as the Scala script, drawn from the same Java random number generator
random = java.util.Random(int64(5));
pixels = zeros(28, 28);
for row = 0:27
    for column = 0:27
        distanceToRingCentre = hypot(row - 12, column - 12);
        distanceToBlobCentre = hypot(row - 23, column - 23);
        if distanceToRingCentre >= 6 && distanceToRingCentre <= 8
            signal = 1.0;
        elseif distanceToBlobCentre <= 2.5
            signal = 0.8;
        else
            signal = 0.0;
        end
        pixels(row + 1, column + 1) = signal + 0.1 * random.nextDouble();
    end
end

% minPersistence 0.3 keeps the bars longer than 0.3, which is the Scala script's 'significant'
dark = TDA4j.computeFromImage(pixels, {'sublevel', 'true', 'minPersistence', '0.3'});
dark.toArray()          % four rows: [0 0 Inf], [0 0.001 1.002], [1 0.062 1.099], [1 0.064 0.900]

% With sublevel false the facade works on the negated image: births and deaths are NEGATED intensities
bright = TDA4j.computeFromImage(pixels, {'sublevel', 'false', 'minPersistence', '0.3'});
bright.toArray()        % three rows: [0 -1.099 Inf], [0 -0.900 -0.063], [1 -1.004 -0.001]

% The fast engine gives the same bars
fast = TDA4j.computeFromImage(pixels, {'sublevel', 'true', 'engine', 'fast-cubical', 'minPersistence', '0.3'});
isequal(sortrows(fast.toArray()), sortrows(dark.toArray()))      % true
size(dark.toArrayUnfiltered(), 1)                                 % 1625 bars in the full barcode
```

</div>
</div>

The facade takes an image as a matrix and has the engine choice as an option. `sublevel` false filters the *negated* image, which is why the bright features have negative births and deaths. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
