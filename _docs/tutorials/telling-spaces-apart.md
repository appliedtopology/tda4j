---
layout: main
title: Telling spaces apart
---

Persistent homology is built to read *data*, but the same machinery works on spaces you construct yourself, and that is where
you can ask pure questions: are these two spaces the same? What distinguishes them? The first tool is **Betti numbers**, the
counts of holes in each dimension. This tutorial starts with what they can do and then shows where they run out, and what to
reach for next: **cup products** and **Steenrod squares**, which detect structure the counting cannot see.

There is no data in this tutorial. Every space is built in a few lines, as a *simplicial set* (a space glued together from
simplices, with the freedom to glue them to themselves, which keeps the models tiny).

```scala sc:nocompile
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2
```

## Betti numbers over two fields

`space.bettiNumbers(p)` returns the dimensions of the homology groups of `space` with coefficients in the field with `p`
elements, `Vector(b0, b1, b2, ...)`. We ask for two fields, `p = 2` and `p = 3`, because comparing them exposes a subtle kind of hole
that counting over one field can hide:

```scala sc:nocompile
def betti[G](x: FiniteSimplicialSet[G]) = (x.bettiNumbers(2), x.bettiNumbers(3))

// A space can be described by a presentation: generators (loops), and relations (disks glued in along words in the loops).
// A letter is (generator number, +1 or -1). The torus is <a, b | a b a^-1 b^-1>: "going round a then b is the same as b then a".
val torus = SimplicialSet.presentationComplex(2, Seq(List((0, 1), (1, 1), (0, -1), (1, -1))))
val projectivePlane = SimplicialSet.presentationComplex(1, Seq(List((0, 1), (0, 1))))     // <a | a^2>
val kleinBottle = SimplicialSet.kleinBottle

betti(torus)             // (Vector(1, 2, 1), Vector(1, 2, 1))
betti(projectivePlane)   // (Vector(1, 1, 1), Vector(1, 0, 0))
betti(kleinBottle)       // (Vector(1, 2, 1), Vector(1, 1, 0))
```

The torus has one piece, two independent loops and one enclosed surface (`1, 2, 1`), whichever field you count in. The other two
have a twist and show it: the projective plane has `1, 1, 1` over the field with 2 elements but only `1, 0, 0` over the field with 3, so its extra
loop is a "double loop" (going round twice is trivial), which does not exist in odd characteristic. The Klein bottle is `1, 2, 1`
over 2 and `1, 1, 0` over 3, so the torus and the Klein bottle cannot be told apart by counting holes over the field with 2
elements, but can by comparing the two fields.

## Where counting runs out

Now the interesting case. Glue a sphere to two circles at a single point:

```scala sc:nocompile
val circle = SimplicialSet.sphere(1)
val twoCircles = circle.wedge(circle.generators(0).head, circle, circle.generators(0).head)
val sphere = SimplicialSet.sphere(2)
val notATorus = twoCircles.wedge(twoCircles.generators(0).head, sphere, sphere.generators(0).head)

betti(notATorus)         // (Vector(1, 2, 1), Vector(1, 2, 1))
```

It has exactly the same Betti numbers as the torus over both fields: one piece, two loops, one enclosed surface. And yet it is a different space: on the torus the two loops and the surface are tied together
(the surface is bounded by the loops going round each other), while on the wedge the surface floats free of the loops. Betti numbers cannot see that. **Cup products** can. A cup product multiplies
two cohomology classes: two classes in degree 1 give a class in degree 2. On the torus the product of the two loop classes is the
generator of the enclosed surface; on the wedge, every product of loop classes is zero.

