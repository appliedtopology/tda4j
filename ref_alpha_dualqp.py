"""Reference (validation) implementation of Carlsson & Carlsson 2024,
'Computing the alpha complex using dual active set quadratic programming'.

Everything here is written to mirror the Scala port exactly: same variable
names, same control flow, dense arrays only, no numpy tricks that wouldn't
translate.
"""
import math
import numpy as np

TOL = 1e-10


# ---------------------------------------------------------------------------
# Cholesky workspace with append / delete (stand-in for DAQP's LDL^T updates)
# ---------------------------------------------------------------------------
class Chol:
    """Maintains L lower triangular with L L^T = B_W for the working set W."""

    def __init__(self, cap):
        self.L = np.zeros((cap, cap))
        self.n = 0

    def reset(self):
        self.n = 0

    def solve_lower(self, b):
        """L z = b"""
        n = self.n
        z = np.zeros(n)
        for i in range(n):
            s = b[i]
            for j in range(i):
                s -= self.L[i, j] * z[j]
            z[i] = s / self.L[i, i]
        return z

    def solve_upper(self, z):
        """L^T w = z"""
        n = self.n
        w = np.zeros(n)
        for i in range(n - 1, -1, -1):
            s = z[i]
            for j in range(i + 1, n):
                s -= self.L[j, i] * w[j]
            w[i] = s / self.L[i, i]
        return w

    def solve(self, b):
        return self.solve_upper(self.solve_lower(b))

    def try_append(self, bcol, beta, tol):
        """bcol = B[W, j], beta = B[j,j].  Returns (ok, s, l).

        l = L^{-1} bcol,  s = beta - l.l  (Schur complement).  If s > tol the
        column is appended and ok=True."""
        n = self.n
        l = self.solve_lower(bcol) if n > 0 else np.zeros(0)
        s = beta - float(np.dot(l, l))
        if s <= tol:
            return False, s, l
        self.L[n, :n] = l
        self.L[n, n] = math.sqrt(s)
        self.n += 1
        return True, s, l

    def delete(self, k):
        """Remove index k from W.  Delete row k of L (keeping all columns),
        then Givens-rotate the extra superdiagonal away."""
        n = self.n
        R = np.zeros((n - 1, n))
        R[:k, :] = self.L[:k, :n]
        R[k:, :] = self.L[k + 1:n, :n]
        for i in range(k, n - 1):
            a, b = R[i, i], R[i, i + 1]
            rho = math.hypot(a, b)
            if rho == 0.0:
                continue
            c, s = a / rho, b / rho
            for r in range(i, n - 1):
                x, y = R[r, i], R[r, i + 1]
                R[r, i] = c * x + s * y
                R[r, i + 1] = -s * x + c * y
        self.L[:n - 1, :n - 1] = R[:, :n - 1]
        self.L[n - 1, :] = 0.0
        self.L[:, n - 1] = 0.0
        self.n = n - 1


# ---------------------------------------------------------------------------
# DualQP:  max_lambda  -1/2 l^T B l + U^T l   s.t.  l_i >= 0 for i not in J
# ---------------------------------------------------------------------------
INFEASIBLE = float("inf")


