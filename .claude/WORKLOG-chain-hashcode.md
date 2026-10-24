# `Chain.hashCode` (#58), 2026-10-24

`Chain.equals` is equality of formal sums (cells with a nonzero total, coefficients equal by the field's `isEqual`, any
order, any storage) and `hashCode` was the identity hash. Found 2026-10 while writing the chunks cross-validation
(`WORKLOG-chunks-representatives.md`, "A second real finding"), worked around there and in `NaiveCappedSpec` with
multiset matches, filed as #58.

## Design

- `hashCode` = `MurmurHash3.unorderedHash` over `termHash(cell.##, field.hash(coefficient))` of every term of `terms`
  (merged, zero totals dropped). `unorderedHash` makes it blind to the order the chain is stored in; going through `terms`
  makes it blind to deferred entries (`a + a` as two entries, a cell whose entries cancel) and to the storage.
- The coefficient part needs the field: `Fp` has several `Int` forms of one element (`Fp(1)` and `Fp(-2)` over F₃), so
  `x.##` is wrong for it. New `Field.hash(x: Self): Int`, "agrees with `isEqual`". Default `0`: correct for every
  field, including a user's, and a finer hash is an override. `Fp` hashes `x.toUInt` (the residue in `0 until p`; the
  balanced-representative `norm` is also unique per class but `toUInt` is the one the file already uses for table
  indices). `DoubleApproximated` is overridden to `0` explicitly: equality within `epsilon` is not transitive
  (`a ~ b`, `b ~ c`, not `a ~ c`), so no function of the value can agree with it, and the hash is by cells only, as the
  issue proposed. The same tolerance decides which terms are zero, so the cells hashed are those `terms` keeps.
- Alternatives not taken: a `Hash` typeclass on the coefficient (a second context bound on `Chain`, and every `Chain[_,
  C]` signature in the library carries `C: Field`, not a pair); defaulting `Field.hash` to `x.##` (wrong for `Fp`, and
  silently wrong for a user field with several representations, where `0` is only weak).
- `HeapChain.terms` collapses the queue in place, so `hashCode`, like `equals`, mutates the representation (not the
  sum); the thread-safety is that of `equals`/`terms`, unchanged.

## What the specs pin

`ChainSpec` ("Chain hashing agrees with equality"), over F₃: heap, heap in the reverse order, heap with respelled
coefficients, packed, packed respelled, and a heap with deferred entries (`1 + 1`, and a cancelling `1 + 2`) all equal and
hash alike, collapse in a `Set`, and find each other as `Map` keys. Discriminating against the plausible wrong
implementations: hashing `rawEntries` (fails on the deferred chain), hashing the raw `Int` (fails on the respelled
ones), folding terms in storage order (fails on the reverse-ordered heap), hashing the cells only for `Fp` (fails the
"differ in a coefficient" check, which pins this implementation's choice, not the contract). `ChainStorageSpec` repeats it
with a user storage (`ListChain`) over 80 random sums. `FiniteFieldSpec`: `hash` is the residue for any `Int`.

## Docs touched

`Chain`/`Field` scaladoc, `architecture.md` (Chain bullet, Field paragraph), `gotchas.md` (the item is gone, #12 became
#11: nothing linked to either number), `CLAUDE.md`, `rules/algebra.md`, `CHANGELOG.md` (Added `Field.hash`, Fixed #58).
The older tests' comments saying "Chain has no matching hashCode" (`HomologySpec`, `CubicalStreamSpec`, `NaiveCappedSpec`)
now say why they still match as multisets (a `Set` would collapse duplicate representatives).

## Not done

MiMa: `Field` gains a concrete method (a default method on the interface) and `Chain` an override of `Object.hashCode`; the
0.7 series has an empty baseline, so there is nothing to report against until a 0.7 release exists.
