# Handoff: TDA4j alpha complex + Simplex opaque type

Written for a fresh Claude instance picking up this work. Read the whole thing
before touching code; several things that look like bugs are deliberate, and
several things that look fine are not.

---

## 1. Who and what

- The user is the **project lead of TDA4j** (https://github.com/appliedtopology/tda4j/),
  a Scala rewrite and expansion of the JavaPlex topological data analysis library.
- Default branch is `scala`. Package is `org.appliedtopology.tda4j`.
- The codebase uses **Scala 3.6+ syntax**: `X is Y` type syntax, named context
  bounds (`[CoefficientT: Field as fr]`), new given syntax
  (`given foo: [T: Ordering] => (X is Y) = ...`). Do not "modernise" or
  "de-modernise" this; match what is already in their files.
- Their stated conversation preferences: intellectual honesty, direct pushback,
  no sycophancy. They want to be told when an approach is a dead end. They
  responded well to being told the paper's own benchmarks are mixed.

### Environment constraint (important)

The sandbox is a **Linux container with no network and no Scala toolchain**.
`bash_tool` networking is disabled; there is no `scalac`, `scala`, `scala-cli`,
`coursier`, or `scala-library.jar` anywhere on the filesystem. Java 21 is
present but useless without the Scala library.

The user mentioned `/Users/mik/.sdkman/candidates/scala/current/bin/scala`.
**That is on their Mac, not reachable from the sandbox.** I verified `/Users`
does not exist in the container. Do not try again; say so plainly if it comes up.

**Consequence: none of the Scala produced in this session has ever been
compiled.** All validation was done by writing a Python twin and testing that.
This is the single most important open item.

---

## 2. Deliverables produced

| File | Status |
|---|---|
| `AlphaComplex.scala` | Main implementation. Never compiled. |
| `AlphaComplexBridge.scala` | 10-line bridge to their `Simplex` type. Never compiled. Most likely thing to break. |
| `AlphaComplexTest.scala` | Standalone test harness, no framework. Never compiled. |
| `Simplex.scala` | Rewrite of their uploaded file: opaque type + bridges only. Never compiled. |
| `SimplexOps.scala` | The pass-through facade split out of the above. Never compiled. |
| `reference_oracle.py` | Python twin of the QP solver + alpha complex. **Runs, passes.** |
| `reference_tests.py` | 10-part validation suite. **Runs, prints `ALL PASS`.** |

Run the Python suite with `python3 reference_tests.py` (needs numpy + scipy;
takes a few minutes). It is the ground truth for the Scala port.

---

## 3. Work item A — the alpha complex

### Source

Erik Carlsson & John Carlsson, *Computing the alpha complex using dual active
set quadratic programming*, Scientific Reports **14**:19824 (2024),
https://doi.org/10.1038/s41598-024-63971-3. The user uploaded the PDF.

The QP solver reference they pointed at is DAQP (Arnström, Bemporad & Axehill,
IEEE TAC 67(8):4362–4369, 2022), https://github.com/darnstrom/daqp — that is
reference [27] of the paper. Useful observation made to the user: the paper's
problem (9) is **already in DAQP's canonical inner form** (H = I, a least
distance problem), so DAQP's factorisation of H is unnecessary and its recursive
LDL^T updates collapse to Cholesky update/downdate of `B_W`.

### The maths, so you don't have to re-derive it

Base vertex `x`, neighbours `x_i`, power weights `p`. Primal QP (13) becomes
Eq. (9) with

```
A_i  = (x_i - x)^t
V_i  = ½(‖x_i‖² - ‖x‖² - p(x_i) + p(x))
B_ij = (x_i - x)·(x_j - x)
U_i  = ½(p(x_i) - p(x) - ‖x_i - x‖²)
c1   = (a1 + p(x))/2          cutoff
w(σ) = 2c* - p(x)             filtration value = the POWER, not the radius
Φ(σ) = x - Σ λ*_i (x_i - x)   witness map, KKT (12)
```

Dual (10): maximise `-½λᵀBλ + Uᵀλ` s.t. `λ_i ≥ 0` for `i ∉ J`. The gradient
`∇d_i = U_i - (Bλ)_i = A_i y - V_i` is the slack of constraint `i`, so
"most violated constraint" = argmax gradient.

