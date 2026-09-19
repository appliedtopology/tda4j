import math
import numpy as np
from itertools import combinations
from ref_alpha import alpha_complex, ground_truth, DualQP


def test_vs_delaunay(N=40, m=2, r=0.35, seed=0, maxdim=None):
    if maxdim is None:
        maxdim = m
    rng = np.random.default_rng(seed)
    X = rng.random((N, m))
    simp, by_dim = alpha_complex(X, None, r * r, maxdim)
    gt = ground_truth(X, r, maxdim)

    mine = {s: math.sqrt(max(w, 0.0)) for s, (w, y) in simp.items()}
    ok = True
    if set(mine) != set(gt):
        ok = False
        extra = set(mine) - set(gt)
        missing = set(gt) - set(mine)
        print(f"  MISMATCH sets: extra={len(extra)} missing={len(missing)}")
        for s in list(extra)[:5]:
            print("    extra  ", s, mine[s])
        for s in list(missing)[:5]:
            print("    missing", s, gt[s])
    else:
        worst = max((abs(mine[s] - gt[s]) for s in mine), default=0.0)
        if worst > 1e-7:
            ok = False
            print(f"  MISMATCH values: worst={worst:.3e}")
    counts = [len([s for s in mine if len(s) == k + 1]) for k in range(maxdim + 1)]
    euler = sum((-1) ** k * c for k, c in enumerate(counts))
    print(f"  N={N} m={m} r={r} -> sizes {counts}, chi={euler}, {'OK' if ok else 'FAIL'}")
    return ok


def brute_qp(X, p, sigma, a1):
    """Ground truth for one QP by projected-gradient-free enumeration:
    solve min ||y-x||^2 s.t. pi_x = pi_z (z in sigma), pi_x <= pi_z (else)
    with scipy's generic SLSQP."""
    from scipy.optimize import minimize
    N, m = X.shape
    x = X[sigma[0]]

    def A_row(i):
        return X[i] - x

    def V(i):
        return 0.5 * (X[i] @ X[i] - x @ x - p[i] + p[sigma[0]])

    cons = []
    for z in sigma[1:]:
        cons.append({"type": "eq", "fun": (lambda z: (lambda y: A_row(z) @ y - V(z)))(z)})
    for i in range(N):
        if i in sigma:
            continue
        cons.append({"type": "ineq", "fun": (lambda i: (lambda y: V(i) - A_row(i) @ y))(i)})
    best = None
    for start in range(4):
        y0 = X[list(sigma)].mean(axis=0) if start == 0 else X[list(sigma)].mean(axis=0) + 0.1 * np.random.randn(m)
        res = minimize(lambda y: 0.5 * np.sum((y - x) ** 2), y0, constraints=cons,
                       method="SLSQP", options={"maxiter": 500, "ftol": 1e-12})
        if res.success:
            v = 0.5 * np.sum((res.x - x) ** 2)
            if best is None or v < best:
                best = v
    return best


def test_qp_against_slsqp(N=14, m=3, seed=3, trials=25):
    rng = np.random.default_rng(seed)
    X = rng.random((N, m))
    p = rng.random(N) * 0.05
    a1 = 0.5
    gram = X @ X.T
    bad = 0
    checked = 0
    for _ in range(trials):
        k = rng.integers(1, 4)
        sigma = tuple(sorted(rng.choice(N, size=k + 1, replace=False).tolist()))
        x = sigma[0]
        nb = [i for i in range(N) if i != x]
        pos = {v: t for t, v in enumerate(nb)}
        D = X[nb] - X[x]
        B = D @ D.T
        U = np.array([0.5 * (p[v] - p[x] - (gram[v, v] - 2 * gram[v, x] + gram[x, x])) for v in nb])
        c1 = 0.5 * (a1 + p[x])
        qp = DualQP(len(nb))
        cstar, lam = qp.solve(B, U, [pos[v] for v in sigma[1:]], 1e18)
        ref = brute_qp(X, p, sigma, a1)
        checked += 1
        if cstar == math.inf:
            if ref is not None and ref < 1e6:
                print(f"  sigma={sigma}: mine=INF slsqp={ref}")
                bad += 1
        else:
            if ref is None or abs(ref - cstar) > 1e-6 * (1 + abs(cstar)):
                print(f"  sigma={sigma}: mine={cstar:.9f} slsqp={ref}")
                bad += 1
    print(f"  QP cross-check: {checked - bad}/{checked} agree with SLSQP")
    return bad == 0


