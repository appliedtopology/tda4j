# Harmonic chains: Laplacian solve (#305), cocycles, the kernel as chains, no zero-length bars (#307)

Snapshot of 2026-11-18. Builds on `WORKLOG-harmonic-chains.md` (the barcode, the Basu-Cox representatives).

## #305: `Orthogonalization.LaplacianSolve` instead of the stored basis

The positive-cell step of `HarmonicChainBarcode.Run` made the chain of the new cell orthogonal to an orthogonal basis of all
the cycles of the degree, kept (a dense chain per positive cell). `Orthogonalization` (`GramSchmidt`, the default;
`LaplacianSolve`) is the choice. The solve keeps only the boundaries of the cells of that dimension and a coface index: the
chain is `z = e_sigma + d^T y` with `(d d^T) y = -d e_sigma`, `d` the boundary of the cells of sigma's dimension that entered
before it (conjugate gradient; over the rationals it finishes exactly). It is the Gram-Schmidt chain: both are the one cycle
with coefficient 1 on sigma that is orthogonal to the older cycles.

**A/B** (`HarmonicBenchmarkDriver`, one strategy per JVM, serial GC, `-Xmx3g`, `taskset` on three cores, three trials, uniform
points in the unit square (radius 0.4, 0.3, 0.25, 0.22 for 60, 100, 150, 200 points; seed 1), Vietoris-Rips degree 1; `retained` = heap kept by the finished run after full GCs, minus before):

| points | cells | Gram-Schmidt | solve | retained, Gram-Schmidt | retained, solve |
|---|---|---|---|---|---|
| 60 | 3,497 | 1.21 s | 1.15-1.21 s | 14.5 MB | 2.8 MB |
| 100 | 6,643 | 7.4 s (7.3-7.7) | 4.9 s (4.9-5.1) | 55.2 MB | 12.2 MB |
| 150 | 11,763 | 31 s (28-33) | 18.0 s (17.9-19.2) | 138 MB | 20.0 MB |
| 200 | 16,198 | 98 s (95-99) | 51 s (50-55) | 288 MB | 60 MB |

The solve is faster from 100 points on and keeps 4 to 5 times less (the gap grows with the size). The machine had other
jobs on the fourth core and load above 8 during part of the runs; the pairs were run back to back.

**The traps.**
- *The solve's stopping rule has to be as strict as the field's zero test.* The conjugate-gradient residual first stopped at
  the field's `1e-9`, the same number the algorithm then used to decide whether an entry of the new chain is zero; the
  solve's error was of that size, so it left entries just above the threshold and the next test (`close`: is the boundary
  orthogonal to this chain?) answered differently from Gram-Schmidt: different bar counts. Now every residual entry must be
  zero after scaling by 2^10, i.e. 1e-12; the cap is `2*unknowns+50` iterations and the error says to use `Rationals` or
  `GramSchmidt`.
- *The two still pair a few bars differently over the reals on large near-tie clouds.* A different pairing, and so a different
  number of zero-length bars, which are dropped: at 60 and 80 points no difference; 100 points
  44 of 641 listing lines, 200 points 32 of 1,424; 70 and 90 points 476 against 471 and 534 against 532 bars. Every decision
  of the algorithm is a test of the form `isEqual(x, 0)` at an absolute `1e-9`, and a cycle that is almost orthogonal to a
  boundary is judged by rounding.
  **Exact rational reference** (Gram-Schmidt over `Coefficients.Rationals`, the arbiter): finished at 70 points only, see "The exact reference and the decision" below.

## #307

1. **Harmonic cocycles** (`HarmonicRepresentatives(.., representatives = Representatives.Cocycles)`). Cochains and chains share
   coordinates (orthonormal cells), so a harmonic cocycle is a vector on the same cells, and the harmonic cochains of a complex
   are its harmonic chains. The maps of the persistence module of cohomology go from a later value to an earlier one and are
   the orthogonal projections of the harmonic cochains, so the bar `[s, t)` is born (in cohomology) at the last value
   before `t`, and the Basu-Cox construction runs with the values reversed: `killedBy(level) = preimageOfImage(atTop, after,
   basisAt(level))`. The marker on the diagram records the kind. Facades: `representativeType` for the harmonic
   representatives; the harmonic chain barcode stays cycles only.
