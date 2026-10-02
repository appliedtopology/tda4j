---
layout: main
title: All the ways to call TDA4j
---

There are several ways to access the methods in the TDA4j library, depending on how much code you want to write, what systems you want to interact with, and what you are ready to learn. In the following snippets we will show off the primary ways available. Some set-up and tasks:

1. We want to build a hollow octahedron by hand (6 vertices, 12 edges, 8 triangles) and compute its homology. It is a 2-sphere, so we expect one connected component, no loops and one void.
2. We have the following file with data points, and we want the Vietoris-Rips barcode:
``` sc:nocompile
1.0, 4.2e-15
0.9659, 0.2588
0.866, 0.5
0.7071, 0.7071
0.5, 0.866
0.2588, 0.9659
6.123e-17, 1.0
-0.2588, 0.9659
-0.5, 0.866
-0.7071, 0.7071
-0.866, 0.5
-0.9659, 0.2588
-1.0, 1.225e-16
-0.9659, -0.2588
-0.866, -0.5
-0.7071, -0.7071
-0.5, -0.866
-0.2588, -0.9659
-1.837e-16, -1.0
0.2588, -0.9659
0.5, -0.866
0.7071, -0.7071
0.866, -0.5
0.9659, -0.2588
1.0, -2.449e-16
```
3. We also want a circular coordinate function of this point cloud.

## Command Line

We package TDA4j so that it can execute common tasks from the command line, without writing any code at all. This method is somewhat limited in what it can do, but it gets you from just a file with data to a result.

Task 1 is not possible from the command line.

Task 2 can be done as follows:
```shell sc:nocompile
java -jar target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar -r --max-dimension 1 _docs/tutorials/examplepoints.csv
```

Task 3 is not possible from the command line.

## Matlab

MATLAB talks to TDA4j through its built-in Java interface: put the assembly jar (`sbt assembly`) on the Java path and call `org.appliedtopology.tda4j.matlab.TDA4j` directly. MATLAB can handle all three tasks. See [Calling from MATLAB or Java](../user-guide/matlab.md) for the complete list of options.

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');
import org.appliedtopology.tda4j.matlab.*;   % makes TDA4j (a static Java class, not an object to construct) callable by its short name

% Task 1: the octahedron is the flag complex of its own edge graph, so a distance matrix is enough.
% Vertices 1..6; the antipodal pairs (1,2), (3,4), (5,6) are NOT joined by an edge.
D = ones(6) - eye(6);          % every pair at distance 1 ...
D(1,2) = 2; D(2,1) = 2;        % ... except the antipodal pairs, which are never adjacent at scale 1
D(3,4) = 2; D(4,3) = 2;
D(5,6) = 2; D(6,5) = 2;
octahedron = TDA4j.computeFromDistanceMatrix(D, {'maxDimension', '2', 'maxFiltrationValue', '1.5'});
octahedron.toArray()           % rows [dimension birth death]: one [0 0 Inf], one [2 1 Inf]; the H1 classes
                               % born at 1 die at 1 and are not reported

% Task 2: Vietoris-Rips barcode of the points in the file
points = readmatrix('_docs/tutorials/examplepoints.csv');   % csvread on older releases
result = TDA4j.computeFromPoints(points, {'maxDimension', '1'});
bars = result.toArray();       % rows [dimension birth death]; death is Inf for an essential class
bars(bars(:,1) == 1, :)        % the one loop, about [1 0.261 1.732]
result.hiddenCount()           % number of negligible bars that were left out (see the options table)

