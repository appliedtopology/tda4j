# Bugs found in external papers / reference implementations

This file is specifically for defects found in *someone else's* published algorithm, paper, or reference
codebase while building or validating a tda4j feature against it — as distinct from tda4j's own bugs (which get
a normal `WORKLOG-*-bug.md`). Kept separate and flat (not folded into CLAUDE.md's per-topic condensation) because
the point is to make every instance easy to find in one place across sessions, not to preserve narrative. Add an
entry here whenever you find one; don't wait to be asked. Each entry: what the reference claims/does, what's
actually wrong, how it was verified (not just suspected), and what tda4j did instead.

## Sheehy's sparse/approximate VR filtration — CJS 2015's own Algorithm 3 vs. its own Section 5.3

Cavanna-Jahanseir-Sheehy 2015 (arXiv:1506.03797), Algorithm 3 (`EdgeBirthTime`) computes an edge's birth from
only its two endpoints, with no check against `vanish` (the radius past which a point's ball is empty). But the
*same paper's* Section 5.3 general-simplex rule (`SimplexBirthTime`) requires clamping every edge to
`<= min` over its own vertices' `vanish` values, else `infinity` — i.e. the paper's own definition of what makes
this a genuine monotone filtration is stricter than what its own edge algorithm computes. Verified by hand, not
just suspected: concrete counterexample `epsilon=1, lambda_i=1, lambda_j=10, d=10`, where Algorithm 3's second
branch returns `8`, but `p_i` vanishes at `4` — for the entire interval `(4, 8)` this "edge" would be included
by Algorithm 3 while `p_i`'s own ball is already empty, non-monotone/inconsistent with the paper's own
definition. tda4j's `edgeBirth` (`streams/SheehyRipsStream.scala`) applies the `vanish` clamp to every edge
directly, matching Section 5.3's general rule rather than Algorithm 3's own unclamped shortcut. Full derivation
and the hand-worked numeric example: `.claude/WORKLOG-sheehy-rips.md`.

## DREiMac's `toroidalcoords.py` — `_gram_schmidt` projects onto the wrong vectors

2026-09-26, cloud session building toroidal coordinates (`.claude/WORKLOG-toroidal-coordinates.md`).
`scikit-tda/DREiMac`'s `_gram_schmidt` (in `dreimac/toroidalcoords.py`, the reference implementation of
Scoccola-Gakhar-Bush-Schonsheck-Rask-Zhou-Perea 2022, arXiv:2212.07201) is supposed to compute the standard
Gram-Schmidt orthogonalization used to drive LLL's size-reduction and Lovász-condition checks. Its inner loop
projects each new vector onto the *original* input basis vectors (`Aj = B[:, j]`) instead of the
*already-orthogonalized* ones (should be `Aj = A[:, j]`) — a well-known copy-paste bug pattern in toy LLL
implementations. Verified numerically, not just by inspection: feeding it `b0=(1,0,0), b1=(1,1,0), b2=(1,1,1)`
produces a third output vector `(-0.5, 0.5, 1.0)` that is **not orthogonal** to the first (`dot = -0.5`), where
the textbook algorithm (projecting onto the running orthogonalized vectors) correctly produces `(0,0,1)`.
Repro kept at the bottom of this entry.

**Consequence, precisely scoped**: this bug is invisible for exactly 2 simultaneously-combined cohomology
classes — the headline "two circles → one torus" case — because for `n=2` there is only ever a `j=0` step, and
`A[:,0] == B[:,0]` identically, so "wrong" and "right" coincide. It only manifests combining **3 or more**
classes at once (which DREiMac's own docstring explicitly anticipates: "it may be of interest to see other
dimensions (e.g. for a torus)"). It does not invalidate DREiMac's output in the sense of producing a
non-unimodular change of basis (that invariant is maintained separately, by construction, regardless of whether
the guiding Gram-Schmidt vectors are correct) — the *isolated* `_gram_schmidt` function is unambiguously wrong
(the repro above proves it produces a non-orthogonal output), independent of what its caller does with it.

**Checked, and did NOT find, an end-to-end failure**: whether the bug actually causes `_lll`'s own *final*
output to fail to be genuinely LLL-reduced (size-reduced + Lovász condition, checked against a *correct*
Gram-Schmidt of that output, independent of whichever Gram-Schmidt `_lll` used internally to get there) is a
separate question from whether the isolated subroutine is wrong — and empirically, the answer on every case
tried is no. Ported `_lll`'s own main loop (not just `_gram_schmidt`) to pure Python and ran it, buggy-GS vs.
correct-GS, on: the specific 3×3 skewed-diagonal fixture this codebase uses in `LatticeReductionSpec`
(`g0=diag(4,9,16)` skewed by unimodular `S=[[1,2,3],[0,1,4],[0,0,1]]`) — both give back exactly `diag(4,9,16)`,
correctly and identically — and 300 random 3×3 integer bases (entries in `[-6,6]`, `seed=42`) — zero cases where
the buggy-GS-guided run's final output failed the true-Gram-Schmidt size-reduction/Lovász check, and none took
more than 60 main-loop iterations either (no near-infinite-loop symptom). A plausible mechanism (not proven):
the discrepancy between buggy- and correct-GS is proportional to how much the *raw* input vectors already
differ from their *orthogonalized* versions, which shrinks as `_lll`'s own main loop makes the working basis
progressively more orthogonal — so by termination (a basis that IS nearly orthogonal, by the algorithm's own
stopping condition), the two GS variants may simply have converged to near-agreement regardless of which one
guided the intermediate steps. **This is a real, proven bug in an isolated subroutine, not a demonstrated
end-to-end correctness bug** — don't cite this entry as "DREiMac's toroidal coordinates gives wrong answers for
3+ simultaneous classes" without a repro backing that stronger claim; no such repro exists yet. If a future
session finds one (a harder adversarial search, larger `n`, or ill-conditioned/near-singular input might), add
it here rather than assuming this note's own search was exhaustive.

**What tda4j did**: implemented textbook LLL from scratch (`homology/LatticeReduction.scala`) with correct
Gram-Schmidt (projecting onto the running orthogonalized vectors), cross-checked against the paper's own
Algorithm 4 (Cholesky factor of the Gram matrix, LLL on the factor's rows) and against Wikipedia's independent
statement of the algorithm — not a port of DREiMac's code. Not reported upstream (out of scope for this
session); flagging here so a future session can decide whether to file it.

Repro (pure Python, no numpy needed):
```python
def dot(u,v): return sum(a*b for a,b in zip(u,v))
def sub(u,v): return [a-b for a,b in zip(u,v)]
def scal(c,v): return [c*a for a in v]
def proj(v1,v2):
    c = dot(v2,v1)/dot(v1,v1)
    return scal(c,v1)

def gram_schmidt_dreimac(B):  # B: list of column-vectors (as lists)
    n = len(B); A = [None]*n; A[0] = B[0][:]
    for i in range(1, n):
        Ai = B[i][:]
        for j in range(0, i):
            Aj = B[j][:]          # <-- bug: should be A[j]
            Ai = sub(Ai, proj(Aj, Ai))
        A[i] = Ai
    return A

B = [[1,0,0],[1,1,0],[1,1,1]]
A = gram_schmidt_dreimac(B)
print(A)                 # [[1,0,0], [0.0,1.0,0.0], [-0.5,0.5,1.0]]
print(dot(A[0], A[2]))   # -0.5 -- should be 0 if A[2] is really orthogonal to A[0]
```

## PH-roadmap's `fractal_9_5_2` distance matrix is not symmetric

2026-10-04, while planning the paper's benchmarks (`.claude/PLAN-paper.md`). The file
`data_sets/roadmap_datasets_distmat/fractal_9_5_2_random_edge_list.txt_0.19795_distmat.txt` in `n-otter/PH-roadmap`
(Otter et al. 2017; the "fractal-r" case of the Ripser paper's benchmark, pulled by `Ripser/ripser-benchmark`'s
Dockerfile) is supposed to be a distance matrix. It is a full 512x512 text matrix with a zero diagonal, but **2,135 of
its 130,816 unordered pairs (1.6%) have `d(i,j) != d(j,i)`**: differences up to 1e-5 absolute and 1e-4 relative, all
of which survive rounding to float32. Verified by scanning the whole file with Python (downloaded from
raw.githubusercontent.com). A 100x100 submatrix had first shown it on 2026-10-03, with 168 of 10,000 entries differing.

**Consequence, precisely scoped**: a tool that reads the lower triangle and one that reads the upper triangle compute
from different inputs, so barcodes can differ by up to ~1e-5 on this case. Cross-tool comparisons need that tolerance,
or a canonicalized input. For tda4j the asymmetry was fatal before the fix: the packed Ripser engine looped forever
(`WORKLOG-o3-1024-fractal-r-session-2026-09-25.md`, 2026-10-03 update). **Not checked**: whether any other tool
misbehaves on it.

**What tda4j did**: `ExplicitMetricSpace.distance(x, y)` reads `dist(min)(max)`, one fixed triangle. The packed
engine's ordering compares indices first, so the same simplex is never two different keys. Not reported upstream.

**Open: the triangle is the wrong one for comparisons.** `dist(min)(max)` is the **upper** triangle. `ripser.cpp`'s
default input format, `distance`, reads "only [the] lower triangular part": row i, columns j < i, per its
`read_distance_matrix` and help text. On this file, then, tda4j and Ripser compute from different inputs. Whether to
switch to `dist(max)(min)`, symmetrize the inputs in the benchmark harness, or both is a decision for the lead
(`PLAN-paper.md` §2.8).

## jvptree 0.3.0's `getNearestNeighbors(q, k)` returns fewer than `k` neighbours on some builds

2026-10-17, while replacing jvptree with our own `VantagePointTree` (`WORKLOG-scalajs-numerics-spatial.md`).
`com.eatthepath:jvptree` 0.3.0, `VPTree.getNearestNeighbors(queryPoint, maxResults)`, can return fewer than
`min(maxResults, n)` points, when `maxResults` is a sizeable fraction of the `n` points. The vantage points are random, so
it varies between builds of the same tree. **Verified** (JVM, 2026-10-17):

- `VantagePointTreeOracleSpec` (`src/test/scalajvm`) builds the tree over the points `0..63` on a line (distance `|a - b|`)
  100 times and asks for the 64 nearest of the point 21: 17 of 100 builds answered with fewer than 64 points (35 on the
  first build tried).
- A sweep over random clouds (n in {5, 20, 33, 40, 64, 100, 150, 300}, dimension 1, 2, 3, 6, three builds each, every third
  point as the query, k in {1, n/4, n/2, 3n/4, n-1, n}) found short answers in 67 of 5,736 queries with k < n/2 (the
  smallest k/n was 0.247; none of the k = 1 queries), 135 of 2,880 with n/2 <= k < 3n/4, 292 of 2,880 with 3n/4 <= k < n-1,
  and 468 of 2,880 for each of k = n-1 and k = n. The points a short answer does contain are not always the nearest ones.
- **Not checked**: k small against a large n (the distance to measure's k = 15 of 50,000 points: ratio 3e-4; nothing in
  the sweep covers that), and the cause inside jvptree (the pruning in `getNearestNeighbors` while the result heap is
  not yet full is the suspect).

**Consequence for tda4j, precisely scoped**: `DistanceToMeasure(space, JVPTree(space), k)` and `AlphaComplexDQP.dtm` (which
built its `DistanceToMeasure` on `JVPTree`) could average over fewer than `k` neighbours when `k` was a sizeable fraction of
the number of points; `BruteForce`, the default of `DistanceToMeasure`, was never affected. Not checked: whether any shipped
example or published number used `JVPTree` with such a `k`.

**What tda4j did**: `VantagePointTree.nearest` is the first `k` of the stable sort by distance (a bounded max-heap searched
with pruning on strict inequality), compared with a linear scan on Euclidean and non-Euclidean metrics in
`VantagePointTreeSpec`. Not reported upstream.

## jvptree 0.3.0 loses a point lying exactly on the radius, and answers some nearest-neighbour queries with the wrong points

2026-10-20, from a CI failure of `VantagePointTreeOracleSpec` (`WORKLOG-vptree-oracle-flake.md`). `com.eatthepath:jvptree`
0.3.0; both defects vary between builds of the same points, because the vantage points come from an unseeded
`java.util.Random` per node. **Verified** (JVM, 2026-10-20), on the 8 x 8 integer lattice with Euclidean distance
`sqrt(sum of squares)`:

- **`getAllWithinDistance(q, radius)` loses a point whose computed distance is the radius.** 121 of 2,000 builds lost at
  least one point (queries every third lattice point, radii `sqrt(0..20)`); in all 121 the lost point's computed distance
  equals the radius exactly; no build returned a point outside the radius. Cause, checked inside 66 of 66 builds that
  lost the point 30 for the query 39 at radius `sqrt(2)`: the node prunes its nearer child unless
  `d(q, v) <= threshold + radius` (bytecode of `VPTreeNode.collectAllWithinDistance`: no allowance for rounding), and with
  the vantage point (0,3) and threshold `sqrt(18)` the collinear points make `sqrt(32) > sqrt(18) + sqrt(2)` by one ulp.
  Random point clouds do not trip it (0 mismatches in 500 builds of `VantagePointTreeOracleSpec`'s first example).
- **`getNearestNeighbors(q, k)` can answer with `k` points that are not the `k` nearest** (the entry above has the
  short answers). Lattice, k = 20 of 64: 43 of 2,000 builds, e.g. distances `3.0, 3.162` where the 19th and 20th nearest are
  both `2.828`. Sweep over random clouds and lattices (261,120 queries): 4,101 short or wrong, none with k <= 16 (184,320
  queries), the smallest failing k was 20.
- **Not checked**: the cause of the wrong nearest-neighbour answers (a reading of `collectNearestNeighbors`, which prunes on
  the distance to the farthest candidate so far with no test that `k` candidates were found, fits k above the leaf
  capacity 16, but no patched build was run); behaviour on metrics other than Euclidean.

**Consequence for tda4j**: none in shipped code (`VantagePointTree` replaced jvptree in `src/main`); the JVM-only oracle spec was
flaky about one run in twelve until it was judged by a linear scan.

**What tda4j did**: `VantagePointTreeOracleSpec` asserts `VantagePointTree` against the linear scan and counts jvptree's
defects instead of asserting them; `VantagePointTreeSpec` pins the one-ulp case against our own pruning slack. Not reported
upstream.