**Divergence from the paper worth keeping:** the algorithm needs only *squared
distances*, not dot products as the paper frames it:
`B_ij = (d²(i,x) + d²(j,x) - d²(i,j))/2`. So the input abstraction
(`PowerDistance`) sits on squared distance and their `FiniteMetricSpace` can
feed it directly. Caveat: `B` is PSD only for Euclidean-embeddable metrics.

### Two real bugs found and fixed (do not reintroduce)

**1. The singular step.** The paper delegates this to DAQP without describing
it, and my first implementation was wrong. When the entering constraint `j` is
linearly dependent on the active set, the dual has no curvature along the
entering direction. You must carry `λ_j` through the ratio test and **swap** the
blocking constraint out for `j` (set `λ_j = t_max`, drop the blocker, then
re-append `j` — which now succeeds because `r_kb ≠ 0` guarantees independence).
Dropping the blocker *without* assigning `λ_j = t_max` makes the dual objective
**decrease** and the solver cycles forever. Symptom: "dual QP did not converge"
on a 60-point planar cloud.

**2. Tolerance scale floored at 1.** `DualQP.solve` originally did
`var uScale = 1.0; ... if |u(i)| > uScale then uScale = |u(i)|`. For a cloud of
unit diameter this is harmless; for a cloud of *small* diameter it makes
`gradTol` effectively absolute, the solver stops one violated constraint early,
and you get a **silently wrong filtration value**, not a crash. Measured: 1.7e-2
relative error on a 35-point cloud scaled to 1e-4. Now the scale is derived from
`max(|U|, diag B)` with no floor. Scale invariance went from "fails below 1e-3"
to "holds 1e-8 … 1e4".

### What has actually been validated (in Python)

- **Exact agreement** — simplex sets *and* filtration values — with an
  independent scipy `Delaunay` + Gabriel/coface ground truth in R² and R³.
- Individual weighted QPs match `scipy.optimize` SLSQP.
- The `c1` early-exit changes nothing but speed (2347 vs 2347 simplices, `|dw| = 0`).
- S² sampled into R⁵ → Betti (1,0,1); T² in R⁴ → (1,2,1).
- Embedding independence: R³ vs an isometric copy in R⁴³ agree to 8.6e-15.
- Face closure and filtration monotonicity, weighted and unweighted.
- Witness map is power-equidistant to 6e-16 and globally minimal.
- Cospherical grids (see the hazard below).

### Design points a reader will otherwise re-litigate

- **Filtration values are squared radii** (powers), per Definition 10.
  `AlphaComplex.radiusOf` takes the sqrt. Unweighted only.
- **Čech-neighbour restriction is exact, not an approximation.** If `U_x` and
  `U_z` are disjoint then any `y` with `π_x(y) ≤ a1` already satisfies
  `π_x(y) ≤ π_z(y)`. A *superset* of neighbours is always safe (extra candidates
  get rejected; extra constraints are genuine). A subset is not. The Čech test
  is therefore deliberately generous by a relative epsilon.
- **`cechNeighbours` is `protected`, and `AlphaComplexBuilder` is deliberately
  not `final`,** so the O(N²) default graph build can be swapped for a spatial
  index. At the paper's Conf₄ scale (N = 20232) that build is ~2e8 distance
  evaluations before any QP runs. This is the first performance thing to replace.
- `B` and `U` are rebuilt once per (dimension, vertex), matching the paper.
  Caching costs O(N·deg²) memory.
- `enforceMonotonicity` clamps `w(σ)` up to the max over its facets.
  Mathematically a no-op (a face's QP has fewer equality constraints, hence a
  larger feasible set and smaller optimum) but removes ~1e-16 violations that
  upset persistence algorithms.

### The degeneracy hazard (worth repeating to the user)

In degenerate position the alpha complex is **not** a Delaunay subcomplex. `k`
cospherical sites share a Voronoi vertex and contribute a `(k-1)`-simplex:

- unit grid in the plane → **3-simplices** (one per unit square)
- unit grid in R³ → **7-simplices** (one per unit cube)