% Task 3: circular coordinates
h1 = TDA4j.h1Bars(points);     % one row (birth, death) per H1 class, most persistent first
r = h1(1,1) + 0.5 * (h1(1,2) - h1(1,1));   % a scale at which the most persistent class is alive
coordinate = TDA4j.circularCoordinates(points, r);
theta = coordinate.theta();    % one angle in [0,1) per point
plot(cos(2*pi*theta), sin(2*pi*theta), 'o');
```

Two details worth knowing: MATLAB passes the Java `String[]` of options as a cell array of character vectors, and a MATLAB `double` matrix becomes a Java `double[][]` automatically.

## Scala REPL

When working in a Scala interpreter, where you may want to go step by step, we have set up the `org.appliedtopology.tda4j.TDAlab` class, so that importing from an instance of the class gets you a fairly comprehensive setup with some default choices made for you.

Some techniques we use to make the code easier to use are fairly experimental -- with the result that we currently cannot avoid one pure boilerplate line of code: telling Scala that we are okay with specific experimental features. The rest of this example instantiates the `TDAlab` (by picking a prime number, or 0 for computing with `Double` coefficients, in which case you can also use a `precision` argument to pick precision for equality tests):

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = new TDAlab(17)
import lab.{*,given}

// Task #1: the vertices are 1..6, and 1-2, 3-4, 5-6 are the antipodal pairs, which are never joined.
// A triangle takes one vertex from each pair, so the 8 triangles are all we have to list: fromFacets adds
// the 12 edges and 6 vertices they contain.
val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield ∆(a, b, c)
val octahedron = homology.SimplicialHomologyEngine().persistentHomology(streams.ExplicitStreamBuilder.fromFacets(triangles))
// Everything enters at 0.0 here, so the engine also lists a dozen zero-length bars (cells that cancel at once).
// Dropping bars of persistence 0 leaves the answer: one class in dimension 0 and one in dimension 2.
org.appliedtopology.tda4j.barcode.PersistenceFilter.significant(octahedron.barcodeAt(4.0), minPersistence = Some(1e-9))

// Task #2: maxDimension is the highest homology degree you want, so H_0 and H_1 here. (The stream
// itself also contains the triangles, which H_1 needs; classes in degree 2 are incomplete -- ignore them.)
val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/examplepoints.csv")
val circle = homology.SimplicialHomologyEngine().persistentHomology(streams.VietorisRips(metricSpace, maxDimension = 1))
val allBars = circle.barcodeAt(1.5).filter(_.dim <= 1)
// The engines report every bar, mostly noise. Keep the ones longer than 1% of the enclosing radius:
org.appliedtopology.tda4j.barcode.PersistenceFilter.significant(allBars, scale = Some(metricSpace.minimumEnclosingRadius))

// Task #3
// Pick a likely parameter at which your coordinate is alive, say 1.5
homology.CircularCoordinates.h1Bars(metricSpace, Some(1.5))
// observe index of the bar you're interested in, say 0
homology.CircularCoordinates.compute(metricSpace, 1.5, 0)
```

## Scala by extension

If you are not working interactively, one useful approach is instead to extend the `TDAlab` instance. That way it sets up your environment inside your extension for you. The resulting code may looks like this:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

object myComputation extends TDAlab(17) {
  // Task 1
  val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield ∆(a, b, c)
  val octahedronHomology = homology.SimplicialHomologyEngine().persistentHomology(
    streams.ExplicitStreamBuilder.fromFacets(triangles)
  )
  // (includes zero-length bars; see the REPL example above for dropping them)
  val octahedronBarcode = octahedronHomology.barcodeAt(4.0)

  // Common setup
  val metricSpace = io.CSV.readEuclideanMetricSpace("_docs/tutorials/examplepoints.csv")

  // Task 2
  val circleHomology = homology.SimplicialHomologyEngine().persistentHomology(
    streams.VietorisRips(metricSpace, maxDimension = 1)
  )
  val circleBarcode = circleHomology.barcodeAt(1.5)

  // Task 3
  val coordinate = homology.CircularCoordinates.compute(metricSpace, 1.5, 0)
}
```

## Scala with explicit imports

`TDAlab` is a convenience: it picks the coefficient field for you and brings a pile of names into scope. Nothing in it is
magic, and you can do without it. The version below uses no helper class at all. It imports each package it needs (with
`given`, which matters: a plain `*` import does **not** bring in Scala 3 `given` instances such as the ordering and
`OrderedCell` instances the engines look for), and it declares for itself the one thing `TDAlab` was choosing, the
coefficient field.

```scala
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}
import org.appliedtopology.tda4j.barcode.PersistenceFilter
import org.appliedtopology.tda4j.io.CSV

// The coefficients. FiniteField(17) is the field with 17 elements; importing its givens makes `field.Fp` a Field.
// (For floating point instead, drop these two lines and declare: given Double is Field = Field.DoubleApproximated(1e-9))
val field = FiniteField(17)
import field.given

// The engine is generic over vertex type, coefficient type and filtration type, so we name all three.
val engine = SimplicialHomologyEngine[Int, field.Fp, Double]()

// Task #1: a triangle takes one vertex from each antipodal pair (1,2), (3,4), (5,6); fromFacets adds the faces.
val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
val octahedron = engine.persistentHomology(ExplicitStreamBuilder.fromFacets(triangles))
PersistenceFilter.significant(octahedron.barcodeAt(4.0), minPersistence = Some(1e-9))

// Task #2
val metricSpace = CSV.readEuclideanMetricSpace("_docs/tutorials/examplepoints.csv")
val circle = engine.persistentHomology(VietorisRips(metricSpace, maxDimension = 1))
PersistenceFilter.significant(circle.barcodeAt(1.5).filter(_.dim <= 1), scale = Some(metricSpace.minimumEnclosingRadius))

// Task #3: circular coordinates need no engine and no field of ours (they work over their own prime, 47 by default)
CircularCoordinates.h1Bars(metricSpace, Some(1.5))
CircularCoordinates.compute(metricSpace, 1.5, 0)
```

What you gave up by not using `TDAlab`: the `∆(...)` literal (here `Simplex(...)`), the `Fp(...)` constructor, and chain
arithmetic, none of which this computation needed. What you gained: every name in the snippet is a name you can look up, and
the same imports work unchanged inside a library of your own.
