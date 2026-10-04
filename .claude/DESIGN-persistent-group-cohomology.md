# DESIGN: persistent group (co)homology via the bar construction (2026-10-02)

Idea (project lead): a finite group `G` with a chain of subgroups `1 <= H_0 <= H_1 <= ... <= G` gives nested classifying
spaces `BH_0 ⊂ BH_1 ⊂ ... ⊂ BG` (the nerve/bar construction), so the persistent homology of this filtration is the
persistence module `H_n(H_0) -> H_n(H_1) -> ... -> H_n(G)` of inclusion-induced maps. Over a field `F_p` group
cohomology and homology have the same dimensions (dual spaces), so the barcode is the same for both.

## A. Is it clearly computationally infeasible?

**No for small groups and low degrees, yes for anything growing.** The normalized bar complex has `(|G|-1)^n`
non-degenerate n-simplices, and `H_k` needs cells up to dimension `k+1`:

| group | n=1 | n=2 | n=3 | n=4 | n=5 |
|---|---|---|---|---|---|
| S_3 (6) | 5 | 25 | 125 | 625 | 3,125 |
| S_4 (24) | 23 | 529 | 12,167 | 279,841 | 6,436,343 |
| S_5 (120) | 119 | 14,161 | 1,685,159 | 200,533,921 | 23,863,536,599 |

So: S_4 up to `H_3` (needs 280k cells) is routine, `H_4` (6.4M) is borderline; S_5 gives `H_1` (14k cells) and `H_2`
(1.7M cells) easily/with some memory, `H_3` (200M) is out of reach with this representation. The growth is exponential in
the degree with base `|G|-1`, which is why serious software (HAP for GAP, Sage's group cohomology via libGAP/Singular)
computes group cohomology from small free resolutions (e.g. Wall/minimal resolutions, polycyclic or Sylow-based
methods), not from the bar complex. The bar construction is the right tool as a *conceptual and cross-checking* one --
and it is the one that lives naturally inside a filtered-simplicial-set persistence engine.
Honest limitation: it does NOT compress anything, which is the thing the project lead cares about elsewhere (CLAUDE.md,
"short-circuits the compression aspect"). Possible compression (not done): use the smaller *non-normalized-by-subgroup*
model (a Sylow reduction: for `P` Sylow, `H_*(G;F_p)` is a summand of `H_*(P;F_p)`), or a minimal resolution as the cell
complex with a hand-built filtration.

## B. What is needed (and what exists)

1. A finite group with a multiplication table and subgroup generation: `groups.FiniteGroup` (new).
2. The nerve as a `FiniteSimplicialSet`, truncated at `k+1`: `groups.ClassifyingSpace` (new). The one delicate point is
   the face `d_i` when `g_i * g_{i+1} = e`: it is the *degenerate* simplex `s_{i-1}(g_1..g_{i-1}, g_{i+2}..g_n)`
   (degeneracy word `[i-1]`, always a single index, so already in normal form). `validate()` (the simplicial identities)
   passes on S_3 up to dimension 3.
3. A filtration labelling each simplex with the first subgroup containing all its entries: monotone automatically
   (a face's entries are entries or products of entries, all inside any subgroup containing the coface's).
4. Engines: unchanged. `FilteredSimplicialSetStream` accepts an arbitrary generator labelling, and the chunks engine
   reduces it.
5. Not required but wanted: infinite/lazy simplicial sets (`DESIGN-sage-simplicial-sets-comparison.md`, item A), so that BG
   is a first-class object with `.skeleton(n)` rather than "build it truncated at the right dimension yourself".

## C. Proof of concept

Code: `src/main/scala/.../groups/{FiniteGroup,ClassifyingSpace}.scala`; tests: `groups/ClassifyingSpaceSpec.scala`.
Library-only (a new `groups` package that depends on `cells`/`streams`/`homology`; nothing depends on it), NOT yet a
four-surface capability: no MATLAB/CLI entry, no user docs page.

### Results (measured; chunks engine, this machine)

Oracles (all derived by hand BEFORE running, all pass in `ClassifyingSpaceSpec`): Z/2 over F_2 → 1,1,1,1,1; Z/3 over F_2 →
1,0,0,0,0; Z/3 over F_3 → 1,1,1,1,1; Z/2×Z/2 over F_2 → n+1 (Künneth); S_3 over F_2 → 1,1,1,1,1 (Sylow of odd index); S_3 over
F_3 → 1,0,0,1,1 (from H_n(S_3;Z) = Z/2, 0, Z/6, 0 and universal coefficients); S_4 over F_2 → 1,1,2 (abelianization Z/2;
Schur multiplier Z/2 plus Tor). Simplicial identities hold on B(S_3) (`validate()`), cell counts are (|G|-1)^n.

Persistence, S_4 over F_2 along `<(01)> < <(01),(23)> < D_8 < S_4` (orders 2, 4, 8, 24; positions 0..3), degrees ≤ 2:

    H_0: [0, ∞)
    H_1: [0, ∞)   [1, 2)   [2, 3)
    H_2: [0, ∞)   [1, 2)   [1, ∞)   [2, 3)

Reading: the class from the first Z/2 survives to S_4 (it is the abelianization); each enlargement adds H_1 classes that are
identified away at the next step (V_4's second generator dies in D_8, D_8's own new class dies in S_4). The two H_2 classes
alive at S_4 are born by position 1 (V_4) — consistent with the Sylow argument asserted in the spec: nothing essential is born
at S_4 (restriction from the Sylow 2-subgroup is onto in F_2-homology). Over F_3 every bar is trivial (all subgroups here are
2-groups, S_4 has no 3-torsion in H_1/H_2): H_0 only.

Cost: S_3 to H_3 0.3 s; S_4 to H_1 0.1 s, to H_2 (12k cells) 4 s; **S_4 H_3 (280k cells) > 9 min, not finished**; S_5 H_1 (14k
cells) 1.2 s (F_2: 1,1; F_5: 1,0 — matches abelianization Z/2), **S_5 H_2 (1.7M cells) > 8 min, not finished**. So the feasibility table above is
confirmed in practice, and the limit is the reduction (Chain/PriorityQueue pivots on millions of cells), not enumeration. Likely
next steps if this matters: run the cohomology-with-clearing (twist) engine on the nerve, which skips most columns on exactly
this kind of complex, or switch to a minimal resolution.

Not done / unverified: no MATLAB/CLI/user-docs surface; fields other than F_p not exercised; D_8/Q_8/other chains only S_4
tested; the S_4 H_3 value (3) is by hand from H*(S_4;F_2) = F_2[σ1,σ2,c3]/(σ1c3), unconfirmed by computation.
