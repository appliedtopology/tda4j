# Fast engines: representatives without quadratic bookkeeping (2026-10-04)

Point-in-time snapshot. Blocker 2.2 of `PLAN-paper.md`; the project lead asked for a fix if a viable approach existed.

## The problem

`FastCubicalHomologyEngine` and `FastAlphaHomologyEngine` kept, for every dual component, an immutable map from top
cell to coefficient. On every union they:
- copied the surviving component's whole map into a mutable map;
- merged the young one in;
- copied the result back with `.toMap`.

They also built the boundary of the dying region for every pair, including zero-length pairs that were then dropped.

The cost is quadratic as soon as one large component other than `∞`'s absorbs many small ones. `∞`'s map was never
copied, which is why uniform noise looked fine: there, almost every merge lands in `∞`'s component, because border
pixels reach `∞` as soon as they appear.

The common real case is a **bright object on a dark background**: microscopy, a head in an MRI slice. In the descending
dual pass the object becomes one big component long before the dark border connects to `∞`, and every later merge copies
it.

## Measured before (same machine, `FastCubicalProfileDriver`, F_17, median of 3, one JVM per run)

The machine is a 4-core Xeon at 2.1 GHz in the cloud sandbox. `blob` is a Gaussian bump plus 5% noise; `random` is
uniform noise.

| image | n=100 | n=200 | n=300 |
|---|---|---|---|
| random | 0.28 s | 1.87 s | 3.28 s |
| blob | 4.65 s | **79.6 s** | **453.8 s** |

Blob costs 17x from 100² to 200², and 5.7x from 200² to 300²; the pixel count grows 4x and 2.25x. That is
quadratic.

## The fix: a signed union-find with a merge forest (`homology/SignedUnionFind.scala`)

- **Orientation per top cell.** Each top cell carries an orientation, a field unit, relative to the root of its
  component. `find` compresses paths and composes orientations along them, so `orientation(c)` is amortised
  near-constant.
- **Unions don't touch members.** A union hangs the young root under the old one with the solved `flip`. It never visits
  the members.
- **Members come from the merge forest.** Each root keeps a children list, which path compression does not alter. A
  root's subtree is frozen once it has been merged away, so `members(root)` walks it and multiplies the flips along the
  way.
- **Representatives are built only for reported bars.** A reported bar's representative is the boundary of
  `Σ flip · orientation(c) · c` over the dying region. The boundary is assembled before the union, and only when the bar
  is reported: zero-length pairs that are filtered out never pay for one.
- **Cost.** Near-constant per merge, plus the size of the region for each reported bar. The worst case is still the
  sum of the reported regions' sizes, which is the price of an explicit region per bar. It is bounded by (number of
  pixels) × (nesting depth of the reported regions). Representatives are not materialized lazily on read:
  `PersistenceBar.annotation` is a strict `Option`, and making it lazy would be an API change.
- **Both engines share it.** The flip formula and both earlier bug fixes are unchanged:
  - the resolved old root, not the raw id, decides the `∞` case;
  - `∞` is always the elder.

## Gate

The previous implementations moved verbatim to the test tree as `EagerFastCubicalReference` and
`EagerFastAlphaReference`. `FastRepresentativesSpec` requires the production engines to return **equal lists**: the
same bars, the same representatives term for term, in the same order.

The inputs are 12 seeds × 5 images:
- integer ties;
- noise;
- blob;
- missing pixels at `+∞`, which tie against `∞`;
- a 3-D grid, which runs the chunks hybrid.

Each image is checked over F_2, F_3 and F_17, with zero-length bars on and off. On top of that:
- random Helix triangulations, 10 seeds each in 2-D and 3-D, over F_3 and F_17. More than half of them must produce bars
  rather than both engines rejecting the triangulation, so the check is not vacuous.
- Every top-degree representative must be closed over F_3, a check the reference cannot share a bug with.

The spec passes, and `FastCubicalHomologySpec` and `FastAlphaHomologySpec` pass unchanged.

## Measured after (same setup)

| image | n=100 | n=200 | n=300 | n=400 | n=600 |
|---|---|---|---|---|---|
| random | 0.23 s | 1.28 s | 3.35 s | 4.05 s | 11.5 s |
| blob | 0.20 s | **0.82 s** | 2.29 s | 6.03 s | 10.6 s |

- Blob at 200² goes from 79.6 s to 0.82 s, about 97x; at 300², from 453.8 s to 2.29 s, about 200x. Random is
  unchanged within noise.
- At 600² (360k pixels) both images cost ~30 µs per pixel.
- For comparison, the general dense cohomology engine (`CellularCohomologyEngine`, cycles) at n=200/400: 3.6 s / 12.7 s
  on random, 2.3 s / 20.1 s on blob. The fast engine now beats it at every size measured; before the fix it did not even
  at 200².

## Where the time goes now (JFR, 400² blob, 2 runs)

- `computeH0`, the primal union-find, takes 66% of the samples:
  - most of it is `Vector.sorted` of the edges under the stream's generic `FiltrationOrdering`;
  - each comparison looks up filtration values through `CubicalGridStream`'s `HashMap` of `Cube`s and computes
    `nondegenerateAxes` and `dim`;
  - stream materialization (`iterateDimension`) is part of it.
- The dual pass is 34%.

**The next lever, not done here:** compute `H_0` from flat arrays of grid values, as the dual pass mostly does.
- Pixels give the vertex and edge values directly: in the T-construction a cell's value is the minimum over its
  containing top cells.
- Sort edge indices by (value, the stream's tie-break) on primitive arrays.

To keep `FastRepresentativesSpec`'s exact equality, the tie-break must reproduce the stream's `filtrationOrdering`
exactly. The expected gain is a few times, but that is unmeasured. Until it is done, the cubical comparison against
CubicalRipser in the paper will show a large constant factor.

## Files

- `homology/SignedUnionFind.scala` (new, `private[tda4j]`)
- `homology/FastCubicalHomology.scala`
- `homology/FastAlphaHomology.scala`
- test: `EagerFastCubicalReference.scala`, `EagerFastAlphaReference.scala`, `FastRepresentativesSpec.scala`,
  `FastCubicalProfileDriver.scala`
