# Harmonic chains (2026-11-05)

Context: Mikael's "Harmonic chains next" (Bei Wang's work, deferred on 2026-11-05 until then). Related: Basu and Cox,
and Gülen, Mémoli, Wan and Wang. Reused from PR #270: the `Coefficients`/rationals, `Involution.Pair` bar cells,
`PersistenceBar.annotation` representatives.

Papers
- Hou, Parsa, Wang, "Tracking the persistence of harmonic chains" (SoCG 2025, arXiv 2412.15419): the harmonic chain
  barcode. The zigzag of harmonic chain groups `H_p(K_i) = ker ∂_p ∩ ker ∂_{p+1}ᵀ` along a simplex-wise filtration, joined
  by inclusions in either direction. Algorithm 1: a positive cell makes a new harmonic cycle (their Algorithm 3 `φ`);
  a negative cell ends the oldest alive chain `z` with `<z, ∂σ> != 0`, and every other affected `z_j` gets
  `z_j -= (α_j / α*) z_*`. O(m³) worst case. Bars are closed-open real intervals.
- Basu and Cox, "Harmonic persistent homology" (arXiv 2105.15170): `i^{s,t}` is the projection onto `B(K_t)^⊥` restricted
  to `H_p(K_s)`; `M^{s,t} = i^{-1}(image of H(K_{s-}))`, `N^{s,t} = M^{s,t-}`, `P^{s,t} = M ∩ N^⊥ ⊂ H_p(K_s)`; the
  representatives of the bars `[s, t)` are a basis of `P^{s,t}`. They depend on filtration values only.

Decisions
- Characteristic 0 only (reals with tolerance, `Coefficients.Rationals`), cycles only, degrees 0..maxDimension from a
  stream that holds the cells of dimension maxDimension + 1.
- HPW insertion order: value, then dimension, then the stream's order within a dimension (`insertionOrder`); `ofOrder`
  takes any order. The explicit stream lists tied cells in descending lexicographic order, so specs of tie-sensitive
  facts pass an explicit order.
- The birth chain is the unique element of `Z_p(K_{i+1}) ∩ Z_p(K_i)^⊥` with coefficient 1 on the birth cell: the
  reduction's V-column cycle, orthogonalised by Gram-Schmidt against an orthogonal basis of the cycles (no normalising,
  so exact over Q). The paper's coboundary-basis construction gives the same chain.
- Basu-Cox chains are computed in coordinates of the harmonic chains the HPW run keeps alive at the values involved
  (snapshots), with inner products with that basis instead of projections: membership questions are invariant under that
  change of coordinates. A bar of multiplicity one is scaled to 1 at its first cell; a group gets an orthogonal basis.
- Zero-length bars: dropped by default for the HPW barcode (repo convention, `includeZeroLength`); the Basu-Cox entry
  point has no such bars (they are not classes of the module) and the facades do not offer the option there.
- Facades: `TDA4j.harmonicChainBarcodeFromPoints/FromDistanceMatrix`, `harmonicRepresentativesFromPoints/...`, options
  `maxDimension`, `maxFiltrationValue`, `field` (R default, Q; Z is an error), `epsilon`, `includeZeroLength` (barcode),
  `minPersistence`, `minPersistenceFraction`, `threads`; the result is a `PersistenceResult` (default threshold as the other
  entry points). CLI `--harmonic chains|representatives` flows through the normal output; JS `harmonicChainBarcode`,
  `harmonicRepresentatives`; Python `harmonic_chain_barcode`, `harmonic_representatives` (+ `_from_distances`).

Oracles (nothing shared with the algorithm)
- The zigzag of harmonic chain groups: the number of bars containing `[i, j]` is `dim ∩_{k in [i,j]} V_k` (lim to colim is
  injective), multiplicities by inclusion-exclusion, exact Q matrices; random filtrations.
- Definition 11 of the paper for every representative: harmonic at every complex of the bar, born at the opening cell,
  not harmonic once the closing cell has entered.
- The paper's Figure 1 by hand: harmonic bars `[4,8), [5,9), [7,10)` against the ordinary `[4,9), [5,10), [7,8)`; the chains
  derived by hand (the oldest loop is `abd` with coefficients 1, 1, -1) and the hollow triangle (birth cell coefficient +1:
  {01: -1, 12: -1, 02: +1}; my first expectation (1, 1, -1) was wrong).
- Basu-Cox: Definition 3.10 in chain coordinates over Q for every pair of values (`HarmonicRepresentativesSpec.Oracle`); a
  bar's representatives must span exactly its `P`; a tie-heavy filtration; real against rational.