2. **The kernel of the persistent Laplacian as chains** (`Spectrum.kernel`): the right singular vectors of the transpose of the
   (padded) factor beyond its rank, `V` being exactly orthogonal; entries below `1e-12` are dropped, the sign makes the first
   nonzero entry positive. MATLAB `kernel()`, JavaScript `kernel`, Python `kernel` (shape `(betti_number, len(cells))`), the
   CLI `kernel-i=` lines.
3. The Gülen-Mémoli-Wan(-Wang) construction: `WORKLOG-persistent-laplacian-map.md`.
4. **No zero-length bars in `HarmonicRepresentatives`**: a bar that begins and ends at one value is not a class of the
   persistence module, so there is no Basu-Cox chain; `ofDiagram` throws, with a message that says to leave them out.

## Specs

`HarmonicCocycleSpec` (the two-loop example worked by hand: the cocycle of the long bar is the plain loop tilted away from the
second, that of the short bar the plain loop, the cycles the other way round; the dual definition over the rationals; ties),
`HarmonicOrthogonalizationSpec` (random orders over the rationals: bar for bar and term for term equal; tie-heavy Rips
orders), `PersistentLaplacianSpec` (kernel examples), the facade specs. Mutation checks of the solve and the cocycle branch
(each failed an example): dual top = death, larger/smaller swapped, `j == 0` empty, `after` empty, cocycles ignored, marker
always cycles, zero-length allowed, kernel rank offset, kernel never padded, kernel sign flipped, kernel threshold huge.

## Facades

`orthogonalization` (`gram-schmidt`, `laplacian-solve`) is a key of both harmonic facade entry points (MATLAB, JavaScript and
Python pass it through as an option; CLI `--orthogonalization`, harmonic only). `HarmonicFacadeSpec` and `HarmonicCLISpec` run both
over `Q` (bar for bar, cell for cell the same) and refuse a name that is neither; `python/tests/test_inputs.py` has the same example
(not run here: the container has no wheel and no assembly jar).

## The exact reference and the decision

The arbiter for the near-tie pairing is Gram-Schmidt over `Coefficients.Rationals` (#353). Rational Gram-Schmidt grows the
numerators with every positive cell, so it finished on one cloud only: 70 points (radius 0.37, 4,578 cells, 2,058 s on one core,
470 bars). The 90 and 100 point runs were stopped after 20 and 49 CPU minutes with no output.

On that cloud the real runs against the exact barcode (bars listed as degree, birth, death; zero-length bars dropped):

| run | bars | in the run, not in the exact barcode | in the exact barcode, not in the run |
|---|---|---|---|
| Gram-Schmidt, reals | 476 | 24 | 18 |
| Laplacian solve, reals | 471 | 3 | 2 |

So where the reference exists the solve is the closer one; Gram-Schmidt over the reals, whose basis accumulates rounding error
with every positive cell, mispairs about eight times as many bars. One cloud is one data point.

Decision: the default stays `GramSchmidt` in this PR. A default is a behaviour change for every existing real-coefficient
harmonic run, the evidence is one cloud, and the rational cost of the solve was not measured; the solve is the opt-in for memory
and, on this one cloud, for accuracy. The flip is the default value of `orthogonalization` in four places in
`HarmonicChainBarcode`, two in `HarmonicRepresentatives` and the facade's parse (`TDA4j.harmonic`); #353 holds the question.

Unverified: the stopping rule of the solve over the reals on clouds far larger than 200 points (the cap `2 * unknowns + 50`
iterations has not been hit in any run).

## Left

The poset-filtration Laplacian of #307 item 3 was read as the persistent Laplacian of a simplicial map (Mikael's decision);
the weights on the source other than 1 are not built (`WORKLOG-persistent-laplacian-map.md`).
