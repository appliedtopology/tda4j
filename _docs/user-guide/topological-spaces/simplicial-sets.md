---
layout: main
---

### Simplicial sets

A simplicial set describes a space combinatorially, by its non-degenerate simplices (the *generators*) and their
faces. Unlike a simplicial complex it can glue a simplex to itself: a circle is one vertex and one edge whose two ends
are that same vertex. Small models like this make it practical to compute the homology, cup products and Steenrod
squares of spheres, tori, projective spaces and classifying spaces of groups.

Simplicial sets are an add-on to the core library. Import both:

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*
```

(With a lab, `val tdalab = TDAlab(2); import tdalab.{*, given}` already covers both.)

Everything is in two places:

- **`SimplicialSet.<name>`** builds a space: from the catalog, from a list of facets, or by hand.
- **Methods of the space** do everything else: look inside it, build new spaces from it, compute its homology.

#### Ready-made spaces

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

val s2 = SimplicialSet.sphere(2)                 // one vertex, one 2-simplex
val t = SimplicialSet.torus                      // one vertex, three edges, two triangles
val k = SimplicialSet.kleinBottle
val rp3 = SimplicialSet.realProjectiveSpace(3)   // one cell per dimension
val cp2 = SimplicialSet.complexProjectivePlane   // Sage's minimal model; complexProjectivePlaneKuhnel is the 9-vertex one
val d3 = SimplicialSet.simplex(3)                // also: point, empty, horn(n, k)
val octahedron = SimplicialSet.fromSimplicialComplex(
  for a <- List(1, 2); b <- List(3, 4); c <- List(5, 6) yield Simplex(a, b, c)
)
val bz2 = SimplicialSet.classifyingSpace(FiniteGroup.cyclic(2))   // B(Z/2) = RP^∞: infinite, see below
```

`SimplicialSet.fromStream(stream)` turns any simplicial stream (a Vietoris–Rips complex, say) into a simplicial set,
and `SimplicialSet.presentationComplex(...)` builds the presentation complex of a group presentation.

#### Looking inside

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

val t = SimplicialSet.torus
t.dimension              // 2
t.fVector                // Vector(1, 3, 2): generators per dimension
t.generators(1)          // Vector(A, B, C): the non-degenerate edges, in order -- the cells homology sees
t.allGenerators          // Vector(Vertex, A, B, C, U, L)
t.faces(TorusGenerator.U)   // its d_0, d_1, d_2: the edges B, C, A, each as SSetElement(Nil, edge)
t.simplices(2).size      // 9: EVERY 2-simplex, degenerate ones (like s_0 A) included
t.eulerCharacteristic    // 0
t.isConnected            // true
t.validate()             // Seq.empty: the face data satisfies the simplicial identities
```

A simplex of a simplicial set is a generator under a *degeneracy word*: `SSetElement(word, generator)`, where
`word` lists the degeneracy indices in strictly decreasing order (`s_1 s_0 v` is `SSetElement(List(1, 0), v)`) and
`Nil` means the generator itself. `t.face(i, e)` and `t.degeneracy(j, e)` apply `d_i` and `s_j` to any simplex and
return it in that normal form.

#### Building your own

Give the generators per dimension and, for each generator of dimension `n > 0`, its `n + 1` faces:

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

enum CircleGen derives CanEqual { case V, E }
import CircleGen.*
given Ordering[CircleGen] = Ordering.by(_.ordinal)

val circle = SimplicialSet[CircleGen](
  IndexedSeq(Set(V), Set(E)),
  {
    case V => IndexedSeq.empty
    case E => IndexedSeq(SSetElement(Nil, V), SSetElement(Nil, V)) // both ends of the loop are V
  }
)
circle.validate()          // Seq.empty -- no errors
circle.bettiNumbers(2)     // Vector(1, 1)
```

`validate()` is a necessary check, not a sufficient one: it catches face data that breaks the simplicial identities,
not face data that describes a different space than you meant. Check the homology too.

#### New spaces from old

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

val circle = SimplicialSet.sphere(1)
val v = MinimalSphereGenerator.Vertex

