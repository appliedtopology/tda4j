# DQP alpha backend: why it was slow with a high degree cap, and the int-array rewrite (2026-10-28)

Point-in-time snapshot. Trigger: Mikael's server sweep, interior n = 60, d = 10, up to H_9: Helix 465.9 s, DQP 3151 s,
35 GB. Server data points: Helix d = 6/7/8 in 1.5/5.8/26.5 s, DQP d = 6/7 in 4.9/22.3 s, d = 8 in 110 s.

## Finding
Not a blow-up: the cell counts at d = 8 (about 1.8 M cells) are those both backends walk, and the cost per cell was large.
The bar counts of Helix and DQP agree for bars longer than 1e-6 (d = 6: 311/501/370/127/13 in degrees 1 to 5 in both) and
differ by a bar or two shorter than about 1e-8 (near-ties, unchanged by this rewrite). DQP was also effectively
single-threaded (1/2/4 threads: 27.8/36/31.7 s at d = 7, before the change). The time was NOT in the QP solves. JFR
(inclusive frames, 4 cores, d = 7) put it in
1. the candidate screening: every candidate of dimension k was a tree-based `Simplex`, hashed and compared in `present`
   (a `HashMap[Simplex, ...]`) once per facet;
2. the final `sortInPlace` of the cells, whose comparator looked up two tree simplices in a `HashMap` per comparison;
3. `clampMonotone`, which did the same lookups per face.

## Change (`alpha/AlphaComplexDQP.scala`)
- Candidates, found cells and their facets are rows of an `Int` array (`CandidateRows`), the face tables are
  `DelaunayAlphaShapes.FaceTable` (open addressing, `indexOf`), the weights live in a `Double` array per dimension.
- The vertex solve reads candidate rows directly; the clamp walks facet rows; the final order is a `Parallel.sort` over
  an `Array[java.lang.Integer]` of row numbers with the comparator (value, then descending `simplexOrdering` read off the
  rows). `Simplex` values are made once, at the end.
- Public signatures of `buildCandidates`, `solveAtVertex`, `clampMonotone` and `Found` change; `allFacetsPresent` is gone.

## Measurements (cloud container, 4 cores, 60 interior points, one draw, barcodes md5-identical to the old build at d = 5, 6, 7)
| d | old | new | Helix |
|---|---|---|---|
| 6 | 10.1 s | 2.4 s | 2.9 s |
| 7 | 32.9 s | 5.5 s | 5.9 s |
| 8 | 105 s | 23.5 s | 22.4 s |
Peak heap at d = 8: 5389 MB to 2449 MB. A first version that only changed the screening gave d = 7 in 22 s: the sort and
the clamp were the rest.

## Spec
`AlphaComplexDQPOrderSpec`: the order contract (ascending value, ties by descending `simplexOrdering`) in 3 to 5
dimensions and on a grid (degenerate), faces present with no larger value, cells and values equal to Bowyer-Watson in
general position, `parallel = true` identical to `false`. Mutation check: reversing the tie comparison fails the first
example.

## Open
- `Auto` still picks DQP whenever a radius is given and ignores `maxDimension`. Whether DQP should be chosen only for a
  capped computation waits for the `--path four` sweep numbers (#135).
- Threads: the server's 32 cores were no better than my 4 before the change; re-measure the scaling on the new code.
