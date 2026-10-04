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

## Later the same day: the representatives were wrong whenever a merge missed `∞`

While planning a flat-array rewrite, I re-read the merge code and found a bug that both the old code and the port
above shared:
- **The cause:** the orientation flip looked up `facet.boundary.getOrElse(topCell, zero)`. That is the coefficient of a
  top cell in the **facet's own boundary**, which holds only the facet's faces, so the result was always 0.
- **When `∞` is the surviving side**, the flip is defined as 1, so those bars were fine.
- **On every other merge, the flip was 0:**
  - the dying bar's representative was the zero chain;
  - the absorbed region's coefficients became 0, so later representatives of that component lost their interior
    cancellation and had cells from after the bar's birth.
- **Why the tests missed it:**
  - `FastRepresentativesSpec`'s equality check shares the bug with its reference;
  - "closed over F₃" is true of the zero chain;
  - the hand-built fixtures in `FastCubicalHomologySpec` mostly merge into `∞`.

**The fix:** the coefficient is read from the **top cell's** boundary. It is the coboundary entry the derivation
intended: `coeffToward(top)` finds `facet` in `top.boundary`. Applied in four places:
- `FastCubicalHomologyEngine`;
- `FastAlphaHomologyEngine`;
- both test references, so the equality gate stays a correct oracle (their docs say so).

**The new gate** (two examples in `FastRepresentativesSpec`, independent of any reference): every top-degree
representative of the fast cubical and fast alpha engines must be:
- non-zero;
- closed;
- made of cells present at the bar's birth;
- for cubical, with one cell entering exactly at birth.

The inputs are tie-heavy, noisy, blob, missing-pixel and 3-D images over F₃ and F₁₇, and 2-D and 3-D Helix clouds.
- **Before the fix:** the gate failed with zero representatives, and with cells entering after birth.
- **After:** all 31 tests in `FastRepresentativesSpec`, `FastCubicalHomologySpec` and `FastAlphaHomologySpec` pass.

**The lesson:** an equality-with-reference gate must be paired with a validity check that a shared bug cannot pass.

## Lever 1: the fast cubical engine on flat arrays (2026-10-04, later)

What changed (`FastCubicalHomology.scala`, plus a new `SortIndices`, a boxing-free stable merge sort of indices):
- **Both union-finds work on flat arrays:**
  - pixels row-major;
  - vertices and edges in mixed radix over the vertex grid;
  - facets as (axis, lower corner).
- **Values come straight from the pixels:** a cell's value is the minimum over its containing pixels.
- **Orientation coefficients are closed-form**, from `cubeIsOrderedCell`'s rule: along axis `a` the upper face has sign
  `+1` for even `a` and `−1` for odd `a`, the lower face the opposite.
- **Region boundaries are summed by facet key** in a `LongMap`, so interior facets cancel; cubes are built only for the
  facets that remain.
- **The order is reproduced exactly:**
  - `H_0` edges: value ascending, then encoding descending;
  - dual events: value descending, top cells before facets, then coordinates or encoding ascending.

  The doubled-coordinate encoding becomes a `Long` with weights `∏ (2 shape + 1)`.

Gate: `FastRepresentativesSpec`'s equality with the eager reference passes term for term, as do its validity
examples, `FastCubicalHomologySpec`, `CubicalStreamSpec` and `ImagesSpec`.

Measured with `FastCubicalProfileDriver`, F₁₇, median of 3, same sandbox:

| image | after the quadratic fix | `H_0` on arrays | both passes on arrays |
|---|---|---|---|
| noise 400² | 4.05 s | 2.78 s | 1.05 s |
| noise 1000² | not run | not run | 13.9 s |
| blob 400² | 6.03 s | 2.83 s | 0.83 s |
| blob 1000² | not run | not run | 7.1 s |

What remains, from a JFR profile of 700² noise taken before region boundaries moved to arrays:
- representative assembly was then about half the time;
- region boundaries now go through the `LongMap`, but each reported bar still walks its region.

That is the price of a representative per bar, which CubicalRipser and GUDHI do not pay. 1000² noise has about 380k
`H_1` bars.

## Combining the union-finds with cohomology in 3-D and up (2026-10-04, later)

The project lead asked whether the union-finds should combine with the cohomology approach. They should; the 3-D
hybrid had them combined with **chunks** for the middle degrees, which the size sweep (`bench/sweep_cubical.py`) showed
losing badly:
- on 32³ noise, the hybrid took 58.8 s against 8.7 s for plain cohomology on the whole image;
- the hybrid's µs per voxel grew with size, from 582 at 16³ to 1795 at 32³.

**Step 1 (done): the middle degrees go to the cohomology engine.**
- `computeMiddleDimensions` in both fast engines runs `CellularCohomologyEngine.persistentHomology` on the truncated
  view and keeps degrees `<= d-2`; the representatives are cycles by the involution.
- The views, `LimitedCubicalGridStream` and `LimitedAlphaShapesStream`, now declare
  `homologyDegreeLimit = maxDim - 1`, as the stream rules require of truncating wrappers. The involution therefore
  skips the artificial top degree.
- The equality references were switched identically; they check the dual union-find, and the middle degrees are
  shared code.

Same sweep images, warm median of 3 in the sandbox:

| image | old hybrid | new hybrid | plain cohomology |
|---|---|---|---|
| noise 16³ | 2.38 s | 0.46 s | 0.62 s |
| noise 24³ | 14.2 s | 1.55 s | 2.36 s |
| noise 32³ | 58.8 s | 5.45 s | 5.90 s |
| blob 32³ | 42.6 s | 2.85 s | 9.57 s |

**Step 1b (done): `CubicalGridStream` values come from a flat array.**
- A JFR profile of the new hybrid on 32³ noise: about 35% of the time was spent sorting cells through the generic
  comparator, each comparison hashing a `Cube` into the value cache and walking `nondegenerateAxes`. The reduction was
  about 14%, the involution about 14%.
- `CubicalGridStream` now precomputes every cell's value once, into an array over the doubled-coordinate grid. The
  minimum over containing pixels is separable: one min pass per axis over the even positions.
- `filtrationValue` is an index computation. The `HashMap` cache, `containingTopCells` and the parallel warm-up are
  gone; `parallelFiltrationValue` now parallelizes reading the pixels.
- **Effect:**
  - the fast hybrid at 32³: noise 5.45 → 4.03 s, blob 2.85 → 2.07 s;
  - plain cohomology at 32³: inconclusive. Trials within one JVM ranged from 3.5 to 12.9 s, medians about 5.3 s with
    the change against 5.7 s without, which this sandbox cannot resolve. Re-measure on a quiet machine.

**Step 2, not done: union-find pairs as clearing and compression in the middle reduction.**
- The primal union-find's killing edges are pivots of the degree-0 coboundaries, so they can be cleared from the
  degree-1 columns.
- The dual union-find's (d−1)-cell births are never pivots of the degree-(d−2) coboundaries, so they can be removed
  as rows. This is the coboundary dual of Bauer–Kerber–Reininghaus compression.
- With the reduction now at about 14% of the time, the expected gain is bounded by that share; the stream
  materialization and the involution come first.

Gates: `FastRepresentativesSpec` (equality plus validity), every `*Cubical*` spec, `ImagesSpec`, `TDA4jSpec` and
`PersistenceVerbSpec` pass; `testFull`: 924 tests, 0 failures.

**Consequence for the open "3-D `Auto` default" question:** fast cubical, now this hybrid, is the right default again.
It beats plain cohomology at every size measured.
