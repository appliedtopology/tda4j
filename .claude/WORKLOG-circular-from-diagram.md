# Worklog: circular and toroidal coordinates from a persistence diagram (#236)

2026-10-26, cloud session. Session transcript: `https://claude.ai/code/session_01PCJWFHCvHSyrZB9PbgMjFc`.
Point-in-time snapshot; the standing rules are in `rules/engines.md` ("From a diagram").

## What was asked

`CircularCoordinates.h1Bars/compute/computeToroidal` took only a `FiniteMetricSpace[Int]`: they rebuilt the
Vietoris-Rips complex and ran `CellularCohomologyEngine` twice (full filtration for the bars, `K_r` for the cocycle),
with a separate `prime`. A Cech, alpha, witness or stream diagram could not be used. The request: take any persistence
diagram object, since the verb can always give cocycles (`Involution`), and keep the point-cloud entry point working.

## What was built

- `CircularCoordinates(diagram, input, bar = 0, r = <middle of the bar>, complex = VietorisRips)`,
  `CircularCoordinates.toroidal(diagram, input, bars, r, complex, reduce)`, `CircularCoordinates.h1Bars(diagram)`.
  `input` is a `Persistence.Input[Simplex[Int]]` (points, metric space, stream: the verb's `into` types).
- `PersistenceDiagram.representatives: Option[Representatives]`, set by `Persistence` (the four construction sites in
  `Persistence.scala`), carried by `dim`/`at`/`significant`/`longerThan`; `None` for hand-built diagrams and cursor
  snapshots. This is what lets the code tell a cocycle from a cycle: both are `Chain[Simplex[Int], C]` on edges, and
  guessing from the support is unsafe (a cycle of a square with no triangles is also a cocycle).
- The shared tail of the old path: `harmonicSmooth(edges, z)` (the public `harmonicSmoothOnComponent` delegates),
  `verifyIntegerCocycleOn(triangles, z, prime)`, `combineClasses(...)` (Gram, LLL, new theta). `compute(metricSpace, ...)`
  and `computeToroidal(metricSpace, ...)` keep their own front half (VR via `CellularCohomologyEngine`), so their
  results, including tie order among equally persistent bars, are unchanged; the existing specs pass untouched.
- The field comes from the diagram: the characteristic by repeated addition (`p * 1 == 0`, 0 if it does not wrap within
  `FiniteField.MaxPrime`), the integer lift by a table of `k * 1`, `|k| <= (p-1)/2`, keyed by `Field.hash`. No change to
  `Field`. Characteristic 0 and 2 are refused with a message that says what to do.
- `K_r` is `input.stream(1, Some(r), complex)` cut by `AtValue` (value <= r, dimensions <= 2) so a user's whole
  stream works; only edges and triangles are read.

## The cocycle from a diagram

For a finite bar `(sigma, tau)` the cocycle `V_sigma` has oldest cell `sigma` and `delta V_sigma = R_sigma` starts at the
death cell, so its restriction to `K_r`, `birth <= r < death`, is a cocycle. It pairs non-trivially with the bar's cycle
(the supports meet only in `sigma`). Measured, not assumed: every engine that gives cocycles (`Ripser`, `ParallelRipser`,
`Cohomology`, `Chunks`, `Naive`) passes the exact integer cocycle check on `K_r` and follows the true angle on a noisy circle.
A mismatch with the input surfaces as a mod-p coboundary error naming the triangle (`verifyIntegerCocycleOn` separates it
from `NoIntegerCocycleException`, which stays for torsion or a prime that is too small).

A diagram of cycles cannot give cocycles by `Involution` alone: the diagram drops zero-length bars, whose birth cells are
pivots for the others, and does not store the pairing. So its class is recovered by the cohomology of `K_r` over the
diagram's field, matched to the bar by birth (tolerance 1e-9 relative; two essential classes born at the same value is
an error, as in `compute`).

## Finding: a bar's class is defined only modulo the shorter bars nested in it

The first torus test failed: reduced Gram matrix from a Ripser-cocycle diagram `[[8.37, -0.67], [-0.67, 7.86]]` against
`[[6.60, 2.10], [2.10, 8.62]]` from `computeToroidal` (the cycles route, which recovers with the same cellular engine,
matched exactly). At the chosen `r = 1.24` a third loop (bar `[0.72, 1.39)`) was alive. In the cohomology persistence
module the generator of bar `[b, d)` can absorb any class of a bar `[b', d')` with `b' >= b` and `d' <= d` and still
be a valid decomposition, so with nested bars alive the coordinates of an engine's "bar 0" differ between engines, all
valid. At `r = 1.5`, where only the two long loops are alive, the two lattices coincide and the reduced Gram matrices
agree to 1e-6 for cycles and cocycles. The spec picks `r` that way; the user guide says to choose `r` where only the
loops you ask for are alive. This already held for `compute` (different engines, different representatives) and is
not new, but it was undocumented.

## Not done

- MATLAB `circularCoordinates(points, r)` still takes points and uses VR; a `complex` choice there is a follow-up (issue
  linked from #236). No CLI command for circular coordinates exists.
- The tutorial page `circular-and-toroidal-coordinates.md` is unchanged (its numbers are asserted by a spec); the new
  entry point is in the user guide.
- Not run locally: `sbt tda4jJS/test`, `sbt doc` (the user-guide fence was compiled and run as a throwaway spec), MiMa
  (0.7.0 starts a fresh baseline). CI's jobs are the check.