val torus = circle.product(circle)            // fVector (1, 3, 2), Betti numbers (1, 2, 1)
val figureEight = circle.wedge(v, circle, v)  // Betti numbers (1, 2)
val s2 = circle.suspension                    // also: cone, join(other), smash(v, other, w)
val both = circle.coproduct(circle)           // disjoint union
```

`quotient(f)` sends each generator to its image (a surviving generator or a degenerate simplex over one — collapsing an
edge to a point needs the latter), `identify(pairs)` glues generators of equal dimension, and `subcomplex(keep)`
restricts to a face-closed set of generators.

#### Homology

`x.bettiNumbers(p)` is `dim H_n(X; F_p)` for every `n` up to the dimension of `X`. Using two primes shows most small
torsion: the Klein bottle has Betti numbers `(1, 2, 1)` over `F_2` but `(1, 1, 0)` over `F_3`, and `RP²` has
`(1, 1, 1)` over `F_2` but `(1, 0, 0)` over `F_3`:

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

SimplicialSet.kleinBottle.bettiNumbers(2)             // Vector(1, 2, 1)
SimplicialSet.kleinBottle.bettiNumbers(3)             // Vector(1, 1, 0)
SimplicialSet.realProjectiveSpace(2).bettiNumbers(3)  // Vector(1, 0, 0)
BettiNumbers(SimplicialSet.classifyingSpace(FiniteGroup.cyclic(2)), 3, 2)   // Vector(1, 1, 1, 1): an infinite set, up to degree 3
```

For persistent homology, give each generator a filtration value with `x.filtered(...)` (a generator may not appear
before its faces; this is checked), bring the set's cell structure into scope with `import x.given`, and use any
engine:

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

given Double is Field = Field.DoubleApproximated(1e-9)

enum CircleGen derives CanEqual { case V, E }
import CircleGen.*
given Ordering[CircleGen] = Ordering.by(_.ordinal)
val circle = SimplicialSet[CircleGen](
  IndexedSeq(Set(V), Set(E)),
  { case V => IndexedSeq.empty; case E => IndexedSeq(SSetElement(Nil, V), SSetElement(Nil, V)) }
)

import circle.given
CellularHomologyEngine[CircleGen, Double, Double]()
  .persistentHomology(circle.filtered { case V => 0.0; case E => 1.0 })
  .diagramAt(Double.PositiveInfinity)
// (0, 0.0, Infinity) and (1, 1.0, Infinity): H0 is born at 0, H1 when the loop closes at 1
```

#### Telling spaces apart: cup products, Steenrod squares, π₁

Equal Betti numbers prove little. `CP²` and `S² ∨ S⁴` have the same Betti numbers, but only in `CP²` is the square of
the degree-2 class nonzero:

```scala 3
import org.appliedtopology.tda4j.*
import org.appliedtopology.tda4j.sset.*

val cp2 = SimplicialSet.complexProjectivePlaneKuhnel   // the 9-vertex triangulation of Kühnel and Banchoff
val field = FiniteField(3)
import field.given

val x = CupProduct.cohomologyBasis[Simplex[Int], field.Fp](cp2, 2).head   // the generator of H^2
val xSquared = CupProduct.cup(cp2, 2, 2, x, x)
val nonzero = !CupProduct.isCoboundary(cp2, 4, xSquared)               // true: x^2 generates H^4
```

- `CupProduct` computes cup products of cochains (`Map[G, F]`): `cohomologyBasis`, `cup`, `isCocycle`, `isCoboundary`.
  Compare cochains with `isCoboundary`, never `==`.
- `Steenrod.sq(x, i, degree, c)` gives Steenrod squares over `F_2`.
- `FundamentalGroup.presentation(x)` reads a presentation of π₁ off a connected set.
- `SSetMap(source, target, onGenerators)` is a simplicial map, with `validate()`, `andThen`, `image`, `homologyRank` and
  `SSetMap.mappingCone`. `SimplicialSet.hopfMap` is the Hopf map `S³ → S²`; its mapping cone has the cohomology ring of
  `CP²`.

The tutorial [Telling spaces apart](../../tutorials/telling-spaces-apart.md) works through these.

#### Groups

`FiniteGroup` (`cyclic(n)`, `symmetric(n)`, `permutationGroup(...)`, `product(g, h)`) and `ClassifyingSpace` compute
group homology as the homology of `BG`. Filtering `BG` by a chain of subgroups gives *persistent* group homology; see
the tutorial [Persistent group cohomology](../../tutorials/persistent-group-cohomology.md). `BG` has `(|G| − 1)ⁿ`
generators in dimension `n`, so this is for small groups and low degrees.
