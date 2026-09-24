# Worklog: gap analysis vs. JavaPlex/Dionysus/Gudhi/Dipha/Ripser/OAT, and follow-up decisions

2026-09-24. Planning/analysis session, no code written (working tree confirmed clean at hand-off, branch `scala`).
Written specifically to survive a session migration (to the cloud) intact, since the decisions below only existed
in chat before this file. Session transcript: `https://claude.ai/code/session_016YAbFpuKZgMv6jHj7qgbqv`.

## Starting question and method

Asked: what do mainstream PH libraries have that tda4j doesn't. Answered by reading `CLAUDE.md`'s architecture
section plus `grep`-verifying absence of key terms (bottleneck/wasserstein/zigzag/vineyard/circular-coordinate/
extended-persist/multi-field/discrete-morse/coreduction/edge-collapse — all confirmed absent from `src/`). Full
candidate list from that pass (kept here for completeness, not all pursued): zigzag persistence, vineyards,
extended persistence + relative homology, multi-field simultaneous computation, bottleneck/Wasserstein distance,
diagram vectorizations, flag-complex edge collapse, discrete Morse coreduction, distributed (MPI) computation,
GPU acceleration, optimal/minimal representative cycles (OAT), circular/toroidal coordinates (JavaPlex), Python
bindings, PHAT/sparse-triplet I/O.

**Two follow-up advisor passes corrected real errors in the first draft of this analysis** — recorded below
inline, not just the final state, because the corrections are informative in their own right (this is exactly
the "measure/verify, don't infer" discipline (`[[tda4j_measure_dont_infer]]`) applied to literature claims, not
just benchmarks).

## Decisions (user's calls, in order given)

### 1. Boundary-matrix export — YES; PHAT reader/writer — NO (see item 7)

`PersistenceResult` was **already designed for this**: `WORKLOG-matlab-api.md`'s "deliberately out of scope"
section says boundary-matrix export is "a second closure alongside the existing cycle-provider closure, not a
redesign, when it's wanted" — i.e. add a lazy `Int => (...)` closure next to the existing `cycleProvider` field
(`PersistenceResult.scala:31`), not a new API shape. Output as an `(int[] rows, int[] cols, double[] values)`
triplet, matching the facade's existing type discipline (primitives/`String`/`double[][]`/`String[]`, plus
`int[]` already precedented by the witness two-step API's landmark indices) — rebuildable MATLAB-side as
`sparse(i,j,v)`. This is the one piece of plumbing that unblocks delivering *any* of the optimization-heavy asks
(circular coordinates, optimal cycles) to a MATLAB caller, since that path is currently blocked on exactly this
("Boundary-matrix export designed, not implemented" — `CLAUDE.md`, MATLAB API section).

Needs the CLAUDE.md four-surface treatment once built (MATLAB dispatch, CLI 1:1 mirror, developers-guide,
user-guide).

### 2. Circular coordinates — YES, with the user's own reframing

Original open question (mine): does a *finite* H¹ bar's stored representative in `CellularCohomologyContext`
actually restrict to a nonzero cocycle on a sub-level complex `K_r` for `r` inside `[birth, death)`? CLAUDE.md
says only *essential* bars' V-columns are guaranteed cocycles; finite bars' V-columns "equal their reduced pivot
chain" — unverified whether that's good enough.

**User's fix**: don't ask that question at all. Pick a single parameter value `r` inside the target bar's
range up front, truncate the filtration to the static subcomplex `K_r`, and compute cohomology of *that fixed
complex*. Anything interesting there is now essential *by construction* (nothing survives past `r` in the
truncated view) — the open verification question dissolves.

Mechanically cheap to build on existing plumbing:
- `K_r` = re-threshold an existing coface/VR stream with `maxFiltrationValue = Some(r)` (the same knob that
  already implements enclosing-radius truncation) — no new subcomplex-extraction code needed.
