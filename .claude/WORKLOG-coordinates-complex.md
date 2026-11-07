# `complex` for circular and toroidal coordinates from the facades (#237), 2026-11-07

Point-in-time snapshot. #236 gave Scala `CircularCoordinates(diagram, input, bar, r, complex)` and `.toroidal(diagram, ...)`;
the MATLAB facade still ran the Vietoris-Rips complex of the points only.

## What was added

- `matlab.TDA4j`: `h1Bars(points, numLandmarks, complex)` and `(..., complex, prime)`; `circularCoordinates(points, r,
  cocycleIndex, prime, numLandmarks, complex)`; `toroidalCoordinates(points, r, cocycleIndices, prime, reduce, numLandmarks,
  complex)`. New overloads, so the shorter forms and their results are untouched. `"vr"` calls the shorter form (the
  metric-space route, reals, landmarks allowed). `"cech"` and `"alpha"` build `Persistence(points, maxDimension = 1, complex,
  characteristic = prime, representatives = Cocycles)` and read `CircularCoordinates.h1Bars(diagram)`, `CircularCoordinates(
  diagram, points, bar, r, complex)` or `.toroidal(...)`. Any other complex is refused naming the three that work;
  `numLandmarks > 0` with `"cech"` or `"alpha"` is refused (the sparse construction is the landmark Dowker complex, which
  has no other complex to choose). The result classes are the old ones.
- JavaScript: the option `complex` (text) on `h1Bars`, `circularCoordinates`, `toroidalCoordinates`, and `prime` on `h1Bars`.
  Python: `complex="vr"` on the three, `prime=47` on `h1_bars`. The CLI has no coordinates command, so nothing there.
- User guide: MATLAB, JavaScript and Python tables, and the facade paragraph of the circular coordinates page.

## Choices

- The loops of a Cech or alpha diagram are over `Z/prime`, not the reals the `"vr"` route uses, so `h1Bars` takes the same
  `prime` the coordinates take; their numbering can differ only through torsion, which no spec here has a cloud for (the
  `prime` argument of `h1Bars` is passed through, and not pinned by a value: Vietoris-Rips, Cech and alpha bars of a circle do
  not change with the field).
- Cech and alpha have the same barcode on points (the nerve theorem for the filtrations), so a spec cannot tell a facade that
  swaps them apart; the swap is the one mutant left unkilled, and it is equivalent as far as the bars go.

## Checks

`CircularCoordinatesComplexSpec` (16 examples, JVM and Scala.js): the bars equal those of the Scala diagram and differ from
Vietoris-Rips (radii against diameters: the loop is born below 0.75 of the diameter); the coordinates follow the true angle
of a noisy circle and equal the Scala entry point's value for value, for a chosen loop (bar 1 of a wedge of two circles),
for a chosen field (the result echoes `prime`), and toroidally on the wedge; the refusals. Seven facade mutants (route to the
Vietoris-Rips complex, the entry point called without the complex or with the wrong bar, the diagram built without the
complex or with the wrong prime, the landmark refusal removed) each fail an example; the JS exports and the Python wrappers
were mutated the same way (JS: `complex` ignored in `h1Bars` and in `circularCoordinates`; Python: ignored in each of the
three) and each fails a test. Python 116 tests pass against the assembly jar (JPype in a scratch venv); JS `ExportsSpec`
passes on Node 22.

## The TypeScript declarations

`npm/index.d.ts` was the one place the new options were missing (found in review: `h1Bars` took no options at all, and
`circularCoordinates` and `toroidalCoordinates` listed neither `complex` nor `numLandmarks`, which the JS exports had accepted
since PR #300 for the landmarks). It now has `CoordinatesComplex` (`"vr" | "cech" | "alpha"`) and the options of the three
functions, with the rule that `numLandmarks` is 0 for `"cech"` and `"alpha"`.

Checked by compiling a consumer with `tsc` 6.0.2 (`--strict`, `module: nodenext`) against the packed file placed as
`node_modules/@appliedtopology/tda4j/index.d.ts`: positive calls (`h1Bars(points)`, `h1Bars(points, { complex: "cech",
prime: 47 })`, `{ numLandmarks: 3 }`, `circularCoordinates` with `cocycleIndex`, `prime`, `complex` and `numLandmarks`,
`toroidalCoordinates` with `complex`, `reduce`, `prime`, `numLandmarks`) and three `// @ts-expect-error` lines (`complex:
"witness"`, a misspelled `numLandmark`, `cocycleIndex` on the toroidal function). It compiles clean against the new
declarations and fails with eight errors against the declarations of `origin/scala` (`has no exported member
'CoordinatesComplex'`, `Expected 1 arguments, but got 2`, the unknown properties). The fixture is not in the repository and
CI does not typecheck the declarations: #323.