Truncating at the ambient dimension gives the **wrong homotopy type**, not a
truncated one (6×6 grid truncated at dim 2 gives χ = 26 instead of 1). The test
harness asserts this explicitly so the hazard is pinned rather than just
documented. CGAL/GUDHI users will not expect it.

### Honest assessment given to the user

The paper's own timings are mixed. Ripser beats it on two of four persistence
examples (1.5 s vs 38 s on the image-patch circle); the Stanford bunny is slower
than qhull. The genuine wins are (i) ambient dimension where Delaunay is
impossible, (ii) exact homology rather than persistence diagrams, (iii) far
smaller complexes than Vietoris–Rips when data sits near a low-dimensional
subspace. It complements Ripser; it will not beat it on standard benchmarks.
The user did not push back on this.

### Test vectors hardcoded in `AlphaComplexTest.scala`

Generated by a **SplitMix64** stream reproduced bit-for-bit on both sides
(`state += 0x9E3779B97F4A7C15`, two xor-multiply rounds, `(z >>> 11) * 2^-53`).
So sizes and fingerprints are *exact* expectations, not statistical. All five
cloud cases were regenerated after the tolerance fix and came out unchanged;
the four low-dimensional ones still agree with scipy Delaunay.

```
seed=12345 N=40 R^2 r=0.35  sizes (40,105,66)             fp  16.585490666935
seed=777   N=60 R^2 r=0.25  sizes (60,157,98)             fp   7.220046796648
seed=2024  N=35 R^3 r=0.45  sizes (35,170,243,107)        fp  -5.638333621877
seed=99    N=30 R^3 r=0.55  sizes (30,146,212,95)         fp  11.766111717560
seed=5150  N=50 R^5 r=0.80  sizes (50,638,2636,4661,3723) fp -68.456311816485
fib sphere N=120 r=0.40     sizes (120,354,236,0)  χ=2    fp  -9.136199168553
fib sphere N=200 r=0.32     sizes (200,594,396,0)  χ=2    fp  -3.271266267809
grid 4^2 r=0.8 md=4         sizes (16,42,36,9,0)          fp   1.125250904757
grid 6^2 r=0.8 md=4         sizes (36,110,100,25,0)       fp  -0.338915105578
grid 3^3 r=0.95 md=8        sizes (27,158,400,548,448,224,64,8,0) fp -57.501473422747
```

### Known limits / things deliberately NOT claimed

- The scale-invariance test is pinned to **1e-4 … 1e4** because that is the
  range verified against the oracle. Above 1e4 the answer drifts and I did not
  chase down why. The test comment says "verified envelope", not "guarantee".
  If you have compiler access, this is worth investigating.
- A caution about provenance: partway through, `reference_oracle.py` was found
  to have drifted from the version originally validated (it had acquired a `cap`
  field and half-relative tolerances). It has since been **reconciled
  tolerance-for-tolerance with the Scala** and all vectors regenerated. Do not
  assume any further edits stay in sync; re-run `reference_tests.py` after
  changing either side.

---

## 4. Work item B — the `Simplex` opaque type

The user uploaded `Simplex.scala` and reported **infinite loops**: extension
methods delegating to `underlying` were redirecting to themselves.

### Diagnosis (confirmed, specific)

An opaque type is opaque only *outside the scope that defines it*, and for a
top-level definition that scope is **the whole file**. So inside `Simplex.scala`,
`Simplex[V]` and `SortedSet[V]` are literally the same type. A delegation
`underlying.foo` therefore falls back to extension-method resolution whenever
`SortedSet` has no member `foo` — and finds the very extension being defined.

Exactly **three** methods were affected. Verified against the 2.13
`SortedSetOps` / `SortedOps` source: it provides `firstKey`, `lastKey`,
`minAfter`, `maxBefore`, `iteratorFrom`, `unsorted` — and no `first`, no
`firstOption`, no `maxAfter`.

| Offender | Correct target |
|---|---|
| `def first = underlying.first` | `head` (or `firstKey`) |
| `def firstOption = underlying.firstOption` | `headOption` |
| `def maxAfter(k) = underlying.maxAfter(k)` | `maxBefore` — `minAfter`'s real counterpart |

