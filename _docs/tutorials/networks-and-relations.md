---
layout: main
title: Networks and relations
---

Not all data is a cloud of points in space. A great deal of it is a table of *relationships*: which people belong to which
clubs, which genes respond to which drugs, which users bought which products, how strongly. There is no distance between two people
in such a table, and the two axes are not even the same kind of thing. Persistent homology can still read it, through the **Dowker
complex**, and this tutorial builds one from a tiny membership table.

If you have only seen point clouds so far, the one idea to take in is this: **a group of rows forms a simplex when some column
relates to all of them**. Rows that share a column are linked, and the shape of the resulting complex is the shape of the
overlaps in your table.

## The data

Six people and seven clubs. Person `i` belongs to club `i` as their main club and to the next club `i + 1` (round the ring, so
person 5 also belongs to club 0) as a second club; club 6 is a large social club to which everyone belongs. Each membership has
a *cost*: the main club costs 1, the second club 2, the large club 5, and a missing entry is `Infinity` (no relationship, ever).
Smaller means a stronger tie, as a distance would:

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val never = Double.PositiveInfinity
val relation: Array[Array[Double]] = Array.tabulate(6, 7) { (person, club) =>
  if club == 6 then 5.0
  else if club == person then 1.0
  else if club == (person + 1) % 6 then 2.0
  else never
}
```

The table has 6 rows and 7 columns: nothing requires it to be square or symmetric, and the two axes can be entirely different sets.

## The Dowker complex of the people

A set of people becomes a simplex at time `t` as soon as there is a club that every one of them is in at cost at most `t`. A single
person appears when their cheapest club does (here at 1). Two neighbours around the ring, person `i` and person `i + 1`, share club
`i + 1`, at cost `max(2, 1) = 2`, so they are joined at time 2. Three people never share a club except through the large
club, at time 5.

```scala sc:nocompile
val engine = SimplicialHomologyEngine[Int, CoefficientT, Double]()
def dowkerBars(dual: Boolean) =
  engine.persistentHomology(Dowker(relation, maxDimension = 1, dual = dual)).diagramAt(Double.PositiveInfinity).filter(_._1 <= 1)

val people = dowkerBars(dual = false)
people.size    // 16 bars
```

Sixteen bars, nine of them born and dead at the same instant (the same harmless zero-length bars as in the
[first tutorial](find-a-loop.md)). Dropping those:

```scala sc:nocompile
def withoutZeroLength(bars: List[(Int, Double, Double)]) =
  bars.filter((_, birth, death) => death.isInfinite || death > birth).sortBy(bar => (bar._1, bar._2, bar._3))

withoutZeroLength(people)
// (0, 1.0, 2.0) five times, (0, 1.0, Infinity), (1, 2.0, 5.0)
```

* The six people start as six separate components at 1, and five of those bars end at 2 as neighbours get joined: one
  component survives (the essential bar `(0, 1.0, Infinity)`).
* **One loop, `(1, 2.0, 5.0)`**. At time 2 the six people are joined into a ring of friendships (each person shares a club with
  the next), but no club contains three people from the ring, so the ring is hollow. It is filled in at time 5, when the large
  club relates everyone at once.

So the table says: a chain of overlapping circles of acquaintance, around which nobody is a common member, until the big
club. That is the same picture as the circle in the earlier tutorials, found in a table instead of in space.

## The other side of the table

A table has two sides. We built the complex on the *people*, using clubs as witnesses; we can equally build it on the *clubs*,
using people as witnesses. `Dowker(..., dual = true)` does exactly that:

```scala sc:nocompile
val clubs = dowkerBars(dual = true)
clubs.size    // 13 bars, not 16
withoutZeroLength(clubs) == withoutZeroLength(people)    // true
```

There are 13 bars here against 16 for the people (the two sides have different numbers of zero-length bars, which carry no
information), yet after dropping them the barcodes are **exactly the same**. This is **Dowker duality**, a theorem: the complex of
the rows and the complex of the columns of a relation have the same persistent homology. In practice it is a freedom: build
the complex on whichever side is smaller (people or clubs, genes or drugs) and you get the same loops. It is also a good check on
your data pipeline: if the two sides ever disagree, something is wrong with the table.

## On your own tables

* A **boolean** relation (member or not, no strengths) is the special case where every cost is 0 or never;
  `DowkerGeometry.fromBoolean` builds one for you, and the barcode degenerates to the classical Dowker complex, a plain
  simplicial complex with no filtration.
* A **distance matrix between two different sets** is a perfectly good relation, and so is the matrix of distances from
  landmarks to witnesses (that is the witness complex, see [Choosing a complex](choosing-a-complex.md)).
* The command line reads a relation from a CSV file with `--input-format csv-relation` (and `--dual` for the other side), and
  MATLAB has `computeFromRelation`; both are described in the [user guide](../user-guide/topological-spaces/dowker-complexes.md).

## The whole script

<div class="tabset">
<div class="tab" data-lang="Scala">

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab

val lab = TDAlab(2)
import lab.{*, given}

val never = Double.PositiveInfinity
val relation: Array[Array[Double]] = Array.tabulate(6, 7) { (person, club) =>
  if club == 6 then 5.0
  else if club == person then 1.0
  else if club == (person + 1) % 6 then 2.0
  else never
}

val engine = SimplicialHomologyEngine[Int, CoefficientT, Double]()
def dowkerBars(dual: Boolean) =
  engine.persistentHomology(Dowker(relation, maxDimension = 1, dual = dual)).diagramAt(Double.PositiveInfinity).filter(_._1 <= 1)
def withoutZeroLength(bars: List[(Int, Double, Double)]) =
  bars.filter((_, birth, death) => death.isInfinite || death > birth).sortBy(bar => (bar._1, bar._2, bar._3))

val people = dowkerBars(dual = false)
val clubs = dowkerBars(dual = true)
val agree = withoutZeroLength(people) == withoutZeroLength(clubs)
```

</div>
<div class="tab" data-lang="MATLAB">

```matlab
javaaddpath('target/out/jvm/scala-3.9.0/tda4j/tda4j-0.5.0-SNAPSHOT-assembly.jar');   % from the repository root, after sbt assembly
import org.appliedtopology.tda4j.matlab.*;

% 6 people (rows) and 7 clubs (columns); Inf means 'never a member'
relation = Inf(6, 7);
for person = 0:5
    relation(person + 1, person + 1) = 1;                 % the person's own club, from time 1
    relation(person + 1, mod(person + 1, 6) + 1) = 2;     % the next person's club, from time 2
end
relation(:, 7) = 5;                                       % a club everyone joins at time 5

people = TDA4j.computeFromRelation(relation, {'maxDimension', '1'});
clubs  = TDA4j.computeFromRelation(relation, {'maxDimension', '1', 'dual', 'true'});

people.toArray()        % seven bars, one of them the loop [1 2 5]
clubs.toArray()         % exactly the same seven bars
size(people.toArrayUnfiltered(), 1)    % 16 bars before the zero-length ones are hidden
size(clubs.toArrayUnfiltered(), 1)     % 13
```

</div>
</div>

`computeFromRelation` takes the relation as a matrix, with `Inf` for pairs that are never related; `dual` selects the transposed relation. `MatlabTabsSpec` makes these same calls from Scala and checks every number quoted in the MATLAB tab; MATLAB itself is not run by the test suite.
