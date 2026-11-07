### Build, CI and Documentation

- The "Coming from JavaPlex, Ripser or GUDHI" page is checked against JavaPlex 4's source, `ripser.cpp` and GUDHI 3.13: JavaPlex has no alpha complex and no integer coefficients, zero-length bars and the default threshold are stated for JavaPlex and GUDHI, `--modulus` needs Ripser's `USE_COEFFICIENTS` build, and the Ripser `dipha` format is listed. The Euler curve section says when a persistence diagram gives the curve: a complete one does (the alternating count of the bars alive at each scale), one cut at a degree does not (#159, #248).