- Mutants: both sides the same subspace in `complement` (constructor throws, fine); the identity Gram matrix in
  `complement` survived the Figure 1 specs and is killed by the tie-heavy spec, so the true Gram matrix is kept (my
  claim that it was redundant was wrong); the Gram solve in `projection` was redundant (identity-Gram mutant survived) and
  was replaced by plain inner products.

Findings on the way
- `PersistenceResult.cycleVertices(i)` / `cycleCoefficients(i)` were two reads of `fromBars`' provider, each doing
  `collapseAll()` then `rawEntries`. The heap order after `collapseAll` is not the same on the second call (a
  `groupMapReduce` through a hash map re-heapified), so a coefficient landed on another cell's vertices: a harmonic chain
  of 16 cells came out with two coefficients swapped, no longer a cycle. Found because the facade spec compared the facade
  with the typed route. The provider reads `chain.terms` (cell order) now. The chains of the other engines were not
  seen to be affected (`RepresentativePairingSpec` checks that every degree-1 chain is a cycle for ripser, cohomology and
  naive on random clouds; it fails on the old code only for the harmonic chains).
- Finite fields (probe, 40 random 9-point clouds, degrees <= 1, `Run` over `Z/p`): p = 2, 3, 5, 7 failed on all 40
  (`IllegalStateException`: a closing cell finds no chain that is not orthogonal to its boundary; `ArithmeticException`:
  division by zero in Gram-Schmidt); p = 101 completed on 33 of 40; p = 10007 on 40 of 40; every completed run equalled the
  rational barcode. By hand: the unit square with one diagonal divides by zero over F_3 (`<z, z>` = 3 for a triangle
  boundary) and over F_2 keeps a "harmonic" boundary after both triangles have entered. No nondegenerate form helps for
  chain groups of dimension >= 3 (Chevalley-Warning). A large prime is a modular shortcut, not offered: a pivot
  that vanishes mod p without vanishing over Q can pair differently without an error.
- Births and deaths of the HPW barcode equal the ordinary barcode's (30 of 30 clouds, zero-length bars included).
- On Vietoris-Rips the HPW barcode differs from the ordinary one in 16 of 40 random 10-point clouds (degrees <= 1): an
  edge that starts a loop enters with the triangle that has it as longest edge; the triangle ends the OLDEST non-orthogonal
  chain, so a long ordinary bar is cut at that value. Seed 2: one ordinary loop is four consecutive bars; seed 4: ten. In 68
  of the 84 extra bars the death cell is a triangle containing the next bar's birth edge; the other 16 were not examined.
- Morse reduction (`DiscreteMorse(rips).stream`, 4176 of 7000 cells critical over those clouds) changed the answer: the
  harmonic barcode of the reduced complex was the ordinary barcode in 40 of 40, and differed from the full harmonic barcode in 16
  of 40. A chain homotopy equivalence does not keep the inner product. Only the sign of each cell is inner-product
  independent, so clearing and apparent pairs could classify cells for the harmonic run (the births and deaths) but not
  pair them.

Recipes
- sbt in this container needs the Maven mirror flags: `sbt -Dsbt.override.build.repos=true
  -Dsbt.repository.config=<file with local and gmirror: https://maven-central.storage-download.googleapis.com/maven2>`.
  Native scalafmt (`bash .claude/scripts/install-scalafmt.sh`) for formatting.
- `python3 .claude/scripts/tdalab-exports.py` rewrites `package.scala` and ignores arguments (`--help` regenerated it);
  it sorts `∆` last, the committed file has it first.

Limits and open items
- Memory is the orthogonal cycle basis (every cycle of a degree ever born, orthogonalised); cost cubic in the cells in the
  worst case. A down-Laplacian solve for the new chain (`(∂∂ᵀ) y = -∂σ`, `z = e_σ + ∂ᵀ y`) instead of the stored basis is an untested
  idea (memory over speed, Mikael 2026-10-24).
- No cocycle variant, no representative-type option; a persistent-Laplacian kernel as chains is a cheap follow-up; the
  Gülen-Mémoli-Wan poset construction is not built.

Issues filed: #304 (a large prime for harmonic chains, a decision), #305 (memory of the orthogonal cycle basis), #306 (VR
bars cut at apparent pairs; 16 of the 84 extra bars unexamined), #307 (cocycles, Laplacian kernel as chains, poset construction).

Checks run (2026-11-05): `tda4j/testFull` 2289 examples, no failures; `tda4jJS` the harmonic, exports and TDAlab specs
(68 examples) and then the whole suite; the Python suite against the assembly jar (114 tests, including
`test_facade_coverage.py`); native scalafmt `--check`.