def test_monotone_and_closed(N=60, m=3, r=0.3, seed=7, maxdim=3, weighted=False):
    rng = np.random.default_rng(seed)
    X = rng.random((N, m))
    p = rng.random(N) * 0.02 if weighted else np.zeros(N)
    simp, by_dim = alpha_complex(X, p, r * r, maxdim)
    ok = True
    for s, (w, y) in simp.items():
        for drop in range(len(s)):
            f = s[:drop] + s[drop + 1:]
            if not f:
                continue
            if f not in simp:
                print(f"  NOT CLOSED: {s} missing face {f}")
                ok = False
            elif simp[f][0] > w + 1e-9:
                print(f"  NOT MONOTONE: w{f}={simp[f][0]:.6f} > w{s}={w:.6f}")
                ok = False
    counts = [len(b) for b in by_dim]
    print(f"  closure/monotonicity N={N} m={m} weighted={weighted}: sizes {counts}, {'OK' if ok else 'FAIL'}")
    return ok


def test_witness(N=50, m=3, r=0.3, seed=11, maxdim=2):
    """The witness Phi(sigma) must be equidistant (in power) from all vertices
    of sigma, and no other site may have smaller power at that point."""
    rng = np.random.default_rng(seed)
    X = rng.random((N, m))
    p = rng.random(N) * 0.02
    simp, _ = alpha_complex(X, p, r * r, maxdim)
    worst_eq, worst_min = 0.0, 0.0
    for s, (w, y) in simp.items():
        vals = [np.sum((y - X[i]) ** 2) - p[i] for i in s]
        worst_eq = max(worst_eq, max(vals) - min(vals))
        allv = [np.sum((y - X[i]) ** 2) - p[i] for i in range(N)]
        worst_min = max(worst_min, min(vals) - min(allv))
        assert abs(vals[0] - w) < 1e-7, (s, vals[0], w)
    print(f"  witness: max power spread over sigma = {worst_eq:.2e}, "
          f"max power excess vs global min = {worst_min:.2e}")
    return worst_eq < 1e-7 and worst_min < 1e-7


def test_circle_homology(N=60, r=0.45, seed=1):
    """Points on a circle: alpha complex should be homotopy equivalent to S^1."""
    ang = np.sort(np.random.default_rng(seed).random(N) * 2 * math.pi)
    X = np.stack([np.cos(ang), np.sin(ang)], axis=1)
    simp, by_dim = alpha_complex(X, None, r * r, 2)
    counts = [len(b) for b in by_dim]
    chi = counts[0] - counts[1] + counts[2]
    print(f"  circle: sizes {counts}, chi={chi} (expect 0)")
    return chi == 0


if __name__ == "__main__":
    np.random.seed(0)
    results = []
    print("[1] against scipy Delaunay ground truth (unweighted)")
    for seed, N, m, r in [(0, 40, 2, 0.35), (1, 60, 2, 0.25), (2, 35, 3, 0.45), (5, 25, 3, 0.6)]:
        results.append(test_vs_delaunay(N, m, r, seed))
    print("[2] single-QP cross-check against SLSQP (weighted)")
    results.append(test_qp_against_slsqp())
    print("[3] face closure + filtration monotonicity")
    results.append(test_monotone_and_closed(weighted=False))
    results.append(test_monotone_and_closed(weighted=True, seed=13))
    print("[4] witness map")
    results.append(test_witness())
    print("[5] circle")
    results.append(test_circle_homology())
    print("ALL PASS" if all(results) else "SOME FAILURES")