```scala sc:nocompile
def basis[G](x: FiniteSimplicialSet[G], degree: Int) = CupProduct.cohomologyBasis[G, CoefficientT](x, degree)
def cup[G](x: FiniteSimplicialSet[G], p: Int, q: Int, a: Map[G, CoefficientT], b: Map[G, CoefficientT]) = CupProduct.cup(x, p, q, a, b)
def isNonzero[G](x: FiniteSimplicialSet[G], degree: Int, cochain: Map[G, CoefficientT]) = !CupProduct.isCoboundary(x, degree, cochain)

def someProductOfOneClassesIsNonzero[G](x: FiniteSimplicialSet[G]): Boolean =
  val classes = basis(x, 1)
  classes.exists(a => classes.exists(b => isNonzero(x, 2, cup(x, 1, 1, a, b))))

someProductOfOneClassesIsNonzero(torus)       // true
someProductOfOneClassesIsNonzero(notATorus)   // false
```

`basis` finds cochains whose classes span the cohomology in a degree, `cup` multiplies cochains, and a product is nonzero as a
class when it is not a coboundary (`isCoboundary` says whether it is the boundary-like kind that counts as zero). The torus has
a nonzero product; the wedge does not. The two spaces have the same *numbers* of holes, but different *ring structure* on them.

## Steenrod squares

Cup products are the first rung of a ladder of finer operations. Over the field with 2 elements, the **Steenrod squares** `Sq^i`
send a class of degree `n` to one of degree `n + i`; the top one, `Sq^n x`, is the cup square `x ∪ x`, but the lower ones are genuinely new
information. The next pair of spaces: the complex projective plane (the space of complex lines through the origin in complex 3-space,
a four-dimensional manifold), and a sphere of dimension 2 glued to one of dimension 4.

```scala sc:nocompile
val cp2 = SimplicialSet.complexProjectivePlaneKuhnel          // a 9-vertex triangulation of the complex projective plane
val sphere4 = SimplicialSet.sphere(4)
val s2s4 = sphere.wedge(sphere.generators(0).head, sphere4, sphere4.generators(0).head)

betti(cp2)               // (Vector(1, 0, 1, 0, 1), Vector(1, 0, 1, 0, 1))
betti(s2s4)              // the same

def squareOfTheTwoClassIsSq2[G](x: FiniteSimplicialSet[G]): Boolean = isNonzero(x, 4, Steenrod.sq(x, 2, 2, basis(x, 2).head))
squareOfTheTwoClassIsSq2(cp2)    // true
squareOfTheTwoClassIsSq2(s2s4)   // false
```

Again, equal Betti numbers: one piece, one surface class in degree 2, one in degree 4. On the complex projective plane the generator
of degree 2 squares to the generator of degree 4, `Sq^2` of it is nonzero; on the sphere pair the two classes never meet and the
square is zero. This is a classical fact, and here it is a one-line check.

On the real projective plane, `Sq^1` of the degree-1 class is nonzero. (For a class of degree 1, `Sq^1` is the cup square `x ∪ x`, so
this says the generator of degree 1 squares to the generator of degree 2.)

```scala sc:nocompile
val x = basis(projectivePlane, 1).head
isNonzero(projectivePlane, 2, Steenrod.sq(projectivePlane, 1, 1, x))    // true
```

## Building spaces from other spaces

Constructions are what make this a calculator rather than a catalogue. The complex projective plane is not just something you can look
up: it is what you get by gluing a four-dimensional disc onto a two-dimensional sphere along the **Hopf map** from the three-sphere,
that is, the *mapping cone* of the Hopf map. `SimplicialSet.hopfMap` is a simplicial model of that map, and `SSetMap.mappingCone` does the gluing:

```scala sc:nocompile
val hopfCone = SSetMap.mappingCone(SimplicialSet.hopfMap)
val hopfClass = basis(hopfCone, 2).head
isNonzero(hopfCone, 4, cup(hopfCone, 2, 2, hopfClass, hopfClass))    // true: it has the cohomology ring of the projective plane
```

So the construction really is the complex projective plane as far as cohomology is concerned: its degree-2 class squares to
a nonzero class. A map with a *constant* image instead gives the sphere pair, with square zero. The Hopf map is exactly the map for which the square is nonzero.

