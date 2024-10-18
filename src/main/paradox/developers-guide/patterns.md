# Programming Patterns in use

@@ toc

## Type Class Pattern

We draw extensively on Scala's capacity for type classes to introduce additional capabilities to
(possibly pre-existing) classes. Thus, for instance, a `Chain` object is fundamentally handled as
a map with keys from some type `CellT` and values from some type `CoefficientT`. By requiring `CellT` 
to implement the `Cell` type class and `CoefficientT` to have a `Fractional` implementation, we can
guarantee that you can always call the `boundary` method on any cell and you can do any fractional
arithmetic with any coefficients.

As of the release of Scala 3.5.0, the type class pattern representation in Scala is a little bit in flux:
support is being introduced for a type member-based type class pattern similar to how type classes
are handled in Scala from a parametrized type style type system. This follows the conventions in use in
Rust and Swift. As a result, type classes currently can be handled **both ways**, and in this library
we will keep on using parametrized style access to the standard library type classes (such as `Ordering`),
but our own type classes will be predominantly written according to the new style and syntax, as introduced
in [SIP-64](https://docs.scala-lang.org/sips/sips/typeclasses-syntax.html) and [described in the Scala 3 documentation here](https://dotty.epfl.ch/docs/reference/experimental/typeclasses.html)

As an example of how this works out in practice, the `OrderedCell` type class is the fundamental building
block of the topological functionality, and is introduced as follows:

```scala 3
trait HasDimension:
  type Self
  extension (self : Self)
    def dim : Int

trait Cell extends HasDimension:
  type Self
  extension (self : Self)
    def boundary[CoefficientT : Field] : Chain[Self, CoefficientT]

trait OrderedCell extends Cell { type Self : Ordering as ordering }

given [CellT : OrderedCell as oCell] => Ordering[CellT] = oCell.ordering
```

This code block defines three type classes: `HasDimension`, `Cell` and `OrderedCell`, where `HasDimension`
introduces a method `dim` to the members of the typeclass, and `Cell` introduces a boundary method.
Finally, `OrderedCell` introduces a chosen ordering as an additional constraint, and also provides that
ordering as a declaration (in parametrized-style) for the standard library.

Being a type class declaration in the type member style boils down to having a type member called `Self`.
Then when context bounds are used (such as in `[CellT : OrderedCell]`), as of Scala 3.5.0, these are 
interpreted to force **either** the existence of some `OrderedCell[CellT]` or some instance of `OrderedCell`
where the type `Self` is exactly `CellT`. Both of these circumstances get recognized as fulfilling the
context bound by the compiler.

With parametrized type classes, you would use the syntax `Ordering[CellT]` to refer to a specific instance.
The corresponding notation with type member type classes is `OrderedCell { type Self = CellT }`, or as a
notational convenience, Scala also provides the `is` type alias that allows us to write `CellT is OrderedCell`.

With these type class definitions in place, an actual implementation of a specific type to fulfill the type
class can look like this:
```scala 3
given [VertexT: Ordering] => Simplex[VertexT] is OrderedCell:
    given Ordering[Simplex[VertexT]] = simplexOrdering
    
    extension (t: Simplex[VertexT]) {
      def boundary[CoefficientT](using fr: (CoefficientT is Field)): Chain[Simplex[VertexT], CoefficientT] =
        if (t.dim <= 0) Chain()
        else Chain.from(
          t.vertices
            .to(Seq)
            .zipWithIndex
            .map((vtx, i) => Simplex.from(t.vertices.toSeq.patch(i, Seq.empty, 1)))
            .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
        )
      def dim: Int = t.vertices.size - 1
    }
```

Here, the basic declaration is that whenever a type `VertexT` is a member of the `Ordering` typeclass
(so that there is somewhere an object of type `Ordering[VertexT]`), this piece of code declares for us
the membership of `Simplex[VertexT]` in the type class `OrderedCell`, by providing a canonically chosen
(using the `given` keyword) object of type `Simplex[VertexT] is OrderedCell`.

The definition of this object witnessing the type class membership follows: the code block that follows
is the body of an anonymous class instance of the type `Simplex[VertexT] is OrderedCell`, which by the
definition of `OrderedCell` has to provide:

1. An ordering for `Simplex[VertexT]` (because `Self : Ordering` was part of the definition of `OrderedCell`)
2. An implementation of `boundary[CoefficientT : Fractional]`.
3. An implementation of `dim`.

So the body of the membership declaration provides an ordering, and in an `extension` block, provides 
definitions of the methods `boundary` and `dim`.

And now, since the library provides `given` instances of the type class, the following code works as is:
```scala 3
Welcome to Scala 3.5.0 (17.0.9, Java OpenJDK 64-Bit Server VM).
Type in expressions for evaluation. Or try :help.
                                                                                                                                                                                                                   
scala> import org.appliedtopology.tda4j.*
                                                                                                                                                                                                                   
scala> ∆(1,2,3).boundary[Double]
val res0:
  org.appliedtopology.tda4j.Chain[org.appliedtopology.tda4j.Simplex[Int], Double
    ] = 1.0⊠∆(2,3) + -1.0⊠∆(1,3) + 1.0⊠∆(1,2)
                                                                                                                                                                                                                   
scala> ∆(1,2,3).boundary[Float]
val res1:
  org.appliedtopology.tda4j.Chain[org.appliedtopology.tda4j.Simplex[Int], Float] = 1.0⊠∆(2,3) + -1.0⊠∆(1,3) + 1.0⊠∆(1,2)
```


## Context Pattern

After seeing how Kotlin works we have also decided to draw extensively on _context-driven programming_;
we provide a number of contexts that include specific choices of things that need to be chosen, and
type aliases and notation to enable working with the chosen things. Hence, providing a `SimplexContext`
with a type parameter determines how `VertexT` will work throughout, and defines `Simplex` as a type
alias for `AbstractSimplex[VertexT]` as well as a convenience function `s` that allows the user to
very easily write explicit simplex instances.

Code that uses the simplex capabilities will likely want to start with the lines
```scala 3
given sc: SimplexContext[Int]()
import sc.{given,*}
```

At a top level we provide a meta-context `TDAContext[VertexT,CoefficientT]` that establishes the choices
of vertex type and coefficient type and imports convenience functions into the environment - a user of
the library will likely want to start their code with the lines
```scala 3
given tdac: TDAContext[Int, Double]()
import tdac.{given,*}
```

With all contexts in place (through the meta context), a user can write out an explicit chain as follows:
```scala 3
1.0 *> s(1,2) - 1.0 *> s(1,3) + 1.0 *> s(2,3)
```

This uses under the hood an implicit conversion from the `Simplex` type to the `Chain` type (a simplex is
a one-simplex chain with coefficient `1`).

### Finite Fields

We provide a finite field arithmetic implementation. To use this, create a `FiniteField` object and import
it's internal opaque type and `given` instances, and then you can use `Fp` to create finite field elements.
The resulting code looks something like:

```scala 3
val fp17 = FiniteField(17)
import fp17.{given,*}

Fp(1) *> s(1,2) - Fp(2) *> s(1,3) + s(2,3) <* Fp(3)
```

(note that we provide two versions of scalar multiplication: from the left by `*>` or from the right by `<*`)



