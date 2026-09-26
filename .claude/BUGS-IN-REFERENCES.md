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
the guiding Gram-Schmidt vectors are correct) — it just means the reduction achieved for 3+ simultaneous classes
may not actually be LLL-reduced (size-reduced + Lovász condition against the *true* Gram-Schmidt vectors), i.e.
a correctness-of-optimality bug, not a soundness bug.

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