- Also truncate by dimension (`LimitedCofaceSimplexStream(stream, 2)`, drop dim-2 bars) so
  `CellularCohomologyContext` (which fully materializes its input, no `maxDim` parameter) doesn't build cells
  above the dimension of interest.
- Harmonic smoothing (`δᵀδ g = δᵀz`) is a sparse SPD least-squares solve, not really "optimization" in the
  LP/QP sense. `commons-math3-3.6.1` (already vendored — confirmed by listing the jar directly, not assumed)
  ships `linear.ConjugateGradient` + `linear.RealLinearOperator`: a matrix-free solver interface. tda4j's own
  `Chain`/`OrderedCell.boundary` machinery already *is* that operator (applying a coboundary = the callback CG
  wants). **No new dependency.**

Remaining real implementation points (not yet resolved, flag for whoever picks this up):
- The Laplacian `δᵀδ` is singular on constants (per connected component) — anchor one vertex per component, or
  disable Commons Math's positive-definite check on `ConjugateGradient`.
- **When several classes are alive simultaneously at the chosen `r`** (very common — a real diagram usually has
  more than one visible loop at once), picking "the" essential cocycle isn't unambiguous. Match essential classes
  at `K_r` back to specific full-filtration bars by birth value. This is the actual remaining bookkeeping
  question, smaller and more tractable than the original one, but real.
- `∂(ℤ-lift) = 0` must be a runtime check, not assumed — use an odd, large-ish prime (not the `p=2` default);
  RP²-type classes exist over F₂ with no ℤ/real lift, so a mod-2 "cocycle" can be a mirage for coordinatization.
- Cheap first correctness test: confirm `δ(rep) = 0` on the thresholded complex for essential H¹ bars, cross-check
  both `CellularCohomologyContext` and `PackedRipserCohomologyContext`.

### 3. Optimal cycles (OAT-style minimal representatives) — DEPRIORITIZED, not well-formed enough yet

My first draft claimed "the boundary matrix is totally unimodular, so plain LP suffices" — **wrong as a blanket
statement**. Dey–Hirani–Krishnamoorthy's actual result: `∂_{p+1}` is TU **iff the complex has no relative torsion
in that dimension**. Graph incidence matrices (`∂₁`) always are; `∂₂` of something like this repo's own RP²
fixture is not — LP relaxation can return a fractional non-cycle there, needing MIP or accepting a heuristic.

