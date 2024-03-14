@@@ index
* [Class diagrams](class-diagrams.md)
* [Programming Patterns](patterns.md)
@@@

# Developers Guide for TDA4j

We intend the library to be a viable platform for algorithm development and research into topological data analysis.
This guide is intended to demonstrate how to understand the abstractions and class hierarchies, and to understand how
to extend the code to cover your use cases and fill your needs.

## Fundamental Paradigm: Context-driven programming

Drawing on experiences from the first year of the development work, a lot of the library is intended to work in a
paradigm called _context-driven programming_, which can be seen in action in the Kotlin library kmath.
The idea is that there are interfaces and objects that specify the _context_ in which your computation takes place,
and inside these contexts, symbols and functions are defined that make it natural to write the algebraic and arithmetic
operations that you might need.
The ideal end goal is that a user would need to run one or two lines of code to instantiate choices of what complexes
they are building and what coefficients they want to use for computation, and to load the corresponding functionality
into the current namespace.
Currently, this might look something like this:

```scala 3
import org.appliedtopology.tda4j.*
val tda = TDAContext[Char, Double]()
import tda.*

// now we are ready to write TDA code, and can create simplices and chains easily
// This code creates the chain 2[1,2] - [2,3]
val z = 2.0 *> s(1,2) - s(2,3)
```

Currently, there are a few more lines needed to set up a finite field context, but we aim to streamline the
top-level calling interface for this:

```scala 3
import org.appliedtopology.tda4j.*

val ff: FiniteField = tda4j.FiniteField(17)
import ff.{*,given}
val tda = TDAContext[Char, FiniteField]()

import tda.*

// now we are ready to write TDA code, and can create simplices and chains easily
// This code creates the chain 2[1,2] - [2,3]
val z = Fp(2) *> s(1,2) - s(2,3)
```
