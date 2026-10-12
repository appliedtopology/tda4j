# WORKLOG: Brown's collapse in the library, and persistentGroupHomology with representatives (2026-10-11)

The project lead asked for the two follow-ups from `WORKLOG-category-nerves.md` / `WORKLOG-brown-collapse.md`:
- **(A)** generate the critical cells directly, so that the collapse saves time;
- **(B)** make `ClassifyingSpace.persistentGroupHomology` return representatives.

The advisor set the order: B first, on the bar path only; then A's core with a go/no-go measurement; then the API, the
wiring and the docs.

## B: persistentGroupHomology returns a PersistenceDiagram (commit 778f604)

`nerve(g, Some(top)).persistentHomology(filtrationBy(chain), d, p)` now returns a diagram, and `.triples` gives the old
list. MiMa flagged the change against the 0.5.0 baseline (`IncompatibleResultTypeProblem`); it now has a commented
`mimaBinaryIssueFilters` entry in `build.sbt`.

Updated callers: the tutorial fences, `GroupHomologyDemo`, and `ClassifyingSpaceSpec`. A new spec checks that on the
tutorial's S_4 chain, over F_2 and F_3, every representative is a cycle of BG made of simplices present at the bar's
birth.

## A: the core, and the go/no-go measurement

`CollapsedNerve.criticalCells(n)` grows Anick chains letter by letter. Each next entry is a normal word u, found by a DFS
over normal words, such that `x u` is reducible while `x u'` is irreducible for every proper prefix u'. No bar cell is
enumerated.

The Morse boundary uses the memoized flow:

> crit(redundant s) = -inc · Σ_{f≠s} b_f · crit(f), where t is the partner of s and inc = [∂t : s] = ±1.

It runs over the matching's DAG with an explicit stack. An in-progress set turns a cycle into an `IllegalStateException`.
Arithmetic is `Math.addExact`/`multiplyExact` on Long; on overflow it throws a message that names the bar-complex
fallback. `lift` (ι) uses the same DAG with carry(s) = -inc·t - inc·Σ b_f carry(f), computed only for the cells in
reported representatives.

**Measurement** (one JVM, the second of two runs, so warm; S_4 along `<(01)(23)> < V_4 < A_4 < S_4`):

| maxDegree | critical cells, dimensions 0..d+1 | time (s) | redundant cells visited | bar complex cells, dimensions 0..d+1 |
|---|---|---|---|---|
| 2 | 1, 5, 16, 42 | 0.2 (first run, JIT cold) | 93 | 12,720 |
| 3 | …, 99 | 0.08 | 636 | 292,561 |
| 4 | …, 219 | 0.2–0.3 | 3,766 | 6,728,904 |
| 5 | …, 466 | 1.2 | 21,103 | |
| 6 | …, 968 | 8–10 | 116,727 | 3.4 · 10⁹ |
| 8 | | out of memory at 2 GB (the flow memo) | | |

For comparison, the bar complex took 7.5 s and 5.6 s for maxDegree 2 (F_2 and F_3), and maxDegree 3 did not finish in
9 minutes (`DESIGN-persistent-group-cohomology.md`).

**Go.** The flow touches less than 0.1% of the bar complex at degree 4. The visited count grows about 5× per degree,
against 23× for the bar complex. That is still exponential, and the memo is now the limit.

## Values computed for the first time (both match hand derivations)

- **H_*(S_4; F_2), degrees 0..6** = 1, 1, 2, 3, 3, 4, 5. This is the Poincaré series (1 + t²)/((1 - t)(1 - t³)) of
  F_2[σ1, σ2, c3]/(σ1 c3). It settles the design doc's "S_4 H_3 = 3 by hand, unconfirmed".
- **H_*(S_4; F_3), degrees 0..6** = 1, 0, 0, 1, 1, 0, 0. The Sylow 3-subgroup is abelian with normalizer S_3.

Other runs, reported here but not asserted in specs:
- GL(2,3) (order 48): F_2 1, 1, 1, 2, 3, 3 and F_3 1, 0, 0, 1, 1, 0, about 3 s each. No independent oracle was checked
  beyond H_1 (abelianization Z/2).
- (Z/2)³: 1, 3, 6, 10, 15, 21, 28 (Künneth) in 0.3 s.
- S_3 over F_3, degrees 0..10, in 31 s, with the expected period-4 pattern.

## RewritingSystem.refining

Construction:
1. For each consecutive pair of the chain, plus 1 at the bottom and G at the top, take normal closures down
   (`b ⊵ a^b ⊵ …`). Left if this stalls above a: the subgroup is not subnormal.
2. Refine each normal step by the relative derived series (`[d,d]·lower`). Left if it stalls: the quotient is not
   solvable.
3. Refine each abelian step by an element of prime order modulo what has been built so far.
4. Level i has the letters x_i^k, for 0 < k < p_i. A normal form is one letter per level, top first.

The rules a·b with level(a) < level(b) rewrite to b·(conjugate's normal form), which can be longer than 2 letters. So the
experiment's shortlex termination check would reject them, and `validate` uses the collection order instead (the wreath
product of shortlex by level). The spec asserts both facts for S_4.

`refining` returns Left for:
- the tutorial's chain through D_8, because D_8 is maximal and not normal in S_4;
- S_5 and A_5, because they are not solvable.

It returns Right for the Z/4, Z/8, S_3, D_8 and S_4 chains and for GL(2,3).

## Specs (CollapsedNerveSpec, 13 examples; BrownCollapseReferenceSpec, which is the experiment's 8)