class DualQP:
    def __init__(self, n):
        self.n = n
        self.chol = Chol(n)
        self.ws = [0] * n
        self.lam = np.zeros(n)
        self.in_w = np.zeros(n, dtype=bool)
        self.frozen = np.zeros(n, dtype=bool)  # redundant equalities
        self.grad = np.zeros(n)
        self.iters = 0

    def solve(self, B, U, J, c1, tol=TOL, max_iter=None):
        """Returns (cstar, lambda_full) with cstar = inf if ruled out."""
        n = self.n
        if max_iter is None:
            max_iter = 10 * n + 100
        self.chol.reset()
        nw = 0
        neq = 0
        self.lam[:] = 0.0
        self.in_w[:] = False
        self.frozen[:] = False
        self.iters = 0

        # ---- phase 1: equality constraints (free sign, never dropped) -----
        for q in J:
            bcol = np.array([B[self.ws[t], q] for t in range(nw)])
            ok, s, l = self.chol.try_append(bcol, B[q, q], tol)
            if not ok:
                # A_q is a linear combination of the rows already in W.
                r = self.chol.solve_upper(l) if nw > 0 else np.zeros(0)
                g = U[q] - sum(B[self.ws[t], q] * self.lam[t] for t in range(nw))
                if abs(g) > math.sqrt(tol) * (1.0 + abs(U[q])):
                    return INFEASIBLE, None  # inconsistent equalities
                self.frozen[q] = True        # redundant: ignore it
                continue
            self.ws[nw] = q
            self.in_w[q] = True
            nw += 1
            neq += 1
            rhs = np.array([U[self.ws[t]] for t in range(nw)])
            self.lam[:nw] = self.chol.solve(rhs)

        d = self._dual_obj(B, U, nw)
        if d > c1:
            return INFEASIBLE, None

        # ---- phase 2: DAQP-style dual active set on the inequalities ------
        while True:
            self.iters += 1
            if self.iters > max_iter:
                raise RuntimeError("dual QP did not converge")

            # grad_i = U_i - (B lambda)_i   (= A_i y - V_i, the constraint slack)
            for i in range(n):
                g = U[i]
                for t in range(nw):
                    g -= B[i, self.ws[t]] * self.lam[t]
                self.grad[i] = g

            jbest, gbest = -1, tol
            for i in range(n):
                if not self.in_w[i] and not self.frozen[i] and self.grad[i] > gbest:
                    jbest, gbest = i, self.grad[i]
            if jbest < 0:
                return self._dual_obj(B, U, nw), self._expand(nw)

            j = jbest
            bcol = np.array([B[self.ws[t], j] for t in range(nw)])
            ok, s, l = self.chol.try_append(bcol, B[j, j], tol)

            if not ok:
                # Singular: ray  lambda_j += t,  lambda_W -= t * r
                r = self.chol.solve_upper(l) if nw > 0 else np.zeros(0)
                tmax, kblock = math.inf, -1
                for t in range(neq, nw):
                    if r[t] > tol:
                        cand = self.lam[t] / r[t]
                        if cand < tmax:
                            tmax, kblock = cand, t
                if kblock < 0:
                    return INFEASIBLE, None       # dual unbounded => primal infeasible
                for t in range(nw):
                    self.lam[t] -= tmax * r[t]
                self._drop(kblock, nw)
                nw -= 1
                d = self._dual_obj(B, U, nw)
                if d > c1:
                    return INFEASIBLE, None
                continue

            # j appended; solve the new equality-constrained subproblem
            self.ws[nw] = j
            self.in_w[j] = True
            self.lam[nw] = 0.0
            nw += 1
            while True:
                rhs = np.array([U[self.ws[t]] for t in range(nw)])
                lstar = self.chol.solve(rhs)
                alpha, kblock = 1.0, -1
                for t in range(neq, nw):
                    if lstar[t] < -tol:
                        denom = self.lam[t] - lstar[t]
                        if denom > tol:
                            cand = self.lam[t] / denom
                            if cand < alpha:
                                alpha, kblock = cand, t
                if kblock < 0:
                    self.lam[:nw] = lstar
                    break
                self.lam[:nw] = self.lam[:nw] + alpha * (lstar - self.lam[:nw])
                self.lam[kblock] = 0.0
                self._drop(kblock, nw)
                nw -= 1

            d = self._dual_obj(B, U, nw)
            if d > c1:
                return INFEASIBLE, None

    def _drop(self, k, nw):
        self.in_w[self.ws[k]] = False
        self.chol.delete(k)
        for t in range(k, nw - 1):
            self.ws[t] = self.ws[t + 1]
            self.lam[t] = self.lam[t + 1]
        self.lam[nw - 1] = 0.0

    def _dual_obj(self, B, U, nw):
        # -1/2 l^T B l + U^T l   evaluated on the working set
        q = 0.0
        lin = 0.0
        for a in range(nw):
            lin += U[self.ws[a]] * self.lam[a]
            for b in range(nw):
                q += self.lam[a] * B[self.ws[a], self.ws[b]] * self.lam[b]
        return -0.5 * q + lin

    def _expand(self, nw):
        out = np.zeros(self.n)
        for t in range(nw):
            out[self.ws[t]] = self.lam[t]
        return out


