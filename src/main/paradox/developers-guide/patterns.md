# Type Class Pattern

We draw extensively on Scala's capacity for type classes to introduce additional capabilities to
(possibly pre-existing) classes. Thus, for instance, a `Chain` object is fundamentally handled as
a map with keys from some type `CellT` and values from some type `CoefficientT`. By requiring `CellT` 
to implement the `Cell` type class and `CoefficientT` to have a `Fractional` implementation, we can
guarantee that you can always call the `boundary` method on any cell and you can do any fractional
arithmetic with any coefficients.

Where appropriate, this is implemented with F-bounded types (`CellT` needs to implement the `Cell` typeclass;
see <https://tpolecat.github.io/2015/04/29/f-bounds.html> and 
<https://dotty.epfl.ch/3.0.0/docs/reference/contextual/type-classes.html>) - or with `given` instances
following the Scala 3 pattern for implicit type classes (our `FiniteField` implementation comes with a 
`Fractional[Fp]` instance as an encapsulated `given` that can easily be imported).



# Context Pattern

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

## Finite Fields

We provide a finite field arithmetic implementation. To use this, create a `FiniteField` object and import
it's internal opaque type and `given` instances, and then you can use `Fp` to create finite field elements.
The resulting code looks something like:

```scala 3
val fp17 = FiniteField(17)
import fp17.{given,*}

Fp(1) *> s(1,2) - Fp(2) *> s(1,3) + s(2,3) <* Fp(3)
```

(note that we provide two versions of scalar multiplication: from the left by `*>` or from the right by `<*`)