The catalog `SimplicialSet` also has `torus`, `realProjectiveSpace(n)`, `horn`, `simplex` and `fromSimplicialComplex` (for a
space given as a list of top-dimensional simplices, such as a triangulated surface); every space has the methods `cone`,
`suspension`, `join`, `smash`, `wedge` and `product`; and `FundamentalGroup.presentation` reads a presentation of the
fundamental group back off a connected space: the inverse of `presentationComplex`. The user guide's
[Simplicial sets](../user-guide/topological-spaces/simplicial-sets.md) page tours all of it.

## Limits

These are small, exact calculations on spaces with finitely many cells, over fields (the Betti numbers over `p = 2` and `p = 3`
see torsion in the integer homology but do not compute it, and the Steenrod squares are implemented for `p = 2` only). They have
no command-line or MATLAB entry point, since a simplicial set needs its own input format. For the persistent version of the
same idea, a filtration of such a space by subspaces, see [persistent group cohomology](persistent-group-cohomology.md).

## The whole script

```scala
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.TDAlab.F2.{*, given}  // a prebuilt lab: coefficients in Z/2

def betti[G](x: FiniteSimplicialSet[G]) = (x.bettiNumbers(2), x.bettiNumbers(3))
def basis[G](x: FiniteSimplicialSet[G], degree: Int) = CupProduct.cohomologyBasis[G, CoefficientT](x, degree)
def cup[G](x: FiniteSimplicialSet[G], p: Int, q: Int, a: Map[G, CoefficientT], b: Map[G, CoefficientT]) = CupProduct.cup(x, p, q, a, b)
def isNonzero[G](x: FiniteSimplicialSet[G], degree: Int, cochain: Map[G, CoefficientT]) = !CupProduct.isCoboundary(x, degree, cochain)

val torus = SimplicialSet.presentationComplex(2, Seq(List((0, 1), (1, 1), (0, -1), (1, -1))))
val projectivePlane = SimplicialSet.presentationComplex(1, Seq(List((0, 1), (0, 1))))
val kleinBottle = SimplicialSet.kleinBottle

val circle = SimplicialSet.sphere(1)
val twoCircles = circle.wedge(circle.generators(0).head, circle, circle.generators(0).head)
val sphere = SimplicialSet.sphere(2)
val notATorus = twoCircles.wedge(twoCircles.generators(0).head, sphere, sphere.generators(0).head)

def someProductOfOneClassesIsNonzero[G](x: FiniteSimplicialSet[G]): Boolean =
  val classes = basis(x, 1)
  classes.exists(a => classes.exists(b => isNonzero(x, 2, cup(x, 1, 1, a, b))))

val cp2 = SimplicialSet.complexProjectivePlaneKuhnel
val sphere4 = SimplicialSet.sphere(4)
val s2s4 = sphere.wedge(sphere.generators(0).head, sphere4, sphere4.generators(0).head)
def squareOfTheTwoClassIsSq2[G](x: FiniteSimplicialSet[G]): Boolean = isNonzero(x, 4, Steenrod.sq(x, 2, 2, basis(x, 2).head))

val x = basis(projectivePlane, 1).head
val sq1IsNonzero = isNonzero(projectivePlane, 2, Steenrod.sq(projectivePlane, 1, 1, x))

val hopfCone = SSetMap.mappingCone(SimplicialSet.hopfMap)
val hopfClass = basis(hopfCone, 2).head
val hopfSquareIsNonzero = isNonzero(hopfCone, 4, cup(hopfCone, 2, 2, hopfClass, hopfClass))

val results = (
  betti(torus), betti(notATorus), betti(projectivePlane), betti(kleinBottle), betti(cp2), betti(s2s4),
  someProductOfOneClassesIsNonzero(torus), someProductOfOneClassesIsNonzero(notATorus),
  squareOfTheTwoClassIsSq2(cp2), squareOfTheTwoClassIsSq2(s2s4), sq1IsNonzero, hopfSquareIsNonzero
)
```
