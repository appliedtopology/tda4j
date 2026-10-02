---
layout: main
title: All the ways to call TDA4j
---

There are several ways to access the methods in the TDA4j library, depending on how much code you want to write, what systems you want to interact with, and what you are ready to learn. In the following snippets we will show off the primary ways available. Some set-up and tasks:

1. We want to build an empty octahedron by hand and compute its homology.
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
java -jar target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar -r --max-dimension 1 examplepoints.csv
```

Task 3 is not possible from the command line.

## Matlab

Calling from Matlab, we can handle both tasks. I think? Need to work out details and fill in this example here. Maybe delegate.

## Scala REPL

When working in a Scala interpreter, where you may want to go step by step, we have set up the `org.appliedtopology.tda4j.TDAlab` class, so that importing from an instance of the class gets you a fairly comprehensive setup with some default choices made for you.

Some techniques we use to make the code easier to use are fairly experimental -- with the result that we currently cannot avoid one pure boilerplate line of code: telling Scala that we are okay with specific experimental features. The rest of this example instantiates the `TDAlab` (by picking a prime number, or 0 for computing with `Double` coefficients, in which case you can also use a `precision` argument to pick precision for equality tests):

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = new TDAlab(17)
import lab.{*,given}

// Task #1
val stream = streams.ExplicitStreamBuilder()
  .addOne((0.0, ∆(1)))
  .addOne((0.1, ∆(2)))
  .addOne((0.2, ∆(3)))
  .addOne((0.3, ∆(4)))
  .addOne((1.0, ∆(1,2)))
  .addOne((1.1, ∆(1,3)))
  .addOne((1.2, ∆(1,4)))
  .addOne((1.3, ∆(2,3)))
  .addOne((1.4, ∆(2,4)))
  .addOne((1.5, ∆(3,4)))
  .addOne((2.0, ∆(1,2,3)))
  .addOne((2.1, ∆(1,2,4)))
  .addOne((2.2, ∆(1,3,4)))
  .addOne((2.3, ∆(2,3,4)))
  .result()
val homologyComputation = homology.SimplicialHomologyEngine().persistentHomology(stream)
homologyComputation.barcodeAt(4.0)

// Task #2
val metricSpace = io.CSV.readEuclideanMetricSpace("examplepoints.csv")
val homologyComputation2 = homology.SimplicialHomologyEngine().persistentHomology(streams.IncrementalVietorisRipsSimplexStream(metricSpace, maxDimension=1))
homologyComputation2.barcodeAt(1.5)

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
    val stream = streams.ExplicitStreamBuilder()
      .addOne((0.0, ∆(1)))
      .addOne((0.1, ∆(2)))
      .addOne((0.2, ∆(3)))
      .addOne((0.3, ∆(4)))
      .addOne((1.0, ∆(1,2)))
      .addOne((1.1, ∆(1,3)))
      .addOne((1.2, ∆(1,4)))
      .addOne((1.3, ∆(2,3)))
      .addOne((1.4, ∆(2,4)))
      .addOne((1.5, ∆(3,4)))
      .addOne((2.0, ∆(1,2,3)))
      .addOne((2.1, ∆(1,2,4)))
      .addOne((2.2, ∆(1,3,4)))
      .addOne((2.3, ∆(2,3,4)))
      .result()
    val homologyComputation = homology.SimplicialHomologyEngine().persistentHomology(stream)
    val barcode = homologyComputation.barcodeAt(4.0)
  
    // Common setup
    val metricSpace = io.CSV.readEuclideanMetricSpace("examplepoints.csv")

    // Task 2
    val homologyComputation = homology.SimplicialHomologyEngine().persistentHomology(streams.IncrementalVietorisRipsSimplexStream(metricSpace, maxDimension=1))
    val barcode = homologyComputation.barcodeAt(1.5)

    // Task 3
    val coordinate = homology.CircularCoordinates.compute(metricSpace, 1.5, 0)
}
```