Also conflated ℤ and ℤ/2: minimum-weight ℤ/2-homologous cycle is NP-hard (Chen–Freedman). The LP approach needs
ℤ or ℝ coefficients, lifted from the field computation, with `∂(lift) = 0` checked — and **an F₂ class can have
no ℤ lift at all** (RP²'s fundamental class). This is a direct, real tension with the project's generic-`Field`
design principle: "optimal cycle" cannot be uniformly generic the way every other engine here is. There's also
more than one inequivalent literature definition (homologous-at-birth `z+∂c`; Escolar–Hiraoka's version allowing
older cycles; Obayashi's volume-optimal cycles) — pick one explicitly before ever coding this.

Tooling note, for whenever this gets picked back up: `optim.linear.SimplexSolver`/`LinearConstraint`/
`LinearObjectiveFunction` are present in the already-vendored `commons-math3-3.6.1` jar (confirmed). Dense
textbook two-phase simplex, tableau roughly `O(#p-cells)` rows by a small multiple of `O(#(p+1)-cells)` columns —
a real ceiling to *measure* on an actual fixture before trusting at any scale (per `[[tda4j_measure_dont_infer]]`),
not assumed "moderate." If the TU gap above forces MIP, or the tableau blows memory, evaluate `ojAlgo` (pure
Java, has both LP and MIP) rather than pushing further on Commons Math for this specific piece.

**Status: recorded as an answered/deprioritized question, not a live workstream.**

### 4. Bottleneck / Wasserstein distance — YES, hand-roll

Not the same "optimize against the boundary matrix" pattern as items 2/3 — pure bipartite matching over diagram
points (each also matchable to its diagonal projection), no boundary matrix involved.
- **Wasserstein** = assignment problem → Hungarian algorithm, `O(N³)` time, `O(N²)` memory. Explicitly **do not**
  route this through `commons-math3`'s `SimplexSolver` — a dense LP tableau for the augmented assignment problem
  is `N²` variables; at `N=1000` that's tens of GB. (Considered and rejected during this session, not just
  omitted.)
- **Bottleneck** = binary search over candidate distances + Hopcroft–Karp bipartite-matching feasibility at each
  threshold. Match essential points per homological dimension separately; if essential-point counts differ
  between the two diagrams in some dimension, distance is `+∞`.
- Neither Hungarian nor Hopcroft–Karp is in commons-math3 — hand-roll, comparable scope to the existing DQP
  solver work.
- **Oracles**: brute-force over permutations for small `N`; check `d_B ≤ W_p`; stability test — perturb points by
  `ε` on an untruncated VR filtration, expect `d_B ≤ 2ε` in doubled units (this codebase's diameter convention).
  Take the ground-metric (`internal_p`) convention from Hera/GUDHI's own source when implementing, not from
  memory. Document an explicit policy for essential (never-dying) bars in both diagrams (drop, or cap at some
  value) before writing the matching code.

Needs the four-surface treatment (MATLAB/CLI/docs) once built.

### 5. Flag-complex edge collapse — YES

Boissonnat–Pritam / Glisse–Pritam dominated-vertex removal on the VR 1-skeleton, done once across the whole
filtration (not per-threshold). Representatives transfer for free through the inclusion map *if* the collapse is
a genuine simple-homotopy-equivalence at every filtration level — the standard claim for this construction, but
verify against the actual Boissonnat–Pritam/Glisse–Pritam proof before relying on it, don't just assume.

Concrete points to get right:
- **Glisse–Pritam's variant can shift an edge to a later filtration value instead of deleting it outright** — so
  the reified representation needs modified *finite* values as well as `+∞`, not just an on/off collapse.
- **Integration point**: reify the collapsed graph the same way `WitnessMetricSpace` already reifies non-metric
  edge weights (a `FiniteMetricSpace`-shaped structure, `+∞` on collapsed-away edges, shifted finite values per
  the point above) — reuse existing plumbing rather than inventing a new representation.
- **Enclosing-radius hazard**: compute `minimumEnclosingRadius` from the *original* metric and pass it explicitly.
  The collapsed space's own enclosing radius can inflate, or become `+∞`, the moment any incident edge is `+∞` —
  silently disabling truncation if computed from the collapsed space itself.
- **Enumeration cost**: check whether `CofacetCursor` walks neighbours directly or scans all `n` vertices with a
  threshold test. If it scans, collapse cuts *reduction* work but not *enumeration* work — measure construction
  and reduction separately (`EngineComparisonBenchmarkSpec` already splits these) before claiming any speedup.
  Per `[[tda4j_measure_dont_infer]]`.

**Simplicial-set-engine question (user's curiosity — "could our simplicial set engine collapse more
aggressively?")**: my first answer in chat ("wrong tool, `quotient`/`identify` change topology via gluing") was
**wrong**, corrected by advisor. `X → X/A` for a contractible subcomplex `A` genuinely *is* a homotopy
equivalence — collapsing a contractible piece to a point is not the same operation as gluing two different
points together, and `quotient` (already supports degenerate targets — that's exactly how the RP² fixture is
built) is the right primitive, not the wrong one.

Concrete construction worth checking: **MST-based collapse**. By Kruskal (already in this codebase,
`streams.Kruskal`), `MST ∩ K_r` is a spanning forest with exactly one tree per connected component of `K_r`, at
every filtration value `r` — so quotienting `K_r` by `(MST ∩ K_r)` gives a wedge of components at every level,
naturally in `r`. This should preserve `PH_k` for `k ≥ 1` and kill only the finite H0 bars (verify this per-level
argument directly before coding, don't just take the sketch on faith).

**Likely honest verdict, to confirm empirically rather than assume**: valid, but probably small payoff.
- Removes only `O(n)` cells from a complex that can be `O(n^{k+1})`.
- The H0/H1 work it would save on VR is already done elsewhere: `unionFindDim01` (chunks engine) and apparent
  pairs (both Ripser engines).
- Any subcomplex `A` bigger than the MST that stays contractible at *every* level forces its cells to enter at
  equal filtration values — that's zero-persistence cancellation, i.e. apparent pairs / discrete Morse territory,
  which is either already covered or already deprioritized (see cubical section below).
- Representatives need explicit lifting back (a collapsed loop edge → tree path + edge), unlike edge collapse's
  free ride via inclusion.

**Action before treating this as a workstream**: verify the per-level MST argument directly, and count actual
cells-removed-vs-total on a real VR fixture. Record the answer here or in a dedicated design note; don't commit
engineering time until the payoff is measured, not inferred.

### 6. Dual union-find (cubical, "Flash Cubical") — YES; alpha-complex extension — plausible follow-on, not concurrent

Cubical piece already has a design note: `.claude/DESIGN-fast-cubical-engine.md` (2026-09-20), phases already
laid out — Phase 1 (genericize `SimplicialHomologyByDimensionContext` the same way `PersistenceInChunksContext`
was genericized, low-risk), Phase 2 (Flash Cubical's dual-graph top-dimension union-find, the real capacity-sweep
win — covers H0+H1 completely by union-find alone in 2D, leaves only H1 to real reduction in 3D), Phase 3
(pruning/lookup tables, constant factor), Phase 4 (discrete Morse, explicitly deprioritized there — its own
correctness burden is comparable to phases 1-3 combined and its marginal value on top of phase 2 is unmeasured).
Also note for the record: CLAUDE.md's own citation of Wagner-Chen-Vuçini as "union-find + discrete-Morse" is
wrong per that design note (that paper is actually about the `CubeMap` array representation, which tda4j's `Cube`
opaque type already does the equivalent of) — worth fixing in CLAUDE.md itself in a future pass, not done here.

**Gap the existing design note doesn't cover, found this session**: representatives. Flash Cubical itself is
F₂-only and produces no representatives at all — tda4j's design principle requires both generic-`Field` *and*
representatives for every engine, so this can't just be ported as-is. Before coding Phase 2:
- The H_{d-1} representative is `∂` of the coherently-oriented sum of top cells in the dual component that the
  pairing cell closes off.
- Computing it needs member-tracking inside the union-find structure (not just parent pointers for connectivity).
- Test it over `Fp(3)`, using the existing rank-among-non-degenerate-axes sign rule (`CubicalOrderedCell.scala`).

Add this to `DESIGN-fast-cubical-engine.md` directly before implementation starts.

**Alpha-complex extension (user's suspicion)** — directionally correct, real mechanism, but scoped narrowly:
the underlying justification is Alexander duality for a filtered subcomplex of a *fixed* triangulation of `S^d`
(full Delaunay triangulation plus one point at infinity, mirroring the cubical grid's "one shared infinity dual
vertex" for boundary facets). Three real limits:
- **Needs the full triangulation** — the dual graph is built from *absent* cells, so every top-dimensional
  simplex must eventually enter the filtration, including ones past any truncation radius. Not compatible with a
  truncated-radius alpha complex.
- **Only yields `H_{d-1}`**, so it only pays off at ambient dimension `d = 2` or `3` — complete alpha persistence
  for free in 2D, `H0`+`H2` for free in 3D leaving `H1`. At the high ambient dimensions where `AlphaComplexDQP`
  earns its keep, `H_{d-1}` is irrelevant and truncation (which this technique is incompatible with) is the
  whole point. **This is a `HelixDelaunay` opportunity, not a `DQP` one.**
- **Cospherical/degenerate input breaks the precondition.** CLAUDE.md's own "Degeneracy hazard" section: `k`
  cospherical points give a `(k-1)`-simplex, meaning `AlphaShapeDQP` can emit simplices of dimension `> d` there —
  which breaks the "every facet has ≤ 2 cofaces" assumption the dual graph needs.

**Status**: write this up as its own `.claude/DESIGN-*.md` follow-on once the cubical version (items above) is
built and validated — not concurrent work, and specifically scoped to `HelixDelaunay` at `d ∈ {2,3}`.

### 7. PHAT I/O — declined

Consistent with the original ranking (lowest priority of the group). Verified directly against
[xoltar/phat's README](https://github.com/xoltar/phat/blob/master/README.md) this session, not recalled from
memory, per this codebase's own io-module verification ethos:
- Neither the boundary-matrix format nor the `persistence_pairs` format carries real-valued filtration data at
  all — a cell's filtration position is implicit in line order; pairs are birth/death **indices**, never values.
- Z/2 is baked into the on-disk format's semantics (pure incidence, no signs/coefficients), not just the tool's
  default — a reader couldn't be field-generic without hard-refusing every other field.
- PHAT's primary interface was always its C++ template API; the ascii/binary format is a benchmark-harness
  convenience with no real external interchange ecosystem the way Ripser/Dipha binaries have.
- Its algorithms (`chunk_reduction`, twist/clearing) are Bauer–Kerber–Reininghaus — the same lineage as this
  codebase's own chunks engine. The gap really is only the file format, not any missing algorithmic content.

A writer would be a small addition on top of the boundary-matrix export from item 1 (maybe ~20 lines) if this
ever gets revisited — the user can override this call later at near-zero sunk cost, since item 1 is happening
regardless.

### 8. Diagram vectorizations — YES

No LP/QP/CG tooling implicated at all — pure array/geometry code directly on `Barcode`/`PersistenceBar`.
- **Landscapes**: a single bar gives a tent function; `Σ_k ∫λ_k = Σ(d-b)²/4` exactly — usable as a closed-form
  correctness oracle, not just eyeballing plots.
- **Images**: use Adams et al. 2017's birth-persistence coordinates and weighting convention specifically (verify
  against that paper directly when implementing, not from memory).
- Both need an explicit, stated policy for essential (never-dying) bars — drop, or cap at some finite value —
  decided and documented before the implementation, not left implicit.

Lowest-risk item on the whole list; independent of everything else here.

## Recommended execution order (not yet started)

1. **Bottleneck/Wasserstein + vectorizations** — fully independent of each other and of everything else, crisp
   oracles already specified above, no open representative or math-scope questions. Start here.
2. **Boundary-matrix export** — unblocks MATLAB delivery for anything downstream; small, well-scoped addition to
   `PersistenceResult` per the existing design.
3. **Circular coordinates** — needs the birth-matching bookkeeping (item 2 above) worked out, otherwise
   straightforward given the CG/threshold plumbing already exists.
4. **Edge collapse** — needs the `CofacetCursor` enumeration-cost check and Glisse–Pritam shifted-value handling
   worked out first; the MST-based simplicial-set variant is a smaller, separate research question to answer
   (verify + measure) before deciding whether it's worth building at all.
5. **Dual union-find, cubical** — Phase 1/2 per the existing design note, *plus* the representative-recording
   design (new this session) added to it before Phase 2 implementation starts.
6. **Dual union-find, alpha** — write its own design note after (5) validates; scoped to `HelixDelaunay`, ambient
   `d ∈ {2,3}` only.
7. **Optimal cycles** — deprioritized; revisit only once a single problem formulation is chosen and the TU/
   ℤ-lift scope is sized honestly.
8. **PHAT I/O** — declined.

Every item that ships a new user-facing capability needs the CLAUDE.md four-surface check: MATLAB dispatch, CLI
1:1 mirror, `src/docs/developers-guide/`, `src/docs/user-guide/README.md`.
