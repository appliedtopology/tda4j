# Sage simplicial sets vs tda4j (2026-10-02)

Source: Sage reference manual pages `sage/topology/{simplicial_set, simplicial_set_constructions,
simplicial_set_examples, simplicial_set_morphism}` (read via the web docs; I did NOT read Sage's source or run Sage, so
statements about *how* something is implemented are from the docs' own wording only). Kenzo out of scope as requested.

## What tda4j has (cells/SimplicialSet*.scala)

Finite Eilenberg-Zilber presentations `FiniteSimplicialSet[G]` (non-degenerate generators + faces into `SSetElement`
degeneracy words); `validate()` of the simplicial identities; `product`, `coproduct`, `quotient(map)`, `identify`;
fixtures (spheres, RP^2/RP^3, torus, triangle); homology and cohomology over any `Field`, **persistent** (a real
`FilteredSimplicialSetStream`), with representatives. No MATLAB/CLI entry.

## What Sage does that we don't (grouped by how much it changes the design)

### A. A different kind of object: infinite / lazily generated simplicial sets  (**the big one**)
Sage's `ClassifyingSpace(G)`, `Nerve(M)` are infinite simplicial sets (finite homology computed up to a stated
dimension, `n_skeleton(n)` for finite approximations, `nondegenerate_simplices(max_dim)`). Ours require every
dimension's generators up front. **To match:** a `SimplicialSetLike[G]` with `nondegenerateAt(n): Iterable[G]` (lazy per
dimension) and `faces(g)`, with `FiniteSimplicialSet` as the all-dimensions-materialized special case and
`.skeleton(n)` returning one. Cost: moderate; the engines already consume streams dimension by dimension
(`StratifiedCellStream`), so the change is in `cells/` only. This is also what persistent group cohomology needs
(`DESIGN-persistent-group-cohomology.md`).

### B. Constructions we lack (each is a few dozen lines on top of A or of `quotient`/`coproduct`)
| Sage | tda4j today | To add |
|---|---|---|
| `subsimplicial_set`, `n_skeleton`, `ambient`/`inclusion_map` | none | generator subset closed under faces; skeleton = truncate by dimension |
| pushout, `wedge`, `disjoint_union`, `coproduct` (pointed-aware) | `coproduct` only | pushout along inclusions via `identify`+`coproduct`; wedge = pushout over base points; need base points |
| pullback, `equalizer` | none | levelwise fibre product over generators; messy with degeneracies (Sage builds it from the product) |
| `cone`, reduced cone, `suspension(n)`, `smash_product`, `mapping_cone` | none | cone = join with a point (needs `join`, which Sage itself lists as not implemented); suspension = cone/base via `quotient` |
| `Horn(n,k)`, `Simplex(n)`, `Point`, `Empty` | `edge`/`triangle` fixtures | trivial |
| `KleinBottle`, `ComplexProjectiveSpace(n≤4)`, `HopfMap`, `PresentationComplex(G)` | torus, RP^n | fixtures; `PresentationComplex` is a nice small one |
| `Nerve(monoid)`, `ClassifyingSpace(group)` | none | needs A; see persistent-group-cohomology design |

### C. Maps: `SimplicialSetMorphism` (18 methods in the docs)
We have no morphism type at all. Sage: composition, `image`, `is_injective/surjective/bijective/constant/identity`
(CORRECTION: Sage's docs do not say what these test; an earlier note here said "by homology", which was a summarizer's inference from the examples. We implement them on SIMPLICES, the standard meaning, and treat Sage's exact semantics as unconfirmed), `induced_homology_morphism`, `associated_chain_complex_morphism`, pushout/pullback/coequalizer along maps,
`mapping_cone`, `n_skeleton`. **To match:** `SSetMap[G,H]` = generator -> `SSetElement[H]` respecting faces (checkable
like `validate()`), the chain map it induces, and the induced map on (persistent) homology. Moderate. High value for us:
a persistence MODULE morphism is exactly what a filtered map induces (inclusion of subgroup chain steps, below).

### D. Invariants beyond (persistent) Betti numbers
- `fundamental_group()` as a finitely presented group (Sage uses GAP) — needs a spanning tree/`reduce()` and a
  presentation; no group-theory backend here. Medium; only the presentation, not word-problem solving.
- Integer / ring coefficients with torsion (`homology(base_ring=ZZ)`): ours are fields only (design principle: generic over
  `Field`). Smith normal form over Z is a separate engine; low priority — over `Fp` for several p we see torsion indirectly.
- `alexander_whitney`, cup product / cohomology ring structure (Sage exposes the AW map on simplices): not present. Medium:
  AW is explicit (front/back faces) and works on our `SSetElement`s.
- `is_connected`, `is_reduced`, `reduce()`, `base_point`, `is_pointed`, `f_vector`, `graph()`: trivial utilities.

### E. Things we have that Sage does not (so the comparison is fair)
Persistence (filtration by any monotone generator labelling), representatives for every bar, arbitrary `Field`
coefficients as typeclass, alternative engines cross-checked.

## Suggested order if the lead wants "everything"
1. A (lazy/infinite + `skeleton`) — unlocks Nerve/ClassifyingSpace and group cohomology.
2. B-trivial fixtures + `subsimplicial_set` + `Nerve`/`ClassifyingSpace`.
3. C (morphisms), then pushouts/wedge/cone/suspension on top of them.
4. D: cup products via Alexander-Whitney; `fundamental_group` presentation.
Rough size: A+B+fixtures ≈ a day of focused work with tests; C ≈ a day; D-cup ≈ half a day; pullback and Z-coefficients are the
two I would not volunteer for.

## Decisions (project lead, 2026-10-02)
- **Integer / torsion coefficients: not a priority**, a much-later problem if at all (design principle stays: generic over `Field`).
- **Pullbacks (and equalizers): deferred** until it is clear they are needed.
- Everything else above ("do the rest"): lazy sets, constructions, morphisms, fundamental-group presentation, cup products.