The other ~65 delegations worked only because each name happened to be a real
`SortedSet` member. The pattern was a landmine, not a design error.

### Fix delivered

Split into two files so the facade lives where the type is genuinely opaque:

- **`Simplex.scala`** — the opaque type, the companion (`from`, `apply`, `empty`,
  `unapplySeq`, `toSortedSet`), the two bridges (`underlying`, `asSimplex`), `∆`,
  and `simplexOrdering`.
- **`SimplexOps.scala`** — every pass-through method, plus the `OrderedCell`
  instance.

`simplexOrdering` **must stay in `Simplex.scala`**: `sortedSetOrdering` produces
an `Ordering[SortedSet[V]]`, `Ordering` is invariant, so that value is an
`Ordering[Simplex[V]]` only where the two types coincide.

In `SimplexOps.scala` a delegation to a non-existent name is now a **compile
error** instead of a stack overflow. Top-level definitions in a package are
visible across files in that package, so `underlying`/`asSimplex` resolve
without imports.

### Alternative offered but not chosen

`opaque type Simplex[V] <: SortedSet[V] = SortedSet[V]` — deletes ~85% of the
file, every `SortedSet` method available for free, loops structurally
impossible. Trade-off: no curated subset, set-returning methods give back
`SortedSet[V]` not `Simplex[V]`, and you **cannot** narrow a return type by
extension (a member always wins). The user asked for a curated subset with
narrowed return types, so the two-file version was delivered. Mention this
option if they ever tire of maintaining the facade.

### Separate bug flagged in their `OrderedCell` instance

Their `boundary` was:

```scala
spx.zipWithIndex.map((vtx, i) => spx.dropIndex(i)).toSeq
  .zip(Iterator.unfold(fr.one)(s => Some((s, fr.negate(s)))))
```

`zipWithIndex` on a set returns a `Set`, and `Set` has **no iteration-order
guarantee above four elements** (it switches from `Set4` to `HashSet`). So for
simplices of **dimension ≥ 4** the alternating signs `(-1)^i` were attached to
faces in hash order. The result is a boundary matrix that is wrong but
well-formed, and it passes every low-dimensional test. Rewritten to enumerate
`(0 until spx.size)`, an ordered range.

**This has not been confirmed by the user and has not been compiled or run.** It
should be the first thing they check — it would corrupt H₃ and above.

---

## 5. Immediate next steps

1. **Get the Scala compiled.** Ask them to run, on their machine:
   ```
   scala run AlphaComplex.scala AlphaComplexTest.scala
   ```
   (leave `AlphaComplexBridge.scala` out until their `Simplex` API is reconciled).
   The harness prints `N checks, M failures` and exits nonzero on failure.
   Then `Simplex.scala` + `SimplexOps.scala` against their tree.
2. Expect and fix syntax/type errors. Highest-risk spots, in order:
   - `AlphaComplexBridge.scala` — guesses their `Simplex` API. Their actual
     signature is `Simplex.from[VertexT: Ordering, T <: Seq[VertexT]](vertices: T)`,
     so `Simplex.from(cell.toSeq)` should work, but check.
   - `while true do ... end while` followed by `Double.NaN` in `DualQP.solve`
     (the unreachable trailing expression that gives the method its `Double` type).
   - `new java.util.function.IntConsumer:` anonymous-class syntax in the parallel path.
   - Explicit type arguments on `min[B]`/`max[B]`/`minOption[B]`/`toArray[B]` in
     `SimplexOps.scala` — added because inference would otherwise look for
     `Ordering[VertexT]` when only `Ordering[B]` is in scope.
   - `Iterator.unfold`, `Field as fr`, `is OrderedCell` all come from their tree.
3. Once it compiles, re-check the scale-invariance envelope above 1e4.
4. Replace the O(N²) Čech graph build before running anything at real scale.

## 6. Tone note

Lead with what is broken or unverified. The user has repeatedly been given
uncomfortable findings (mixed benchmarks, an uncompiled deliverable, a drifted
oracle, a bug in their own boundary map) and engaged with all of them. Do not
soften.