# ---------------------------------------------------------------------------
# Alpha complex
# ---------------------------------------------------------------------------
def alpha_complex(X, p=None, a1=1.0, maxdim=2, tol=TOL):
    """X: (N,m) coordinates, p: (N,) power weights, a1: cutoff on the power."""
    N, m = X.shape
    if p is None:
        p = np.zeros(N)
    gram = X @ X.T

    def d2(i, j):
        return gram[i, i] - 2 * gram[i, j] + gram[j, j]

    # ---- Cech graph of the weighted ball cover -----------------------------
    active = [i for i in range(N) if -p[i] <= a1]
    rad = {i: math.sqrt(max(a1 + p[i], 0.0)) for i in active}
    nbrs = {i: [] for i in active}
    for ii, i in enumerate(active):
        for j in active[ii + 1:]:
            if d2(i, j) <= (rad[i] + rad[j]) ** 2 + 1e-12:
                nbrs[i].append(j)
                nbrs[j].append(i)
    for i in active:
        nbrs[i].sort()

    simplices = {}         # tuple -> (weight, witness coords)
    by_dim = [[] for _ in range(maxdim + 1)]

    for k in range(maxdim + 1):
        # candidate simplices grouped by their minimal vertex
        cand = {i: [] for i in active}
        if k == 0:
            for i in active:
                cand[i].append(())
        elif k == 1:
            for i in active:
                for j in nbrs[i]:
                    if j > i:
                        cand[i].append((j,))
        else:
            prev = set(by_dim[k - 1])
            for tau in by_dim[k - 1]:
                x = tau[0]
                for v in nbrs[x]:
                    if v <= tau[-1]:
                        continue
                    sigma = tau + (v,)
                    ok = True
                    for drop in range(len(sigma)):
                        facet = sigma[:drop] + sigma[drop + 1:]
                        if facet not in prev:
                            ok = False
                            break
                    if ok:
                        cand[x].append(sigma[1:])

        for x in active:
            cs = cand[x]
            if not cs:
                continue
            nb = nbrs[x]
            n = len(nb)
            pos = {v: t for t, v in enumerate(nb)}
            # B and U: computed once per vertex per dimension (paper, lines 11-12)
            D = X[nb] - X[x]
            B = D @ D.T
            U = np.array([0.5 * (p[v] - p[x] - d2(v, x)) for v in nb])
            c1 = 0.5 * (a1 + p[x])
            qp = DualQP(n) if n > 0 else None

            for rest in cs:
                J = [pos[v] for v in rest]
                if n == 0:
                    cstar, lam = 0.0, None
                else:
                    cstar, lam = qp.solve(B, U, J, c1, tol)
                if cstar <= c1 + 1e-12:
                    sigma = (x,) + rest
                    w = 2.0 * cstar - p[x]
                    y = X[x].copy()
                    if lam is not None:
                        for t in range(n):
                            if lam[t] != 0.0:
                                y -= lam[t] * (X[nb[t]] - X[x])
                    simplices[sigma] = (w, y)
                    by_dim[k].append(sigma)

    return simplices, by_dim


# ---------------------------------------------------------------------------
# Independent ground truth via scipy Delaunay (unweighted, low dimension)
# ---------------------------------------------------------------------------
def ground_truth(X, r, maxdim):
    from scipy.spatial import Delaunay
    from itertools import combinations
    tri = Delaunay(X)
    m = X.shape[1]

    def circum(idx):
        pts = X[list(idx)]
        p0 = pts[0]
        A = 2 * (pts[1:] - p0)
        b = np.sum(pts[1:] ** 2, axis=1) - np.sum(p0 ** 2)
        # least-norm solution inside the affine hull of the points
        sol, *_ = np.linalg.lstsq(A, b, rcond=None)
        # project p0 -> affine hull constraint: center = p0 + span(pts-p0)
        Dv = pts[1:] - p0
        # solve for center = p0 + Dv^T t with A(p0 + Dv^T t) = b
        M = A @ Dv.T
        rhs = b - A @ p0
        t = np.linalg.solve(M, rhs) if M.shape[0] > 0 else np.zeros(0)
        c = p0 + Dv.T @ t
        return c, np.linalg.norm(c - p0)

    alpha = {}
    full = [tuple(sorted(s)) for s in tri.simplices]
    # all faces of Delaunay simplices
    faces = set()
    for s in full:
        for k in range(1, maxdim + 2):
            for f in combinations(s, k):
                faces.add(f)

    # alpha value: circumradius if Gabriel (circumcenter in the Voronoi cell),
    # else min over cofaces
    def gabriel(idx, c, rho):
        for i in range(len(X)):
            if i in idx:
                continue
            if np.linalg.norm(X[i] - c) < rho - 1e-9:
                return False
        return True

    for f in sorted(faces, key=len, reverse=True):
        c, rho = circum(f)
        if gabriel(f, c, rho):
            alpha[f] = rho
        else:
            best = math.inf
            for i in range(len(X)):
                if i in f:
                    continue
                sup = tuple(sorted(f + (i,)))
                if sup in alpha:
                    best = min(best, alpha[sup])
            alpha[f] = best
    return {f: a for f, a in alpha.items() if a <= r + 1e-9 and len(f) <= maxdim + 1}
