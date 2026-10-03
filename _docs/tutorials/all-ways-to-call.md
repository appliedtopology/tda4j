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
octahedron.toArray()           % rows [dimension birth death]: one [0 0 Inf], one [2 1 Inf]

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

Every Scala file that uses TDA4j starts with two lines. The first accepts the experimental language features the library
is built with (its typeclasses use them, so Scala asks every user file to opt in); the second brings in the library:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

// Task 1: the vertices are 1..6, and 1-2, 3-4, 5-6 are the antipodal pairs, which are never joined.
// A triangle takes one vertex from each pair, so the 8 triangles are all we have to list: fromFacets adds
// the 12 edges and 6 vertices they contain, all at filtration value 0.
val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
val octahedron = Persistence(ExplicitStreamBuilder.fromFacets(triangles), maxDimension = 2)
octahedron.bettiNumbers     // Vector(1, 0, 1): one component, no loops, one void

// Task 2: the Vietoris-Rips diagram in degrees 0 and 1, up to scale 1.5
val points = CSV.readPointCloud("_docs/tutorials/examplepoints.csv")
val circle = Persistence(points, maxFiltrationValue = 1.5)
circle.longerThan(0.3)      // the component [0, Infinity) and the loop [0.261, Infinity), still open at 1.5

// Task 3: circular coordinates, for the loop alive at 1.0
val metricSpace = EuclideanMetricSpace(points)
CircularCoordinates.h1Bars(metricSpace, 1.5)       // the loops, longest first: (0.261, Infinity) up to 1.5
CircularCoordinates.compute(metricSpace, 1.0, 0)   // an angle in [0, 1) for every point
```

For interactive work with chains, a *lab* is a one-import setup that also fixes a coefficient field and brings chain
arithmetic: `import org.appliedtopology.tda4j.TDAlab.F17.{*, given}` (also `TDAlab.F2`, `TDAlab.F3`, `TDAlab.Reals`, or
`val lab = TDAlab(p); import lab.{*, given}` for any prime `p`). With it, `∆(1, 2, 3)` is a simplex, simplices are chains,
and `Fp(2) ⊠ ∆(1, 2) - ∆(2, 3)` is a chain over the field with 17 elements:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F17.{*, given}

val chain = Fp(2) ⊠ ∆(1, 2) - ∆(2, 3)
println(chain.show)
```

## Scala by extension

If you are not working interactively, one useful approach is instead to extend a lab, which sets up the environment
inside your object:

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

object myComputation extends TDAlab(17) {
  // Task 1
  val triangles = for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield ∆(a, b, c)
  val octahedron = Persistence(ExplicitStreamBuilder.fromFacets(triangles), maxDimension = 2)

  // Task 2
  val points = CSV.readPointCloud("_docs/tutorials/examplepoints.csv")
  val circle = Persistence(points, maxFiltrationValue = 1.5).longerThan(0.3)

  // Task 3
  val coordinate = CircularCoordinates.compute(EuclideanMetricSpace(points), 1.0, 0)
}
```

## An engine by hand

`Persistence` picks the engine and the coefficient field for you (the field with 17 elements, unless you pass
`characteristic`). Running an engine yourself means choosing the field, and in exchange you get the engine's cursor: a
computation you can advance in steps or in slices of time, and query at any scale while it runs.

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

// The coefficients. FiniteField(17) is the field with 17 elements; importing its givens makes `field.Fp` a Field.
// (For floating point instead: given Double is Field = Field.DoubleApproximated(1e-9))
val field = FiniteField(17)
import field.given

val metricSpace = CSV.readEuclideanMetricSpace("_docs/tutorials/examplepoints.csv")
val state = SimplicialHomologyEngine[Int, field.Fp, Double]().persistentHomology(VietorisRips(metricSpace, maxDimension = 1))
state.advanceTo(1.0)                        // process the complex up to scale 1.0
state.barcodeAt(1.0).filter(_.dim <= 1)     // the diagram at 1.0: the loop is alive, [0.261, 1.0]
```

The stream for degrees 0 and 1 contains the triangles too, which is how a loop can be filled in; the engine reports the
degree-2 bars of that stream as well, and they are incomplete, hence the filter. `Persistence` does that for you.
