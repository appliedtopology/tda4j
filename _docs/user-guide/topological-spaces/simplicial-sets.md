---
layout: main
---

### Simplicial sets

For homology of a space presented combinatorially (not as a metric-space complex), build a
`FiniteSimplicialSet[G]` by giving each generator's faces directly. A minimal circle (one vertex, one loop
edge):

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.homology.{given, *}

given Double is Field = Field.DoubleApproximated(1e-9)

enum CircleGen { case V, E }
import CircleGen.*
given Ordering[CircleGen] = Ordering.by(_.ordinal)

val circle = FiniteSimplicialSet[CircleGen](
  generatorsByDim = IndexedSeq(Set(V), Set(E)),
  faces = {
    case V => IndexedSeq.empty
    case E => IndexedSeq(SSetElement(Nil, V), SSetElement(Nil, V)) // both faces of the loop are V
  }
)
circle.validate()   // Seq.empty -- no errors

given (CircleGen is OrderedCell) = circle.cellInstance
val stream = FilteredSimplicialSetStream[CircleGen](circle, { case V => 0.0; case E => 1.0 })
CellularHomologyEngine[CircleGen, Double, Double]()
  .persistentHomology(stream)
  .diagramAt(Double.PositiveInfinity)
// List((1, 1.0, Infinity), (0, 0.0, Infinity)) -- H0 = H1 = one essential class each, as expected for S^1
```

`FiniteSimplicialSet`'s companion object builds new simplicial sets from existing ones instead of by hand:
`FiniteSimplicialSet.product`/`.coproduct` (the categorical product/coproduct) and `.quotient`/`.identify`
(attaching maps — glue generators together, or collapse one down onto a lower-dimensional target).
`validate()` checks that hand-written or constructed face data actually satisfies the simplicial identities;
it's a necessary sanity check, not proof the resulting space is the one you intended.

### Ready-made spaces, constructions and cohomology operations

`SimplicialSets` collects the usual examples and constructions: `simplex`, `horn`, `sphere`, `kleinBottle`,
`complexProjectivePlane`, `hopfMap`, `fromSimplicialComplex` (from a list of facets), `presentationComplex` (from a group
presentation), and `cone`, `suspension`, `wedge`, `smash` and `join`. `SSetMap` is a simplicial map (with `mappingCone`),
`FundamentalGroup.presentation` reads a presentation of π₁ back off a connected set, and `CupProduct`/`Steenrod` compute
cup products and Steenrod squares (over F₂) on cochains. Everything here is checked against homology computed by hand,
and cup products are what tell spaces with equal Betti numbers apart. For example, `CP²` and `S² ∨ S⁴` have the same
Betti numbers, but only in `CP²` is the square of the degree-2 class nonzero:

```scala 3
import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

val cp2 = SimplicialSets.complexProjectivePlaneKuhnel   // the 9-vertex triangulation of Kühnel and Banchoff
val field = FiniteField(3)
import field.given

val x = CupProduct.cohomologyBasis[Simplex[Int], field.Fp](cp2, 2).head   // the generator of H^2
val xSquared = CupProduct.cup(cp2, 2, 2, x, x)
val nonzero = !CupProduct.isCoboundary(cp2, 4, xSquared)               // true: x^2 generates H^4
```
