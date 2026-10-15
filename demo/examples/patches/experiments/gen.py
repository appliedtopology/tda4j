"""Inputs for the Klein-bottle experiments.  Run: venv/bin/python -I gen.py <out dir>

1. core_k{K}_T{T}.csv: the densest T% of the 50 000-point sample by 1/rho_K (K-th neighbour, self not counted: the
   convention that reproduces the shipped cores), for a FIXED small grid K in {1,2,3,5,8}, T in {5,10,20}.
2. ideal_klein_N.csv: the paper's "ideal Klein bottle" (Carlsson et al. 2008, section 7): the polynomials
   c(ax+by)^2 + d(ax+by) with (a,b) = (cos t, sin t), (c,d) = (cos s, sin s), evaluated on the 9 points of {-1,0,1}^2,
   mean subtracted, divided by the norm. (t, s) uniform in [0, 2pi)^2 -- uniform in the parameters, not on the surface.
   Euclidean norm on the 9 pixel values (NOT the paper's D-norm DCT coordinates), so a model of the patches, not the
   paper's exact embedding.
3. torus_N.csv: the flat torus (cos t, sin t, cos s, sin s), the control the pass criterion must tell apart.
"""
import sys
from pathlib import Path

import numpy as np

out = Path(sys.argv[1])
rng = np.random.default_rng(20261015)

knn = np.load(out / "knn_n50000.npy")
X = np.loadtxt(out / "n50000Dct.csv", delimiter=",")
for k in (1, 2, 3, 5, 8):
    order = np.argsort(knn[:, k - 1], kind="stable")
    for T in (5, 10, 20):
        n = X.shape[0] * T // 100
        idx = np.sort(order[:n])
        np.savetxt(out / f"core_k{k}_T{T}.csv", X[idx], delimiter=",", fmt="%.17g")
        print(f"core k={k} T={T}% -> {n} points, rho_k in [{knn[idx, k-1].min():.4f}, {knn[idx, k-1].max():.4f}]")

pts = np.array([(x, y) for y in (-1, 0, 1) for x in (-1, 0, 1)], dtype=float)  # the 9 grid points
for N in (5000, 15000):
    t = rng.uniform(0, 2 * np.pi, N)
    s = rng.uniform(0, 2 * np.pi, N)
    a, b, c, d = np.cos(t), np.sin(t), np.cos(s), np.sin(s)
    lin = a[:, None] * pts[None, :, 0] + b[:, None] * pts[None, :, 1]      # ax+by at the 9 points
    v = c[:, None] * lin**2 + d[:, None] * lin
    v -= v.mean(axis=1, keepdims=True)
    v /= np.linalg.norm(v, axis=1, keepdims=True)
    np.savetxt(out / f"ideal_klein_{N}.csv", v, delimiter=",", fmt="%.17g")
    tt = rng.uniform(0, 2 * np.pi, N)
    ss = rng.uniform(0, 2 * np.pi, N)
    tor = np.stack([np.cos(tt), np.sin(tt), np.cos(ss), np.sin(ss)], axis=1)
    np.savetxt(out / f"torus_{N}.csv", tor, delimiter=",", fmt="%.17g")
    print(f"ideal_klein_{N}.csv {v.shape}  torus_{N}.csv {tor.shape}")
