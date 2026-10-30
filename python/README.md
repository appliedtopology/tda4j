# tda4j for Python

Persistent homology and topological data analysis in Python, on the [TDA4j](https://tda4j.appliedtopology.org) library:
Vietoris-Rips, Cech, alpha, witness and cubical complexes, barcodes with a representative and the creating and destroying
cell of every bar, relative persistence, maps between barcodes, distances and vectorizations, and plots. NumPy arrays in
and out.

```python
import numpy as np
import tda4j

square = np.array([[0, 0], [1, 0], [1, 1], [0, 1]])
result = tda4j.persistence(square, max_dimension=1)

result.bars               # rows (dimension, birth, death); death is inf for a class that never dies
result.representative(4)  # the loop of the last bar: its edges and coefficients
result.bar_cells(4)       # the edge that opened the loop and the triangle that closed it
result.plot_barcode()     # a figure; shows itself in a notebook, .save("barcode.svg") writes it
```

Options are keyword arguments in snake_case and are the options of the library's MATLAB and Java interface:
`max_dimension=1`, `prime=3`, `complex="alpha"`, `field="Q"` for exact rational coefficients. By default the field is
Z/17 and the degrees are 0 to 2.

It runs the library in a JVM, which starts when the first function needs it, so Java 21 or newer has to be installed
(`JAVA_HOME` or `java` on the `PATH`). The wheel carries the library's jar; there is nothing to compile. To give the JVM
a bigger heap, call `tda4j.start_jvm(["-Xmx16g"])` before the first computation.

Documentation: <https://tda4j.appliedtopology.org/user-guide/python.html>. MIT license; source and issues:
<https://github.com/appliedtopology/tda4j>.

## From a source checkout

```
sbt tda4j/assembly                  # the fat jar, target/out/jvm/scala-*/tda4j/tda4j-*-assembly.jar
python/scripts/bundle-jar.sh        # copies it (and the license) into python/
pip install -e 'python[test]'
pytest python/tests
```

Without bundling, `TDA4J_JAR=/path/to/tda4j-assembly.jar` points the package at a jar.