- **Against the reference.** `criticalCells` equals "classify every bar cell" cell for cell, on Z/4 (both
  presentations), Z/8, S_3 and D_8 up to dimension 4, S_4 up to dimension 3, and Z/4 one-generator up to 5. The memoized
  `morseBoundary` equals the reference's unmemoized flow term for term on every critical cell.
  - **Mutation test:** changing `-inc * b` to `inc * b` in the memoized flow fails the latter check. The source was then
    restored.
- **Barcodes.** The collapsed barcode equals the bar barcode for Z/8 (F_2, degree 3), S_3 (F_2 and F_3, degree 4), D_8
  (F_2, degree 3) and S_4 (F_2 and F_3, degree 2).
- **S_4 by hand.** Degrees 0..5: F_2 1, 1, 2, 3, 3, 4 and F_3 1, 0, 0, 1, 1, 0. Redundant cells visited are under 1% of
  the bar cells up to dimension 6.
- **Representatives.** Lifted representatives are bar cycles present at birth (S_4 over F_2 and F_3, Z/8, D_8).
- **The guard.** The one-generator Z/4 system validates, but it does not respect Z/2 < Z/4, and `persistentHomology`
  refuses that chain with a message naming `RewritingSystem.refining`. Unfiltered (the chain `Seq(all)`), it gives
  1, 1, 1, 1.
- **Dispatch.** `bettiNumbers(S_4, 4, 2)` = 1, 1, 2, 3, 3 within the test's time, which only the collapse can reach. The
  existing ClassifyingSpaceSpec values (Z/n, V_4, S_3, S_4) now also run on the collapse, which makes them a cross-check.

The experiment file became `BrownCollapseReference.scala` (renamed with `git mv`), on the pattern of
`ChainPackedRipserReference`.

## Docs

- **Tutorial "Status" and "The cost".** Rewritten from the computed values:
  - the collapsed cell counts 16, 42, 99;
  - `s4Homology` up to degree 4;
  - `throughDihedral` = the chain through D_8 has no refinement.

  All three are asserted in `GroupHomologySpec`.
- **User guide (Groups).** Says when the collapse is used.
- **CLAUDE.md.** The gaps line is back to "none known".

## Limits, not done

- Memory: S_4 at degree 8 needs more than 2 GB for the flow memo. Compacting the memo (for example, storing only the
  critical parts of cells with several dependents) is untried.
- There are no cup products or Steenrod squares on the collapsed complex; they would need ι, a diagonal approximation,
  and a projection back.
- A non-subnormal chain (the tutorial's) still goes through the full bar complex. A coset-factorization rewriting system
  might handle some such chains; untested.
- No MATLAB or CLI entry, following the sset precedent.

## Addendum (same day, later): one letter per level; invariants on the built systems; the verb

**One letter per level.** Advisor review pointed out that giving every power x_i^k its own letter served only the shortlex
check. The collection order accepts one letter per level: a power rule `x^p -> lower` shortens the top level, and a
conjugation rule keeps the top letter and shortens the segment before it. `refining` now emits `x_i` alone, with normal
forms `x_m^(e_m)...x_1^(e_1)`.

Critical cells, measured:
- Z/3: 1 per dimension. It was 2^n, no better than the bar complex.
- S_3: n + 1 (was 2^(n+1) - 1).
- S_4: 1, 4, 10, 20, 35, 56, 84, 120 = C(n+3, 3) (was 1, 5, 16, ... 219).

The flow, not the number of cells, sets the cost. It was re-measured on one JVM, warm, on this machine:

| case | before | after |
|---|---|---|
| S_4, F_2, maxDegree 4 / 5 / 6 | about 0.2 / 1.2 / 8-10 s | 0.18 / 0.66 / 5.6 s |
| S_4, F_2, redundant cells visited at maxDegree 4 / 5 / 6 | 3,766 / 21,103 / 116,727 | 3,612 / 29,025 / 264,199 |
| S_4, F_2, maxDegree 8 | out of memory at 2 GB | still out of memory at 2 GB (killed after 11 min of GC thrash) |
| S_4, F_3, degrees 0..6 | 13.6 s | 3.9 s |
| S_3, F_3, degrees 0..10 | 31.5 s | 1.5 s |
| GL(2,3), degrees 0..5 | 2.6-3.4 s | 2.3-2.6 s |

Z/9 over F_3, degrees 0..12, took 23.5 s, with no earlier run to compare. These are single runs, not an A/B. The S_3
factor of 20 is far outside the noise; the S_4 F_2 times are within a factor of two either way.

**New specs**, all passing (72 examples across the collapse, reference, group and category specs):
- the involution and ±1 incidence on `refining`'s own D_8 (up to dimension 4) and S_4 (up to 3);
- ∂∂ = 0 on S_4's collapsed complex up to dimension 6;
- chains that stop below G, compared with the bar complex of their last subgroup: Z/8 up to Z/4, and S_4 up to A_4 over
  F_2 and F_3;
- Z/3 with one critical cell per dimension.

**The verb.** `Persistence(collapsed.stream(chain, 2), characteristic = 2)`, with `import collapsed.given`, equals the
chunks barcode for S_4. That path is Cohomology with cycles derived through Involution, and here it handles the
empty-boundary loop edges. There is now a spec for it. The `stream` scaladoc no longer says "every engine"; it says
chunks or the verb.

**Lint.** Commit fa3c8ac was pushed without scalafmt while sbt was busy, and CI lint failed on PR #49. The formatter
output is its own commit, 44e54a8.
