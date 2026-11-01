# Worklog: the dual of the witness complex (2026-11-01)

Mikael's question: a tutorial says the witness complex is the Dowker complex of the landmark-witness distance matrix;
what is the dual, and is it useful? The full discussion is a Claude Doc ("The dual of the witness complex",
https://claude.ai/artifact/T2mRbjRmavNjtHtYXHYLPP); this file keeps what the repo needs.

## The docs claim was wrong for tda4j

`f(sigma) = min_w max_{l in sigma} d(l, w)` on `WitnessGeometry.D` is Chazal-de Silva-Oudot's ambient Čech complex
Čech(L, W) (arXiv 1207.3885, Example 4.7). It is none of tda4j's witness complexes:

- lazy (`LazyWitnessStream`, JavaPlex tutorial §"Lazy witness streams", javaplex repo
  `reports/javaplex_tutorial/javaplex_tutorial.tex`): flag complex; with `nu = 0` it has the Dowker complex's edges and
  fills every triangle whose edges are there;
- general (`WitnessStream`, same tutorial): a k-simplex is relaxed by the (k+1)-th nearest landmark distance;
- CdSO's own Wit(L, W; a) (a-witnesses relative to the other landmarks) is a third construction.

All of them contain the Dowker complex at every t. Fixture that separates Dowker from lazy `nu = 0`: equilateral
triangle of side 1 as landmarks, edge midpoints as the other witnesses; Dowker has the H1 bar [1/2, sqrt(3)/2), lazy
none. Both, and a random-cloud check (Dowker value >= every witness variant's; lazy nu=0 value = max Dowker edge value),
are in `DowkerStreamSpec`. Fixed: `dowker-complexes.md`, `networks-and-relations.md`, `DowkerStream.scala` scaladoc,
`rules/filtered-complexes.md`; also the engines table's wrong reason for "not a flag complex" in
`persistence-engines.md` (a witness of a simplex does witness its edges; the converse fails).

## The dual (summary)

- Dual at t = sets of data points inside one landmark ball of radius t = intrinsic Čech of all the data with centres
  restricted to landmarks = union of n full simplices. The primal is the induced subcomplex of the full-data intrinsic
  Čech complex on the landmarks.
- `K_W(t) ⊆ Č(t) ⊆ K_W(t + R)` (R = covering radius), so with the functorial Dowker theorem the Dowker witness barcode
  is within bottleneck R of the full-data intrinsic Čech barcode (special case of CdSO Thm 5.6 with L' = W).
- Size (Python count, not tda4j; noisy circle N = 200, n = 15 maxmin, R = 0.355, dims <= 2): primal 37 / 63 / 104
  simplices at t = R / 1.5R / 2R, dual 20,332 / 66,677 / 146,226. Distinct witness signatures 30 / 30 / 28; the dual has
  <= 15 facets. Coordinatewise-dominated witnesses: 7 of 200; witnesses that are the best witness of some simplex of
  dim <= 2: 91 of 200.
- Verdict: never build it. Useful as a proof device and for lifting landmark representatives to data points (Dowker
  chain map via best witnesses on the barycentric subdivision; a bar longer than R gives a cycle non-trivial in the
  full-data Čech complex). Not implemented; offered to Mikael rather than filed.

Unverified (memory only, said so in the doc): Ball Mapper's exact definition, Perea's sparse circular coordinates
using the nerve map, "relaxed strong witness complex" as the name for the nu = 1 shifted Dowker complex.

## The lift, built (same day, Mikael: "let's build it")

- `DowkerGeometry.bestWitness(sigma)` (argmin column, lowest on ties) and `DowkerGeometry.lift(chain)`: psi(v) =
  bestWitness(v); psi(sigma) = cone(bestWitness(sigma), psi(boundary sigma)), memoized per simplex. This is sd followed
  by the vertex map barycentre -> best witness, so it is a chain map; every simplex of psi(sigma) is witnessed (column
  side) by a vertex of the bottom face of its flag within f(sigma). Sign: [apex, w_0 < ... < w_j] is
  (-1)^(#w_i < apex) times the sorted simplex, zero if apex is among the w_i.
- The lifted chain is built with the cells' intrinsic `Ordering[Simplex[Int]]`, not the input chain's: a stream chain
  may be ordered by that stream's filtration, which knows nothing of the columns.
- `Dowker.lift(diagram, relation, dual = false)`: bars unchanged, representatives lifted, `cells = None` (the bar's
  cells belong to the side built), refuses `Representatives.Cocycles` (cocycles need the map the other way: pull back
  along sd of the column complex, or the nearest-landmark vertex map with an R loss in the metric case; not built).
  `representativesDieWithTheirBars` is kept: psi(boundary c) = boundary psi(c) with psi(c) at the death value.
- Facade `representativesOn` (`rows`/`columns`, default the side built) on `computeFromRelation`, CLI
  `--representatives-on`, Python via the pass-through options. The bar cells and boundary matrix stay the built side's.
- `DowkerLiftSpec`: on random relations, both directions, over F_17: bars unchanged, lifted rep is a cycle, every cell
  in the other complex at birth, not a boundary at the last value before death, a boundary at death. Mutations checked:
  dropping the cone sign fails both properties; a fixed apex (column 0) fails them and the hexagon fixture.
