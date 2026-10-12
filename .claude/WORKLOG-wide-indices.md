# 128-bit or BigInt simplex indices: what H4 on 8,000 points would take (2026-10-04)

Point-in-time snapshot, inventory only: nothing here is implemented.

## The question

Project lead: how much infrastructure would change to move to 128-bit or BigInt indices, for "insane things like H4
over 8k points"?

## The numbers (exact; python `math.comb`)

H4 needs 5-simplices. `C(8000, 6) = 3.634e20`: about 40x past `2^63 = 9.22e18`, and far below `2^127 = 1.70e38`.

Largest number of input points `n` for which every `k`-simplex has an index below the bound:

| simplex dim k | homology degree | n, `Long` (2^63) | n, 128-bit (2^127) |
|---|---|---|---|
| 2 | H1 | 3,810,779 | beyond any `Int` vertex count |
| 3 | H2 | 121,977 | 7,993,834,872 |
| 4 | H3 | 16,175 | 115,344,833 |
| 5 | H4 | 4,337 | 7,047,317 |
| 6 | H5 | 1,733 | 978,282 |
| 8 | H7 | 534 | 73,391 |

128 bits is enough for anything that fits in memory; BigInt is never needed for this.

## Where `Long` is assumed

1. **The Vietoris-Rips streams' tie-break: the generic engines hit the ceiling here first.**
   - `EnumeratingCofaceSimplexStream.filtrationOrdering` = `FiltrationOrdering.canonical(..., Ordering.by(simplexIndexing(_)))`,
     and the same holds in `sortedByFiltration`'s memoized tie-break (`SimplexStream.scala` ~405-418).
   - So even chunks, naive and cohomology on a VR stream compute a `Long` index per comparison, and throw past the bound
     (`SimplexIndexing.binomial` throws on overflow; it never wraps).
   - **Fix that keeps full generality:** compare the sorted vertex arrays colexicographically (largest vertex first).
     - The combinatorial number system is order-isomorphic to colex order on sets of one size, so the order is
       unchanged, and there is no width limit and no binomials.
     - Needs a property test (index order == colex order) before the swap.
     - Cost: likely cheaper than computing an index per comparison. Unmeasured.
2. **The coface streams' enumeration by index** (`RipserCofaceSimplexStream`, `SimplexStream.scala` ~442, 478, 561):
   - they build candidates as `simplexIndexing(ix, d)` from `Long` indices;
   - they would need the wide index type, or the colex route above.
3. **`SimplexIndexing`** (40 `Long` mentions):
   - binomial tables (`Array[Array[Long]]`), `apply`/`decodeToArray`, `searchRow`;
   - `CofacetCursor`/`FacetCursor` (`iA`, `iB`, `index`).
   - This is the core of the change: every arithmetic site becomes 128-bit.
4. **`PackedRipserCohomologyEngine`:**
   - `DiameterIndex(diameter, index: Long)` and its compare;
   - `activeCleared`/`nextCleared: mutable.Set[Long]`;
   - the sparse cofacet iterator and the apparent-pair helpers (via the cursors);
   - the decoders in `Persistence`'s Ripser path.
5. **`RipserCohomologyEngine`** (the test oracle): `compareFvThenIndex(..., Long, ...)`; follows from 3.

## JVM mechanics

- There is no primitive 128-bit integer. Options:
  - **a two-`Long` value class** (`hi`, `lo`):
    - addition with carry by hand;
    - multiplication via `Math.multiplyHigh` / `Math.unsignedMultiplyHigh` (Java 18+) for 64x64->128;
    - comparison as unsigned on `lo`;
  - **`BigInteger`**: allocates on every operation, too slow for the cofacet cursors.
- Binomial tables for 128-bit: `C(n, k)` for `k <= 9` and `n <= 10^7` fits, stored as pairs of `Long` arrays.
- **Collections:**
  - no primitive 128-bit sets or maps; `activeCleared` and the basis maps would box, or need a small custom
    open-addressing table keyed by (hi, lo);
  - `DiameterIndex` grows from 16 to 24 bytes of payload.
- **Making the index type a typeclass parameter** (64 or 128 bits chosen at runtime by `C(n, k + 1)`) keeps the 64-bit
  path fast only if it specializes; a generic `I: IndexArithmetic` in the hot loop will box. Two copies, or inline
  methods, are safer.
  - Needs an A/B before any claim, per the project rules.

## Estimate

- **The colex comparator (item 1):** small, about one file plus a property test. It lifts the ceiling for every generic
  engine, alone.
- **A 128-bit packed path (items 2-5):** medium. `SimplexIndexing` plus the packed engine are about 1.5k lines that
  touch the index; most edits are mechanical once a `WideIndex` value class with
  `+`/`-`/`*`/compare/`binomial` exists. Two cursors carry the arithmetic that needs care.
- **BigInt:** not needed (table above).

## The honest framing

A wider index lifts the ceiling on input points; it does not make the computation feasible. The real wall is the
number of cells. At the enclosing radius, 8,000 points have up to `C(8000, 6) = 3.6e20` 5-simplices, and even a
threshold that keeps 0.01% of them leaves about 3.6e16. Realistic routes to H4 on thousands of points shrink the complex:
- sparse Rips (`SparseRips`, linear size for bounded doubling dimension);
- witness complexes on a few hundred landmarks;
- edge collapse;
- a small `maxFiltrationValue`.

A cell-count probe at plausible thresholds would say which. Dense ids (at most 2^31 materialized cells, any cell type)
and wide packed indices (implicit enumeration over huge vertex sets) solve different problems and complement each
other.
