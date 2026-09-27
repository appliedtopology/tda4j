### Simplicial sets

For homology of a space presented combinatorially (not as a metric-space complex), build a
`FiniteSimplicialSet[G]` by giving each generator's faces directly. A minimal circle (one vertex, one loop
edge):

```scala 3
enum CircleGen { case V, E }
import CircleGen.*
given Ordering[CircleGen] = Ordering.by(_.ordinal)

val circle = FiniteSimplicialSet[CircleGen](summon[Ordering[CircleGen]])(
  generatorsByDim = IndexedSeq(Set(V), Set(E)),
  faces = {
    case V => IndexedSeq.empty
    case E => IndexedSeq(SSetElement(Nil, V), SSetElement(Nil, V)) // both faces of the loop are V
  }
)
circle.validate()   // Seq.empty -- no errors

given (CircleGen is OrderedCell) = circle.cellInstance
val stream = FilteredSimplicialSetStream(circle, { case V => 0.0; case E => 1.0 })
CellularHomologyContext[CircleGen, Double, Double]()
  .persistentHomology(stream)
  .diagramAt(Double.PositiveInfinity)
// List((1, 1.0, Infinity), (0, 0.0, Infinity)) -- H0 = H1 = one essential class each, as expected for S^1
```

`FiniteSimplicialSet`'s companion object builds new simplicial sets from existing ones instead of by hand:
`FiniteSimplicialSet.product`/`.coproduct` (the categorical product/coproduct) and `.quotient`/`.identify`
(attaching maps — glue generators together, or collapse one down onto a lower-dimensional target).
`validate()` checks that hand-written or constructed face data actually satisfies the simplicial identities;
it's a necessary sanity check, not proof the resulting space is the one you intended.
