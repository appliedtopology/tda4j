import math
import numpy as np
from itertools import combinations
from ref_alpha import alpha_complex, DualQP


def betti_mod2(by_dim, simp, maxdim):
    """Betti numbers over F_2 by rank computation."""
    idx = [{s: i for i, s in enumerate(sorted(b))} for b in by_dim]
    ranks = []
    for k in range(1, maxdim + 1):
        rows, cols = len(by_dim[k - 1]), len(by_dim[k])
        if rows == 0 or cols == 0:
            ranks.append(0)
            continue
        M = np.zeros((rows, cols), dtype=np.uint8)
        for s, c in idx[k].items():
            for d in range(len(s)):
                f = s[:d] + s[d + 1:]
                M[idx[k - 1][f], c] ^= 1
        # F_2 gaussian elimination
        r = 0
        for c in range(cols):
            piv = None
            for rr in range(r, rows):
                if M[rr, c]:
                    piv = rr
                    break
            if piv is None:
                continue
            M[[r, piv]] = M[[piv, r]]
            for rr in range(rows):
                if rr != r and M[rr, c]:
                    M[rr] ^= M[r]
            r += 1
        ranks.append(r)
    betti = []
    for k in range(maxdim + 1):
        nk = len(by_dim[k])
        rk = ranks[k - 1] if k >= 1 else 0          # rank d_k
        rk1 = ranks[k] if k < len(ranks) else 0      # rank d_{k+1}
        betti.append(nk - rk - rk1)
    return betti


def test_degenerate_grid():
    """Cospherical / cocircular points: the classic Delaunay degeneracy."""
    ok = True
    for m, side, r in [(2, 6, 0.8), (3, 4, 0.95)]:
        g = np.stack(np.meshgrid(*[np.arange(side, dtype=float)] * m, indexing="ij"), -1)
        X = g.reshape(-1, m)
        simp, by_dim = alpha_complex(X, None, r * r, m)
        counts = [len(b) for b in by_dim]
        chi = sum((-1) ** k * c for k, c in enumerate(counts))
        closed = all(s[:d] + s[d + 1:] in simp
                     for s in simp for d in range(len(s)) if len(s) > 1)
        mono = all(simp[s[:d] + s[d + 1:]][0] <= w + 1e-9
                   for s, (w, _) in simp.items() for d in range(len(s)) if len(s) > 1)
        good = closed and mono and chi == 1
        ok &= good
        print(f"  grid {side}^{m} r={r}: sizes {counts} chi={chi} "
              f"closed={closed} monotone={mono} {'OK' if good else 'FAIL'}")
    # exact duplicates / near-duplicates
    rng = np.random.default_rng(2)
    X = rng.random((20, 2))
    X = np.vstack([X, X[:3] + 1e-12])
    try:
        simp, by_dim = alpha_complex(X, None, 0.3 ** 2, 2)
        print(f"  near-duplicate points: sizes {[len(b) for b in by_dim]} (no crash)")
    except Exception as e:
        print(f"  near-duplicate points: FAIL {type(e).__name__}: {e}")
        ok = False
    return ok


def test_early_exit_safety(N=45, m=4, r=0.62, seed=9):
    """Running with the cutoff must give exactly the same complex as running
    with no cutoff and filtering afterwards."""
    rng = np.random.default_rng(seed)
    X = rng.random((N, m))
    p = rng.random(N) * 0.03
    a1 = r * r
    with_cut, _ = alpha_complex(X, p, a1, 3)
    # rerun with the cutoff only used for the Cech graph, not inside the QP
    import ref_alpha
    orig = DualQP.solve
    DualQP.solve = lambda self, B, U, J, c1, tol=1e-10, mi=None: orig(self, B, U, J, 1e18, tol, mi)
    try:
        no_cut, _ = alpha_complex(X, p, a1, 3)
    finally:
        DualQP.solve = orig
    same_keys = set(with_cut) == set(no_cut)
    worst = max((abs(with_cut[s][0] - no_cut[s][0]) for s in with_cut if s in no_cut),
                default=0.0)
    good = same_keys and worst < 1e-12
    print(f"  early exit vs no cutoff: {len(with_cut)} vs {len(no_cut)} simplices, "
          f"max |dw|={worst:.2e}, {'OK' if good else 'FAIL'}")
    return good


def test_high_dim_sphere(N=90, m=5, seed=4):
    """Points on S^2 embedded in R^5 by a random isometry: expect b = (1,0,1)."""
    rng = np.random.default_rng(seed)
    v = rng.normal(size=(N, 3))
    v /= np.linalg.norm(v, axis=1, keepdims=True)
    Q, _ = np.linalg.qr(rng.normal(size=(m, 3)))
    X = v @ Q.T
    for r in [0.55, 0.6]:
        simp, by_dim = alpha_complex(X, None, r * r, 3)
        b = betti_mod2(by_dim, simp, 3)
        print(f"  S^2 in R^{m}, r={r}: sizes {[len(x) for x in by_dim]} betti_F2={b[:3]}")
        if b[:3] == [1, 0, 1]:
            return True
    return False


def test_torus(N=200, seed=6):
    """Flat torus sampled in R^4 (two circles): expect b = (1,2,1)."""
    rng = np.random.default_rng(seed)
    t = rng.random((N, 2)) * 2 * math.pi
    X = np.stack([np.cos(t[:, 0]), np.sin(t[:, 0]),
                  np.cos(t[:, 1]), np.sin(t[:, 1])], axis=1)
    for r in [0.55, 0.6, 0.65]:
        simp, by_dim = alpha_complex(X, None, r * r, 3)
        b = betti_mod2(by_dim, simp, 3)
        print(f"  T^2 in R^4, r={r}: sizes {[len(x) for x in by_dim]} betti_F2={b[:3]}")
        if b[:3] == [1, 2, 1]:
            return True
    return False


def test_dim_independence(N=60, seed=8):
    """Same point cloud, padded with zero coordinates into a higher ambient
    dimension: the algorithm should return an identical complex."""
    rng = np.random.default_rng(seed)
    X = rng.random((N, 3))
    a, _ = alpha_complex(X, None, 0.3 ** 2, 3)
    Xp = np.hstack([X, np.zeros((N, 40))])
    Q, _ = np.linalg.qr(rng.normal(size=(43, 43)))
    b, _ = alpha_complex(Xp @ Q.T, None, 0.3 ** 2, 3)
    same = set(a) == set(b)
    worst = max((abs(a[s][0] - b[s][0]) for s in a if s in b), default=0.0)
    print(f"  R^3 vs isometric copy in R^43: {len(a)} vs {len(b)} simplices, "
          f"max |dw|={worst:.2e}, {'OK' if same and worst < 1e-9 else 'FAIL'}")
    return same and worst < 1e-9


if __name__ == "__main__":
    res = []
    print("[6] degenerate configurations")
    res.append(test_degenerate_grid())
    print("[7] early-termination safety")
    res.append(test_early_exit_safety())
    print("[8] sphere in higher ambient dimension")
    res.append(test_high_dim_sphere())
    print("[9] torus")
    res.append(test_torus())
    print("[10] embedding independence")
    res.append(test_dim_independence())
    print("ALL PASS" if all(res) else "SOME FAILURES")
