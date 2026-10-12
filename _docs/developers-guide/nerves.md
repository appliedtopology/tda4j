---
layout: main
title: Nerves and group homology
---

This page covers the part of the `sset` add-on built on infinite simplicial
sets: nerves of monoids, groups and finite categories, persistent group
homology, and Brown's collapse, which makes group homology computable in
higher degrees. The finite simplicial-set machinery underneath is in the
simplicial-sets section of [Architecture](architecture.md). Everything here is
in `org.appliedtopology.tda4j.sset`.

## Lazy simplicial sets and their persistence

`SimplicialSet[G]` (`SimplicialSet.scala`) is the lazy, possibly infinite
trait: `generators(n)` and `faces` are produced on demand, and `skeleton(n)`
materializes dimensions `0..n` as a `FiniteSimplicialSet`. A skeleton's
homology is correct only below degree `n`.

`x.persistentHomology(filtrationValue, maxDegree, prime)` runs the chunks
engine on the `(maxDegree + 1)`-skeleton, so it is exact in every degree it
reports, for an infinite set too. It returns a `PersistenceDiagram` with a
representative on every bar. A filtration that lets a generator enter before
one of its faces is rejected, naming the faces.

## Monoids, groups and their nerves

`FiniteMonoid` and its subclass `FiniteGroup` (`FiniteGroup.scala`) are
multiplication tables over `0 until order`.

The companions build the examples:
- **monoids:** `rectangularBand` and `reesMatrix`, completely simple semigroups
  with an identity adjoined;
- **groups:** `cyclic`, `symmetric`, `alternating`, `generalLinear(n, p)`
  (acting on the nonzero vectors), `permutationGroup` and `product`.

A group finds its own subgroups:
- `subgroupGeneratedBy`;
- `pSubgroups(p)`, every nontrivial p-subgroup;
- `isElementaryAbelian`, which picks out Quillen's poset.

`Nerve(monoid, subset)` (`ClassifyingSpace.scala`) is the nerve of a monoid as
a one-object category. Its non-degenerate `n`-simplices are the tuples
`[x_1|...|x_n]` of non-identity elements (`NerveSimplex`):
- `d_0` drops the first entry and `d_n` the last;
- an inner face multiplies two neighbouring entries, and is degenerate when the
  product is the identity.

For a group this is `BG`; `subset` restricts it to `BH` for a subgroup `H`.

## Finite categories

`FiniteCategory` (`FiniteCategory.scala`) is a trait with six members: the two
counts, `source`, `target`, `identityOf` and `andThen`. Composition is
diagrammatic: `andThen(f, g)` is "first `f`, then `g`", matching
`FiniteMonoid.multiply(a, b)`. `validate()` checks the category laws.

The constructors are:
- `fromMonoid` and `fromPoset`;
- `freeOnAcyclicQuiver`;
- `actionGroupoid` and `homotopyOrbits`.

`homotopyOrbits` is the Grothendieck construction of a group acting on a
simplicial complex. It returns a `HomotopyOrbitCategory`, whose nerve is
homotopy equivalent to the Borel construction, and whose `morphismsOver(H)`
gives the subcategory of a subgroup's action.

`CategoryNerve(category)` is the lazy nerve. Its non-degenerate simplices
(`CategorySimplex`) are chains of composable non-identity morphisms, with the
same face rule as `Nerve`. `filtrationBy(chain)` filters by a chain of
subcategories. A poset's nerve is finite (its order complex); a category with
a non-identity endomorphism has generators in every dimension.

**Trap:** `B(M^op)` is homotopy equivalent to `BM`, so a composition order
that is flipped by mistake passes every Betti-number check. Test a nerve cell
for cell and face for face against `Nerve`, and a poset's nerve by its exact
f-vector against the order complex.

## Persistent group homology

`ClassifyingSpace.persistentGroupHomology(g, chain, maxDegree, prime)` computes
`H_n(H_i; F_p)` as `H_i` runs up an increasing chain of subgroups:
- a simplex enters with the first subgroup containing all its entries
  (`ClassifyingSpace.filtrationBy`);
- births and deaths are positions in the chain;
- every bar's representative is a cycle of `BG`.

`bettiNumbers(g, maxDegree, prime)` reads the essential classes of the
one-step chain.

The call picks its complex:
- when `RewritingSystem.refining(g, chain)` succeeds, it runs on Brown's
  collapse;
- otherwise it runs on the bar complex, filtered.

The barcode is the same either way. The bar complex has `(|G| - 1)^n` cells in
dimension `n`, so the collapse is what makes degrees above 2 or 3 reachable.

## Brown's collapse

Brown's collapsing scheme ("The geometry of rewriting systems", 1992) turns a
complete rewriting system for a monoid into a discrete Morse matching on its
bar complex. The cells left unmatched (critical) are the Anick chains, with a
Morse boundary of their own and the same homology.

**`RewritingSystem`** (`CollapsedNerve.scala`) gives the normal form of every
element and a level for every letter. A rule must decrease in the collection
order, the wreath product of shortlex orders by level, and `validate()` checks
this. Shortlex alone would reject the rules of a polycyclic presentation,
which lengthen words.

`RewritingSystem.refining(group, chain)` builds such a system: a series of
prime-index normal steps from `1` to `G` passing through every subgroup of the
chain, with one letter per step. It returns `Left`, naming the step, when a
subgroup is not subnormal in the next or a quotient is not solvable. That is
always possible for a p-group, and never for `A_5` or `S_5`.

**`CollapsedNerve(rewriting)`:**
- `criticalCells(n)` generates the Anick chains directly; the bar complex is
  never enumerated;
- `morseBoundary` computes boundaries by a memoized flow over the matching;
- `lift` maps a critical cell back to a bar-complex chain, so representatives
  come back as cycles of `BG`;
- `stream(chain, maxDegree)` gives the filtered critical cells for the chunks
  engine or the verb (`import collapsed.given`);
- `persistentHomology(chain, maxDegree, prime)` returns the lifted diagram.

The flow runs over an explicit stack. A cell found again while still in
progress means the matching is not acyclic, and the flow throws rather than
loops. Coefficients are exact `Long`s, and an overflow throws with a message
naming the bar complex as the way out.

**Trap:** filter only by a chain that the rewriting system `respects`, meaning
each subgroup's normal forms use only its own letters. A matching that
straddles two levels still gives a monotone filtration, but the barcode is
wrong. `stream` and `persistentHomology` check this and refuse.

**Limits:** the flow's memo grows exponentially with the degree, more slowly
than the bar complex but enough to be the limit for larger groups. There are
no cup products or Steenrod squares on the collapsed complex.

**Testing:** `BrownCollapseReference` in the test tree classifies every bar
cell and runs the unmemoized flow. `CollapsedNerveSpec` compares the two term
for term, and compares the barcodes with the bar complex's over `F_2` and
`F_3`. Keep both whenever the collapse changes.
